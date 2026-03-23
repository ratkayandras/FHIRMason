package dev.ratkay.operation

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.hl7.fhir.r4.model.Base
import org.hl7.fhir.r4.model.OperationOutcome
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

class AsyncOperationResult {

    private sealed class TaskNode {
        abstract val key: String

        class Root(override val key: String, val block: suspend () -> Base) : TaskNode()
        class RootList(override val key: String, val block: suspend () -> List<Base>) : TaskNode()
        class Dependent(
            override val key: String,
            val deps: List<String>,
            val block: suspend (Map<String, List<Base>>) -> Base
        ) : TaskNode()
        class DependentList(
            override val key: String,
            val deps: List<String>,
            val block: suspend (Map<String, List<Base>>) -> List<Base>
        ) : TaskNode()
    }

    private val nodes: LinkedHashMap<String, TaskNode> = LinkedHashMap()

    fun add(key: String, block: suspend () -> Base): AsyncOperationResult = also {
        require(!nodes.containsKey(key)) { "Duplicate task key: '$key'" }
        nodes[key] = TaskNode.Root(key, block)
    }

    fun addList(key: String, block: suspend () -> List<Base>): AsyncOperationResult = also {
        require(!nodes.containsKey(key)) { "Duplicate task key: '$key'" }
        nodes[key] = TaskNode.RootList(key, block)
    }

    fun addAfter(
        key: String,
        vararg deps: String,
        block: suspend (Map<String, List<Base>>) -> Base
    ): AsyncOperationResult = also {
        require(!nodes.containsKey(key)) { "Duplicate task key: '$key'" }
        val depList = deps.toList()
        requireKeysExist(depList)
        requireNoCycle(key, depList)
        nodes[key] = TaskNode.Dependent(key, depList, block)
    }

    fun addListAfter(
        key: String,
        vararg deps: String,
        block: suspend (Map<String, List<Base>>) -> List<Base>
    ): AsyncOperationResult = also {
        require(!nodes.containsKey(key)) { "Duplicate task key: '$key'" }
        val depList = deps.toList()
        requireKeysExist(depList)
        requireNoCycle(key, depList)
        nodes[key] = TaskNode.DependentList(key, depList, block)
    }

    // Type-safe single-dependency convenience methods

    fun <T : Base> addAfter(
        key: String,
        dep: String,
        type: KClass<T>,
        block: suspend (T) -> Base
    ): AsyncOperationResult = addAfter(key, dep) { deps ->
        val value = deps[dep]!!.filterIsInstance(type.java).first()
        block(value)
    }

    fun <T : Base> addListAfter(
        key: String,
        dep: String,
        type: KClass<T>,
        block: suspend (T) -> List<Base>
    ): AsyncOperationResult = addListAfter(key, dep) { deps ->
        val value = deps[dep]!!.filterIsInstance(type.java).first()
        block(value)
    }

    // Type-safe list-injection convenience methods

    fun <T : Base> addAfterAll(
        key: String,
        dep: String,
        type: KClass<T>,
        block: suspend (List<T>) -> Base
    ): AsyncOperationResult = addAfter(key, dep) { deps ->
        val values = deps[dep]!!.filterIsInstance(type.java)
        block(values)
    }

    fun <T : Base> addListAfterAll(
        key: String,
        dep: String,
        type: KClass<T>,
        block: suspend (List<T>) -> List<Base>
    ): AsyncOperationResult = addListAfter(key, dep) { deps ->
        val values = deps[dep]!!.filterIsInstance(type.java)
        block(values)
    }

    suspend fun run(): OperationResult<Base> = coroutineScope {
        val resolved = mutableMapOf<String, Deferred<List<Base>?>>()
        val failedTasks = ConcurrentHashMap<String, OperationOutcome>()

        fun launchNode(node: TaskNode): Deferred<List<Base>?> =
            resolved.getOrPut(node.key) {
                async {
                    when (node) {
                        is TaskNode.Root -> try {
                            listOf(node.block())
                        } catch (e: Exception) {
                            failedTasks[node.key] = e.toOperationOutcome()
                            null
                        }
                        is TaskNode.RootList -> try {
                            node.block()
                        } catch (e: Exception) {
                            failedTasks[node.key] = e.toOperationOutcome()
                            null
                        }
                        is TaskNode.Dependent -> {
                            val depResults = mutableMapOf<String, List<Base>>()
                            var failedDep: String? = null
                            for (depKey in node.deps) {
                                val depResult = launchNode(nodes[depKey]!!).await()
                                if (depResult == null) {
                                    failedDep = depKey
                                    break
                                }
                                depResults[depKey] = depResult
                            }
                            if (failedDep != null) {
                                failedTasks[node.key] = dependencyFailureOutcome(node.key, failedDep)
                                null
                            } else {
                                try {
                                    listOf(node.block(depResults))
                                } catch (e: Exception) {
                                    failedTasks[node.key] = e.toOperationOutcome()
                                    null
                                }
                            }
                        }
                        is TaskNode.DependentList -> {
                            val depResults = mutableMapOf<String, List<Base>>()
                            var failedDep: String? = null
                            for (depKey in node.deps) {
                                val depResult = launchNode(nodes[depKey]!!).await()
                                if (depResult == null) {
                                    failedDep = depKey
                                    break
                                }
                                depResults[depKey] = depResult
                            }
                            if (failedDep != null) {
                                failedTasks[node.key] = dependencyFailureOutcome(node.key, failedDep)
                                null
                            } else {
                                try {
                                    node.block(depResults)
                                } catch (e: Exception) {
                                    failedTasks[node.key] = e.toOperationOutcome()
                                    null
                                }
                            }
                        }
                    }
                }
            }

        nodes.values.forEach { launchNode(it) }

        val accumulator = mutableMapOf<String, MutableList<Base>>()
        resolved.forEach { (key, deferred) ->
            val taskResult = deferred.await()
            if (taskResult != null) {
                accumulator.getOrPut(key) { mutableListOf() }.addAll(taskResult)
            }
        }

        val outcomes = failedTasks.values.toList()
        OperationResult.fromMap(accumulator, outcomes, failedTasks)
    }

    fun runBlocking(): OperationResult<Base> = runBlocking { run() }

    private fun dependencyFailureOutcome(taskKey: String, failedDep: String): OperationOutcome =
        OperationOutcome().apply {
            addIssue().apply {
                severity = OperationOutcome.IssueSeverity.ERROR
                code = OperationOutcome.IssueType.PROCESSING
                diagnostics = "Task '$taskKey' skipped: dependency '$failedDep' failed"
            }
        }

    private fun requireKeysExist(deps: List<String>) {
        deps.forEach { dep ->
            require(nodes.containsKey(dep)) {
                "Dependency '$dep' has not been registered. Register tasks in dependency order."
            }
        }
    }

    private fun requireNoCycle(newKey: String, deps: List<String>) {
        fun reachable(from: String, target: String, visited: MutableSet<String>): Boolean {
            if (from == target) return true
            if (!visited.add(from)) return false
            val node = nodes[from] ?: return false
            val nodeDeps = when (node) {
                is TaskNode.Dependent -> node.deps
                is TaskNode.DependentList -> node.deps
                else -> emptyList()
            }
            return nodeDeps.any { reachable(it, target, visited) }
        }
        deps.forEach { dep ->
            require(!reachable(dep, newKey, mutableSetOf())) {
                "Adding '$newKey' with dependency '$dep' would create a cycle."
            }
        }
    }
}
