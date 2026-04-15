package dev.ratkay.operation.dstu3

import dev.ratkay.operation.ErrorStrategy
import dev.ratkay.operation.StepMetrics
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executor
import org.hl7.fhir.dstu3.model.Base
import org.hl7.fhir.dstu3.model.OperationOutcome
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.time.measureTime
import kotlin.time.measureTimedValue

class AsyncOperationResult {

    private val logger = LoggerFactory.getLogger(AsyncOperationResult::class.java)

    private var timingEnabled: Boolean = false
    private var dagTimeoutMs: Long? = null
    private var executor: Executor? = null

    private val taskMetrics = ConcurrentHashMap<String, StepMetrics>()
    private var totalDurationMs: Long = 0L

    private sealed class TaskNode {
        abstract val key: String
        abstract val deps: List<String>
        abstract val block: suspend (Map<String, List<Base>>) -> List<Base>

        class Independent(
            override val key: String,
            override val block: suspend (Map<String, List<Base>>) -> List<Base>
        ) : TaskNode() {
            override val deps: List<String> = emptyList()
        }

        class Dependent(
            override val key: String,
            override val deps: List<String>,
            override val block: suspend (Map<String, List<Base>>) -> List<Base>
        ) : TaskNode()
    }

    private val nodes: LinkedHashMap<String, TaskNode> = LinkedHashMap()

    /** Enables per-task metrics collection. [getMetrics] returns empty when not called. */
    fun timed(): AsyncOperationResult = also { timingEnabled = true }

    /**
     * Sets a maximum wall-clock time for the entire DAG execution.
     *
     * If [run] does not complete within [durationMs] milliseconds, it returns an
     * [OperationResult] containing a single `TIMEOUT`-coded [OperationOutcome] and no task
     * results. [getTotalDuration] will return `0` on timeout because the duration assignment
     * inside the DAG execution is interrupted before it can run.
     *
     * [runBlocking] inherits this timeout automatically.
     *
     * @param durationMs maximum allowed wall-clock time in milliseconds; must be > 0
     */
    fun timeout(durationMs: Long): AsyncOperationResult = also {
        require(durationMs > 0) { "durationMs must be positive" }
        dagTimeoutMs = durationMs
    }

    /**
     * Sets the [java.util.concurrent.Executor] used to run all tasks in this DAG.
     *
     * By default no executor is set and tasks inherit the dispatcher of the calling coroutine.
     * Set an executor to pin tasks to a specific thread pool — for example, use
     * [java.util.concurrent.Executors.newFixedThreadPool] for a bounded I/O pool, or pass
     * [kotlinx.coroutines.Dispatchers.IO] directly (it implements [java.util.concurrent.Executor]).
     *
     * @param executor the executor that all DAG tasks will run on
     */
    fun withExecutor(executor: Executor): AsyncOperationResult = also {
        this.executor = executor
    }

    /** Returns a snapshot of [StepMetrics] collected per task after [run] or [runBlocking]. */
    fun getMetrics(): Map<String, StepMetrics> = taskMetrics.toMap()

    /** Returns total wall-clock DAG execution time in milliseconds. Zero before [run] completes. */
    fun getTotalDuration(): Long = totalDurationMs

    private fun registerNode(key: String, node: TaskNode) {
        require(!nodes.containsKey(key)) { "Duplicate task key: '$key'" }
        nodes[key] = node
    }

    fun add(key: String, block: suspend () -> Base): AsyncOperationResult = also {
        registerNode(key, TaskNode.Independent(key) { listOf(block()) })
    }

    fun addList(key: String, block: suspend () -> List<Base>): AsyncOperationResult = also {
        registerNode(key, TaskNode.Independent(key) { block() })
    }

    fun addAfter(
        key: String,
        vararg deps: String,
        block: suspend (Map<String, List<Base>>) -> Base
    ): AsyncOperationResult = also {
        val depList = deps.toList()
        requireKeysExist(depList)
        requireNoCycle(key, depList)
        registerNode(key, TaskNode.Dependent(key, depList) { map -> listOf(block(map)) })
    }

