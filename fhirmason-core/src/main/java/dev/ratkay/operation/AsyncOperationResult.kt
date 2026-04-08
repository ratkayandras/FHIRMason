package dev.ratkay.operation

import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.hl7.fhir.r4.model.Base
import org.hl7.fhir.r4.model.OperationOutcome
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

class AsyncOperationResult {

    private val logger = LoggerFactory.getLogger(AsyncOperationResult::class.java)

    private var timingEnabled: Boolean = false

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

    // ── Independent task with retry ──────────────────────────────────────────

    /**
     * Registers an independent DAG task that calls [block] up to [maxAttempts] times,
     * retrying on transient failures with exponential backoff using [kotlinx.coroutines.delay].
     *
     * The backoff schedule (no delay before the first attempt):
     * ```
     * Before attempt 2 → delay initialDelayMs
     * Before attempt 3 → delay initialDelayMs × 2
     * Before attempt k → delay initialDelayMs × 2^(k-2)
     * ```
     *
     * On each failure [retryOn] is consulted — if it returns `false` the failure is recorded
     * immediately without further retries. If all [maxAttempts] are exhausted, the last
     * exception propagates and the task is recorded as failed (identical to [add]).
     *
     * @param maxAttempts total number of attempts including the first; must be ≥ 1
     * @param initialDelayMs delay duration before the second attempt in milliseconds; must be ≥ 0
     * @param retryOn predicate called with each exception — return `false` to stop retrying
     * @param block the suspending lambda to invoke; called up to [maxAttempts] times
     */
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
                var lastException: Exception? = null
                var delayMs = initialDelayMs
                var result: List<Base>? = null
                for (attempt in 1..maxAttempts) {
                    try {
                        result = listOf(block())
                        break
                    } catch (e: Exception) {
                        lastException = e
                        if (!retryOn(e) || attempt == maxAttempts) break
                        delay(delayMs)
                        delayMs *= 2
                    }
                }
                result ?: throw lastException!!
            })
        }
    }

    // ── DAG composition ──────────────────────────────────────────────────────

    /**
     * Merges all task nodes from [other] into this DAG and returns `this`.
     *
     * The two DAGs must be completely independent: nodes in [other] may only depend on
     * other nodes registered in [other], not on nodes already registered in this DAG.
     * After the merge, any task added via [addAfter] or [addListAfter] may reference
     * keys from either the original outer DAG or the merged inner DAG:
     * ```kotlin
     * AsyncOperationResult()
     *     .add("patient") { fetchPatient() }
     *     .merge {
     *         AsyncOperationResult()
     *             .add("coverage") { fetchCoverage() }
     *             .addAfter("claim", "coverage", Coverage::class) { cov -> buildClaim(cov) }
     *     }
     *     .addAfter("summary", "patient", "claim") { deps -> combine(deps) }
     *     .run()
     * ```
     *
     * Duplicate keys between DAGs cause [IllegalArgumentException] — task keys are
     * identities, not values; two tasks cannot share the same name in a single DAG.
     * The [other] instance's [timed] setting is ignored; the outer DAG's applies uniformly
     * to all tasks after the merge.
     *
     * @throws IllegalArgumentException if any key from [other] is already registered in this DAG
     */
    fun merge(other: AsyncOperationResult): AsyncOperationResult = also {
        other.nodes.values.forEach { node -> registerNode(node.key, node) }
    }

    /**
     * Calls [block] to build an inner [AsyncOperationResult] and merges all of its task nodes
     * into this DAG. Returns `this`. See [merge] for the full contract.
     *
     * @throws IllegalArgumentException if any key produced by [block] is already registered in this DAG
     */
    fun merge(block: () -> AsyncOperationResult): AsyncOperationResult = merge(block())

    // ── DAG inspection ───────────────────────────────────────────────────────

    /**
     * Returns a human-readable multi-line string describing the registered DAG structure —
     * tasks grouped into execution tiers, with each tier's dependency set listed.
     *
     * Tasks within the same tier have no ordering constraint between them and can run in
     * parallel. Tier assignment is computed as:
     * ```
     *   tier(task) = 1                              // no dependencies
     *   tier(task) = 1 + max(tier(dep) for each dep) // has dependencies
     * ```
     *
     * Example:
     * ```
     * AsyncOperationResult DAG:
     *   Tier 1 (parallel): [patient, coverage]
     *   Tier 2 (parallel): [appointment] → depends on [patient]
     *   Tier 3 (parallel): [claim] → depends on [appointment, coverage]
     * ```
     *
     * Returns `"AsyncOperationResult DAG: (empty)"` when no tasks have been registered.
     */
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

    suspend fun run(): OperationResult<Base> = coroutineScope {
        val dagStart = System.currentTimeMillis()
        val resolved = mutableMapOf<String, Deferred<List<Base>?>>()
        val failedTasks = ConcurrentHashMap<String, OperationOutcome>()

        fun launchNode(node: TaskNode): Deferred<List<Base>?> =
            resolved.getOrPut(node.key) {
                async {
                    logger.debug("FHIRMason.async | task='{}' | status=STARTED", node.key)
                    val taskStart = System.currentTimeMillis()

                    val depResults = mutableMapOf<String, List<Base>>()
                    var failedDep: String? = null
                    for (depKey in node.deps) {
                        val depResult = launchNode(nodes[depKey]!!).await()
                        if (depResult == null) { failedDep = depKey; break }
                        depResults[depKey] = depResult
                    }

                    val taskResult: List<Base>? = if (failedDep != null) {
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

                    val durationMs = System.currentTimeMillis() - taskStart
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

        nodes.values.forEach { launchNode(it) }

        val accumulator = mutableMapOf<String, MutableList<Base>>()
        resolved.forEach { (key, deferred) ->
            val taskResult = deferred.await()
            if (taskResult != null) {
                accumulator.getOrPut(key) { mutableListOf() }.addAll(taskResult)
            }
        }

        totalDurationMs = System.currentTimeMillis() - dagStart
        logger.debug(
            "FHIRMason.async | dag=COMPLETED | totalDuration={}ms | tasks={}",
            totalDurationMs, nodes.size
        )

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
            return node.deps.any { reachable(it, target, visited) }
        }
        deps.forEach { dep ->
            require(!reachable(dep, newKey, mutableSetOf())) {
                "Adding '$newKey' with dependency '$dep' would create a cycle."
            }
        }
    }
}