    fun addListAfter(
        key: String,
        vararg deps: String,
        block: suspend (Map<String, List<Base>>) -> List<Base>
    ): AsyncOperationResult = also {
        val depList = deps.toList()
        requireKeysExist(depList)
        requireNoCycle(key, depList)
        registerNode(key, TaskNode.Dependent(key, depList, block))
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

    // ── Retry helper ─────────────────────────────────────────────────────────

    private suspend fun <R> suspendExecuteWithRetry(
        maxAttempts: Int,
        initialDelayMs: Long,
        retryOn: (Exception) -> Boolean,
        block: suspend () -> R
    ): R {
        var lastException: Exception? = null
        var delayMs = initialDelayMs
        for (attempt in 1..maxAttempts) {
            try {
                return block()
            } catch (e: Exception) {
                lastException = e
                if (!retryOn(e) || attempt == maxAttempts) break
                delay(delayMs)
                delayMs *= 2
            }
        }
        throw lastException!!
    }

    // ── Independent task with retry ──────────────────────────────────────────

    @JvmOverloads
    fun <R : Base> addWithRetry(
        key: String,
        maxAttempts: Int = 3,
        initialDelayMs: Long = 500,
        retryOn: (Exception) -> Boolean = { true },
        block: suspend () -> R
    ): AsyncOperationResult {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
        require(initialDelayMs >= 0) { "initialDelayMs must be non-negative" }
        return also {
            registerNode(key, TaskNode.Independent(key) { _ ->
                listOf(suspendExecuteWithRetry(maxAttempts, initialDelayMs, retryOn) { block() })
            })
        }
    }

    // ── Independent task with timeout ────────────────────────────────────────

    fun addWithTimeout(key: String, timeoutMs: Long, block: suspend () -> Base): AsyncOperationResult = also {
        require(timeoutMs > 0) { "timeoutMs must be positive" }
        registerNode(key, TaskNode.Independent(key) {
            withTimeout(timeoutMs) { listOf(block()) }
        })
    }

    fun addListWithTimeout(key: String, timeoutMs: Long, block: suspend () -> List<Base>): AsyncOperationResult = also {
        require(timeoutMs > 0) { "timeoutMs must be positive" }
        registerNode(key, TaskNode.Independent(key) {
            withTimeout(timeoutMs) { block() }
        })
    }

    // ── Dependent task with timeout ───────────────────────────────────────────

    fun addAfterWithTimeout(
        key: String,
        vararg deps: String,
        timeoutMs: Long,
        block: suspend (Map<String, List<Base>>) -> Base
    ): AsyncOperationResult = also {
        require(timeoutMs > 0) { "timeoutMs must be positive" }
        val depList = deps.toList()
        requireKeysExist(depList)
        requireNoCycle(key, depList)
        registerNode(key, TaskNode.Dependent(key, depList) { depResults ->
            withTimeout(timeoutMs) { listOf(block(depResults)) }
        })
    }

    fun addListAfterWithTimeout(
        key: String,
        vararg deps: String,
        timeoutMs: Long,
        block: suspend (Map<String, List<Base>>) -> List<Base>
    ): AsyncOperationResult = also {
        require(timeoutMs > 0) { "timeoutMs must be positive" }
        val depList = deps.toList()
        requireKeysExist(depList)
        requireNoCycle(key, depList)
        registerNode(key, TaskNode.Dependent(key, depList) { depResults ->
            withTimeout(timeoutMs) { block(depResults) }
        })
    }

    // ── Type-safe single-dependency overloads for timeout ────────────────────

    fun <T : Base> addAfterWithTimeout(
        key: String,
        dep: String,
        type: KClass<T>,
        timeoutMs: Long,
        block: suspend (T) -> Base
    ): AsyncOperationResult = addAfterWithTimeout(key, dep, timeoutMs = timeoutMs) { deps ->
        val value = deps[dep]!!.filterIsInstance(type.java).first()
        block(value)
    }

    fun <T : Base> addListAfterWithTimeout(
        key: String,
        dep: String,
        type: KClass<T>,
        timeoutMs: Long,
        block: suspend (T) -> List<Base>
    ): AsyncOperationResult = addListAfterWithTimeout(key, dep, timeoutMs = timeoutMs) { deps ->
        val value = deps[dep]!!.filterIsInstance(type.java).first()
        block(value)
    }

    fun <T : Base> addAfterAllWithTimeout(
        key: String,
        dep: String,
        type: KClass<T>,
        timeoutMs: Long,
        block: suspend (List<T>) -> Base
    ): AsyncOperationResult = addAfterWithTimeout(key, dep, timeoutMs = timeoutMs) { deps ->
        val values = deps[dep]!!.filterIsInstance(type.java)
        block(values)
    }

    fun <T : Base> addListAfterAllWithTimeout(
        key: String,
        dep: String,
        type: KClass<T>,
        timeoutMs: Long,
        block: suspend (List<T>) -> List<Base>
    ): AsyncOperationResult = addListAfterWithTimeout(key, dep, timeoutMs = timeoutMs) { deps ->
        val values = deps[dep]!!.filterIsInstance(type.java)
        block(values)
    }

    // ── Dependent task with retry ─────────────────────────────────────────────

    fun addAfterWithRetry(
        key: String,
        vararg deps: String,
        maxAttempts: Int = 3,
        initialDelayMs: Long = 500,
        retryOn: (Exception) -> Boolean = { true },
        block: suspend (Map<String, List<Base>>) -> Base
    ): AsyncOperationResult {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
        require(initialDelayMs >= 0) { "initialDelayMs must be non-negative" }
        val depList = deps.toList()
        requireKeysExist(depList)
        requireNoCycle(key, depList)
        return also {
            registerNode(key, TaskNode.Dependent(key, depList) { depResults ->
                listOf(suspendExecuteWithRetry(maxAttempts, initialDelayMs, retryOn) { block(depResults) })
            })
        }
    }

    fun addListAfterWithRetry(
        key: String,
        vararg deps: String,
        maxAttempts: Int = 3,
        initialDelayMs: Long = 500,
        retryOn: (Exception) -> Boolean = { true },
        block: suspend (Map<String, List<Base>>) -> List<Base>
    ): AsyncOperationResult {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
        require(initialDelayMs >= 0) { "initialDelayMs must be non-negative" }
        val depList = deps.toList()
        requireKeysExist(depList)
        requireNoCycle(key, depList)
        return also {
            registerNode(key, TaskNode.Dependent(key, depList) { depResults ->
                suspendExecuteWithRetry(maxAttempts, initialDelayMs, retryOn) { block(depResults) }
            })
        }
    }

    // ── Type-safe single-dependency overloads for retry ──────────────────────

    fun <T : Base> addAfterWithRetry(
        key: String,
        dep: String,
        type: KClass<T>,
        maxAttempts: Int = 3,
        initialDelayMs: Long = 500,
        retryOn: (Exception) -> Boolean = { true },
        block: suspend (T) -> Base
    ): AsyncOperationResult = addAfterWithRetry(
        key, dep,
        maxAttempts = maxAttempts,
        initialDelayMs = initialDelayMs,
        retryOn = retryOn
    ) { deps ->
        val value = deps[dep]!!.filterIsInstance(type.java).first()
        block(value)
    }

    fun <T : Base> addListAfterWithRetry(
        key: String,
        dep: String,
        type: KClass<T>,
        maxAttempts: Int = 3,
        initialDelayMs: Long = 500,
        retryOn: (Exception) -> Boolean = { true },
        block: suspend (T) -> List<Base>
    ): AsyncOperationResult = addListAfterWithRetry(
        key, dep,
        maxAttempts = maxAttempts,
        initialDelayMs = initialDelayMs,
        retryOn = retryOn
    ) { deps ->
        val value = deps[dep]!!.filterIsInstance(type.java).first()
        block(value)
    }

    fun <T : Base> addAfterAllWithRetry(
        key: String,
        dep: String,
        type: KClass<T>,
        maxAttempts: Int = 3,
        initialDelayMs: Long = 500,
        retryOn: (Exception) -> Boolean = { true },
        block: suspend (List<T>) -> Base
    ): AsyncOperationResult = addAfterWithRetry(
        key, dep,
        maxAttempts = maxAttempts,
        initialDelayMs = initialDelayMs,
        retryOn = retryOn
    ) { deps ->
        val values = deps[dep]!!.filterIsInstance(type.java)
        block(values)
    }

    fun <T : Base> addListAfterAllWithRetry(
        key: String,
        dep: String,
        type: KClass<T>,
        maxAttempts: Int = 3,
        initialDelayMs: Long = 500,
        retryOn: (Exception) -> Boolean = { true },
        block: suspend (List<T>) -> List<Base>
    ): AsyncOperationResult = addListAfterWithRetry(
        key, dep,
        maxAttempts = maxAttempts,
        initialDelayMs = initialDelayMs,
        retryOn = retryOn
    ) { deps ->
        val values = deps[dep]!!.filterIsInstance(type.java)
        block(values)
    }

    // ── Independent task with fallback value ─────────────────────────────────

    fun <R : Base> addWithDefault(key: String, default: R, block: suspend () -> R): AsyncOperationResult = also {
        registerNode(key, TaskNode.Independent(key) {
            try {
                listOf(block())
            } catch (e: BaseServerResponseException) {
                logger.warn("FHIRMason.async | task='{}' | using default: {}", key, e.message)
                listOf(default)
            } catch (e: Exception) {
                logger.warn("FHIRMason.async | task='{}' | using default: {}", key, e.message)
                listOf(default)
            }
        })
    }

    fun <R : Base> addListWithDefault(key: String, default: List<R>, block: suspend () -> List<R>): AsyncOperationResult = also {
        registerNode(key, TaskNode.Independent(key) {
            try {
                block()
            } catch (e: BaseServerResponseException) {
                logger.warn("FHIRMason.async | task='{}' | using default list: {}", key, e.message)
                default
            } catch (e: Exception) {
                logger.warn("FHIRMason.async | task='{}' | using default list: {}", key, e.message)
                default
            }
        })
    }

    // ── Conditional task registration ────────────────────────────────────────

    fun addIf(condition: Boolean, key: String, block: suspend () -> Base): AsyncOperationResult =
        if (condition) add(key, block) else this

    fun addListIf(condition: Boolean, key: String, block: suspend () -> List<Base>): AsyncOperationResult =
        if (condition) addList(key, block) else this

    // ── DAG composition ──────────────────────────────────────────────────────

    fun merge(other: AsyncOperationResult): AsyncOperationResult = also {
        other.nodes.values.forEach { node -> registerNode(node.key, node) }
    }

    fun merge(block: () -> AsyncOperationResult): AsyncOperationResult = merge(block())

    // ── DAG inspection ───────────────────────────────────────────────────────

    fun describe(): String {
        if (nodes.isEmpty()) return "AsyncOperationResult DAG: (empty)"

        val tierOf = mutableMapOf<String, Int>()
        fun computeTier(key: String): Int = tierOf.getOrPut(key) {
            val node = nodes[key]!!
            if (node.deps.isEmpty()) 1
            else 1 + node.deps.maxOf { computeTier(it) }
        }
        nodes.keys.forEach { computeTier(it) }

        val byTier: Map<Int, List<TaskNode>> = nodes.values
            .groupBy { tierOf[it.key]!! }
            .toSortedMap()

        val sb = StringBuilder("AsyncOperationResult DAG:\n")
        byTier.forEach { (tier, tasks) ->
            val keysStr = tasks.joinToString(", ") { it.key }
            val allDeps = tasks.flatMap { it.deps }.distinct()
            sb.append("  Tier $tier (parallel): [$keysStr]")
            if (allDeps.isNotEmpty()) {
                sb.append(" → depends on [${allDeps.joinToString(", ")}]")
            }
            sb.append("\n")
        }
        return sb.toString().trimEnd()
    }

    suspend fun run(): OperationResult<Base> {
        val ctx = executor?.asCoroutineDispatcher()
        val execute: suspend () -> OperationResult<Base> = {
            val t = dagTimeoutMs
            if (t != null) {
                try {
                    withTimeout(t) { runInternal() }
                } catch (e: TimeoutCancellationException) {
                    OperationResult.fromMap(emptyMap(), listOf(dagTimeoutOutcome(t)), emptyMap())
                }
            } else {
                runInternal()
            }
        }
        return if (ctx != null) withContext(ctx) { execute() } else execute()
    }

    private suspend fun runInternal(): OperationResult<Base> = coroutineScope {
        val resolved = mutableMapOf<String, Deferred<List<Base>?>>()
        val failedTasks = ConcurrentHashMap<String, OperationOutcome>()

        fun launchNode(node: TaskNode): Deferred<List<Base>?> =
            resolved.getOrPut(node.key) {
                async {
                    logger.debug("FHIRMason.async | task='{}' | status=STARTED", node.key)

                    val depResults = mutableMapOf<String, List<Base>>()
                    var failedDep: String? = null
                    for (depKey in node.deps) {
                        val depResult = launchNode(nodes[depKey]!!).await()
                        if (depResult == null) { failedDep = depKey; break }
                        depResults[depKey] = depResult
                    }

                    var taskResult: List<Base>? = null
                    val taskDuration = measureTime {
                        taskResult = if (failedDep != null) {
                            failedTasks[node.key] = dependencyFailureOutcome(node.key, failedDep)
                            null
                        } else {
                            try {
                                node.block(depResults)
                            } catch (e: BaseServerResponseException) {
                                failedTasks[node.key] = e.toOperationOutcome()
                                null
                            } catch (e: Exception) {
                                failedTasks[node.key] = e.toOperationOutcome()
                                null
                            }
                        }
                    }

                    val durationMs = taskDuration.inWholeMilliseconds
                    val success = taskResult != null
                    val resourceType = taskResult?.firstOrNull()?.fhirType() ?: ""

                    if (success) {
                        logger.debug(
                            "FHIRMason.async | task='{}' | status=COMPLETED | duration={}ms",
                            node.key, durationMs
                        )
                    } else {
                        logger.debug(
                            "FHIRMason.async | task='{}' | status=FAILED | duration={}ms",
                            node.key, durationMs
                        )
                    }

                    if (timingEnabled) {
                        taskMetrics[node.key] = StepMetrics(node.key, resourceType, durationMs, success)
                    }

                    taskResult
                }
            }

        val accumulator = mutableMapOf<String, MutableList<Base>>()
        val dagDuration = measureTime {
            nodes.values.forEach { launchNode(it) }
            resolved.forEach { (key, deferred) ->
                val taskResult = deferred.await()
                if (taskResult != null) {
                    accumulator.getOrPut(key) { mutableListOf() }.addAll(taskResult)
                }
            }
        }

        totalDurationMs = dagDuration.inWholeMilliseconds
        logger.debug(
            "FHIRMason.async | dag=COMPLETED | totalDuration={}ms | tasks={}",
            totalDurationMs, nodes.size
        )

        val outcomes = failedTasks.values.toList()
        OperationResult.fromMap(accumulator, outcomes, failedTasks)
    }

    fun runBlocking(): OperationResult<Base> = runBlocking { run() }

    private fun dagTimeoutOutcome(durationMs: Long): OperationOutcome = OperationOutcome().apply {
        addIssue().apply {
            severity = OperationOutcome.IssueSeverity.ERROR
            code = OperationOutcome.IssueType.TIMEOUT
            diagnostics = "DAG execution exceeded timeout of ${durationMs}ms"
        }
    }

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
            return node.deps.any { reachable(it, target, visited) }
        }
        deps.forEach { dep ->
            require(!reachable(dep, newKey, mutableSetOf())) {
                "Adding '$newKey' with dependency '$dep' would create a cycle."
            }
        }
    }
}
