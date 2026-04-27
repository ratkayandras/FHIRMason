package dev.ratkay.operation.r4

import dev.ratkay.operation.ErrorStrategy
import dev.ratkay.operation.StepMetrics
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executor
import org.hl7.fhir.r4.model.Base
import org.hl7.fhir.r4.model.OperationOutcome
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.time.measureTime
import kotlin.time.measureTimedValue

/**
 * Builds and executes a DAG of asynchronous FHIR tasks, collecting results into an
 * [OperationResult].
 *
 * Tasks are registered before execution:
 * - Independent tasks ([add], [addList], [addWithRetry], etc.) have no dependencies and run
 *   in parallel immediately when [run] is called.
 * - Dependent tasks ([addAfter], [addListAfter], etc.) declare their dependencies by key and
 *   start only after all named dependencies resolve successfully.
 *
 * Execution is started by calling [run] (suspending) or [runBlocking] (blocking). The returned
 * [OperationResult] accumulates all successful task results; failed tasks add an ERROR-severity
 * [OperationOutcome].
 *
 * ### Registration order
 * Dependencies must be registered before the tasks that depend on them — [addAfter] validates
 * at registration time and throws [IllegalArgumentException] for unknown keys or cycles.
 *
 * ### Configuration
 * - [timed] — enable per-task metrics
 * - [timeout] — set a DAG-level wall-clock timeout
 * - [withExecutor] — override the default [kotlinx.coroutines.Dispatchers.IO] thread pool
 */
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
     * When not called, [run] defaults to [kotlinx.coroutines.Dispatchers.IO], which keeps
     * up to 64 threads available for blocking HAPI FHIR client calls so independent tasks
     * execute in parallel regardless of how [run] or [runBlocking] is invoked.
     *
     * Pass a custom executor to override the thread pool — for example, use
     * [java.util.concurrent.Executors.newFixedThreadPool] for a bounded pool, or pass
     * [kotlinx.coroutines.Dispatchers.IO] explicitly to make the choice visible at the call site.
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

    /**
     * Registers an independent DAG task that produces a single [Base] resource stored under [key].
     *
     * [block] is invoked with no arguments when [run] is called. If it throws, the key is absent
     * from the final [OperationResult] and an ERROR-severity [OperationOutcome] is recorded.
     * Downstream tasks that depend on this key are skipped.
     */
    fun add(key: String, block: suspend () -> Base): AsyncOperationResult = also {
        registerNode(key, TaskNode.Independent(key) { listOf(block()) })
    }

    /**
     * Registers an independent DAG task that produces a collection of resources stored under [key].
     * Each element is added to the list under the same key. See [add] for error semantics.
     *
     * Accepts any [Collection] (e.g. [List], [Set], [LinkedHashSet]); elements are stored as a
     * [List] internally.
     */
    fun <R : Base> addList(key: String, block: suspend () -> Collection<R>): AsyncOperationResult = also {
        registerNode(key, TaskNode.Independent(key) { block().toList() })
    }

    /**
     * Registers a dependent DAG task that produces a single [Base] resource stored under [key].
     *
     * [block] is invoked only after all [deps] have resolved successfully. It receives a map
     * keyed by dependency name, where each entry holds the list of resources produced by that
     * dependency. If any dependency fails, this task is skipped without calling [block].
     *
     * All [deps] must already be registered; duplicate keys and cycles cause
     * [IllegalArgumentException] at registration time.
     *
     * @param key storage key for the result
     * @param deps keys of previously registered tasks whose results are passed to [block]
     * @param block the suspending lambda to invoke; receives resolved dependency results
     */
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

    /**
     * Like [addAfter] but [block] returns a `Collection<R>`. All elements are stored under [key].
     *
     * Accepts any [Collection] return (e.g. [List], [Set], [LinkedHashSet]); elements are stored
     * as a [List] internally.
     *
     * @param key storage key for the result list
     * @param deps keys of previously registered tasks whose results are passed to [block]
     * @param block the suspending lambda to invoke; receives resolved dependency results
     */
    fun <R : Base> addListAfter(
        key: String,
        vararg deps: String,
        block: suspend (Map<String, List<Base>>) -> Collection<R>
    ): AsyncOperationResult = also {
        val depList = deps.toList()
        requireKeysExist(depList)
        requireNoCycle(key, depList)
        registerNode(key, TaskNode.Dependent(key, depList) { map -> block(map).toList() })
    }

    // Type-safe single-dependency convenience methods

    /**
     * Type-safe overload of [addAfter] for a single dependency.
     *
     * Extracts the first value stored under [dep] that is an instance of [type] and passes it
     * directly to [block], eliminating manual map lookup and casting.
     *
     * @param key storage key for the result
     * @param dep the single dependency key
     * @param type the expected type of the dependency value
     * @param block the suspending lambda; receives the typed dependency value
     */
    fun <T : Base> addAfter(
        key: String,
        dep: String,
        type: KClass<T>,
        block: suspend (T) -> Base
    ): AsyncOperationResult = addAfter(key, dep) { deps ->
        val value = deps[dep]!!.filterIsInstance(type.java).first()
        block(value)
    }

    /**
     * Type-safe overload of [addListAfter] for a single dependency.
     *
     * Extracts the first value stored under [dep] that is an instance of [type] and passes it
     * directly to [block]. See [addAfter] (typed overload) for the full contract.
     *
     * Accepts any [Collection] return from [block]; elements are stored as a [List] internally.
     *
     * @param key storage key for the result list
     * @param dep the single dependency key
     * @param type the expected type of the dependency value
     * @param block the suspending lambda; receives the typed dependency value
     */
    fun <T : Base, R : Base> addListAfter(
        key: String,
        dep: String,
        type: KClass<T>,
        block: suspend (T) -> Collection<R>
    ): AsyncOperationResult = addListAfter(key, dep) { deps ->
        val value = deps[dep]!!.filterIsInstance(type.java).first()
        block(value)
    }

    // Type-safe list-injection convenience methods

    /**
     * Type-safe overload of [addAfter] for a single dependency where [block] receives **all**
     * values stored under [dep] that are instances of [type], as a typed list.
     *
     * Use [addAfter] (typed overload) when you want only the first matching value.
     *
     * @param key storage key for the result
     * @param dep the single dependency key
     * @param type the expected type of the dependency values
     * @param block the suspending lambda; receives the typed dependency list
     */
    fun <T : Base> addAfterAll(
        key: String,
        dep: String,
        type: KClass<T>,
        block: suspend (List<T>) -> Base
    ): AsyncOperationResult = addAfter(key, dep) { deps ->
        val values = deps[dep]!!.filterIsInstance(type.java)
        block(values)
    }

    /**
     * Like [addAfterAll] but [block] returns a `Collection<R>`.
     *
     * Accepts any [Collection] return (e.g. [List], [Set], [LinkedHashSet]); elements are stored
     * as a [List] internally.
     *
     * @param key storage key for the result list
     * @param dep the single dependency key
     * @param type the expected type of the dependency values
     * @param block the suspending lambda; receives the typed dependency list
     */
    fun <T : Base, R : Base> addListAfterAll(
        key: String,
        dep: String,
        type: KClass<T>,
        block: suspend (List<T>) -> Collection<R>
    ): AsyncOperationResult = addListAfter(key, dep) { deps ->
        val values = deps[dep]!!.filterIsInstance(type.java)
        block(values)
    }

    // ── Retry helper ─────────────────────────────────────────────────────────

    /**
     * Suspending analogue of `OperationResult.executeWithRetry`: calls [block] up to
     * [maxAttempts] times with exponential backoff via [delay] between attempts.
     * Returns the first successful result, or throws the last caught exception.
     */
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
                listOf(suspendExecuteWithRetry(maxAttempts, initialDelayMs, retryOn) { block() })
            })
        }
    }

    // ── Independent task with timeout ────────────────────────────────────────

    /**
     * Registers an independent DAG task that must complete within [timeoutMs] milliseconds.
     *
     * If [block] does not complete in time, [kotlinx.coroutines.withTimeout] throws
     * [kotlinx.coroutines.TimeoutCancellationException], which is caught by the DAG execution
     * engine as a failed task: the key is absent from the final [OperationResult] and a
     * [org.hl7.fhir.r4.model.OperationOutcome] carrying the timeout diagnostics is recorded.
     * Downstream tasks that depend on this key are skipped.
     *
     * @param key storage key for the result
     * @param timeoutMs maximum allowed execution time in milliseconds; must be > 0
     * @param block the suspending lambda to execute within the timeout
     */
    fun addWithTimeout(key: String, timeoutMs: Long, block: suspend () -> Base): AsyncOperationResult = also {
        require(timeoutMs > 0) { "timeoutMs must be positive" }
        registerNode(key, TaskNode.Independent(key) {
            withTimeout(timeoutMs) { listOf(block()) }
        })
    }

    /**
     * Registers an independent list-producing DAG task that must complete within [timeoutMs]
     * milliseconds. See [addWithTimeout] for the full contract.
     *
     * @param key storage key for the result list
     * @param timeoutMs maximum allowed execution time in milliseconds; must be > 0
     * @param block the suspending lambda to execute within the timeout
     */
    fun <R : Base> addListWithTimeout(key: String, timeoutMs: Long, block: suspend () -> Collection<R>): AsyncOperationResult = also {
        require(timeoutMs > 0) { "timeoutMs must be positive" }
        registerNode(key, TaskNode.Independent(key) {
            withTimeout(timeoutMs) { block() }.toList()
        })
    }

    // ── Dependent task with timeout ───────────────────────────────────────────

    /**
     * Registers a dependent DAG task that must complete within [timeoutMs] milliseconds.
     * Dependency results are passed to [block] as a [Map] keyed by dependency name.
     *
     * The timeout window begins after all dependencies have resolved. If any dependency
     * fails the task is skipped without starting the timeout clock.
     * See [addWithTimeout] for timeout failure semantics.
     *
     * @param key storage key for the result
     * @param deps dependency keys whose results are passed to [block]
     * @param timeoutMs maximum allowed execution time in milliseconds; must be > 0
     * @param block the suspending lambda to execute within the timeout
     */
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

    /**
     * Registers a dependent list-producing DAG task that must complete within [timeoutMs]
     * milliseconds. See [addAfterWithTimeout] for the full contract.
     *
     * @param key storage key for the result list
     * @param deps dependency keys whose results are passed to [block]
     * @param timeoutMs maximum allowed execution time in milliseconds; must be > 0
     * @param block the suspending lambda to execute within the timeout
     */
    fun <R : Base> addListAfterWithTimeout(
        key: String,
        vararg deps: String,
        timeoutMs: Long,
        block: suspend (Map<String, List<Base>>) -> Collection<R>
    ): AsyncOperationResult = also {
        require(timeoutMs > 0) { "timeoutMs must be positive" }
        val depList = deps.toList()
        requireKeysExist(depList)
        requireNoCycle(key, depList)
        registerNode(key, TaskNode.Dependent(key, depList) { depResults ->
            withTimeout(timeoutMs) { block(depResults) }.toList()
        })
    }

    // ── Type-safe single-dependency overloads for timeout ────────────────────

    /**
     * Registers a dependent DAG task with a per-task timeout where the single dependency value
     * is extracted and typed automatically, eliminating manual map lookup and casting.
     *
     * Equivalent to [addAfterWithTimeout] with a raw [Map] block, but the block receives the
     * first value stored under [dep] that is an instance of [type] instead of the raw map.
     *
     * @param key storage key for the result
     * @param dep the single dependency key
     * @param type the expected type of the dependency value
     * @param timeoutMs maximum allowed execution time in milliseconds; must be > 0
     * @param block the suspending lambda to execute within the timeout; receives the typed dep value
     */
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

    /**
     * Registers a dependent list-producing DAG task with a per-task timeout where the single
     * dependency value is extracted and typed automatically.
     * See [addAfterWithTimeout] for the full contract.
     *
     * @param key storage key for the result list
     * @param dep the single dependency key
     * @param type the expected type of the dependency value
     * @param timeoutMs maximum allowed execution time in milliseconds; must be > 0
     * @param block the suspending lambda to execute within the timeout; receives the typed dep value
     */
    fun <T : Base, R : Base> addListAfterWithTimeout(
        key: String,
        dep: String,
        type: KClass<T>,
        timeoutMs: Long,
        block: suspend (T) -> Collection<R>
    ): AsyncOperationResult = addListAfterWithTimeout(key, dep, timeoutMs = timeoutMs) { deps ->
        val value = deps[dep]!!.filterIsInstance(type.java).first()
        block(value)
    }

    /**
     * Registers a dependent DAG task with a per-task timeout where all values stored under
     * [dep] are filtered to [type] and injected as a typed list.
     * See [addAfterWithTimeout] for the full contract.
     *
     * @param key storage key for the result
     * @param dep the single dependency key
     * @param type the expected type of the dependency values
     * @param timeoutMs maximum allowed execution time in milliseconds; must be > 0
     * @param block the suspending lambda to execute within the timeout; receives the typed dep list
     */
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

    /**
     * Registers a dependent list-producing DAG task with a per-task timeout where all values
     * stored under [dep] are filtered to [type] and injected as a typed list.
     * See [addAfterWithTimeout] for the full contract.
     *
     * @param key storage key for the result list
     * @param dep the single dependency key
     * @param type the expected type of the dependency values
     * @param timeoutMs maximum allowed execution time in milliseconds; must be > 0
     * @param block the suspending lambda to execute within the timeout; receives the typed dep list
     */
    fun <T : Base, R : Base> addListAfterAllWithTimeout(
        key: String,
        dep: String,
        type: KClass<T>,
        timeoutMs: Long,
        block: suspend (List<T>) -> Collection<R>
    ): AsyncOperationResult = addListAfterWithTimeout(key, dep, timeoutMs = timeoutMs) { deps ->
        val values = deps[dep]!!.filterIsInstance(type.java)
        block(values)
    }

    // ── Dependent task with retry ─────────────────────────────────────────────

    /**
     * Registers a dependent DAG task that calls [block] up to [maxAttempts] times,
     * retrying on transient failures with exponential backoff using [kotlinx.coroutines.delay].
     * Dependency results are passed to [block] as a [Map] keyed by dependency name.
     *
     * The backoff schedule mirrors [addWithRetry] (no delay before the first attempt):
     * ```
     * Before attempt 2 → delay initialDelayMs
     * Before attempt k → delay initialDelayMs × 2^(k-2)
     * ```
     *
     * When any dependency fails the retry block never runs — the task is skipped with a
     * dependency-failure [OperationOutcome] exactly as for [addAfter].
     *
     * @param key storage key for the result
     * @param deps dependency keys whose results are passed to [block]
     * @param maxAttempts total number of attempts including the first; must be ≥ 1
     * @param initialDelayMs delay before the second attempt in milliseconds; must be ≥ 0
     * @param retryOn predicate called with each exception — return `false` to stop retrying immediately
     * @param block the suspending lambda to invoke; receives resolved dependency results
     */
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

    /**
     * Registers a dependent list-producing DAG task that retries with exponential backoff.
     * See [addAfterWithRetry] for the full contract.
     *
     * @param key storage key for the result list
     * @param deps dependency keys whose results are passed to [block]
     * @param maxAttempts total number of attempts including the first; must be ≥ 1
     * @param initialDelayMs delay before the second attempt in milliseconds; must be ≥ 0
     * @param retryOn predicate called with each exception — return `false` to stop retrying immediately
     * @param block the suspending lambda to invoke; receives resolved dependency results
     */
    @JvmOverloads
    fun <R : Base> addListAfterWithRetry(
        key: String,
        vararg deps: String,
        maxAttempts: Int = 3,
        initialDelayMs: Long = 500,
        retryOn: (Exception) -> Boolean = { true },
        block: suspend (Map<String, List<Base>>) -> Collection<R>
    ): AsyncOperationResult {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
        require(initialDelayMs >= 0) { "initialDelayMs must be non-negative" }
        val depList = deps.toList()
        requireKeysExist(depList)
        requireNoCycle(key, depList)
        return also {
            registerNode(key, TaskNode.Dependent(key, depList) { depResults ->
                suspendExecuteWithRetry(maxAttempts, initialDelayMs, retryOn) { block(depResults) }.toList()
            })
        }
    }

    // ── Type-safe single-dependency overloads for retry ──────────────────────

    /**
     * Registers a dependent DAG task with retry where the single dependency value is extracted
     * and typed automatically, eliminating manual map lookup and casting.
     *
     * Equivalent to [addAfterWithRetry] with a raw [Map] block, but the block receives the
     * first value stored under [dep] that is an instance of [type] instead of the raw map.
     * The retry loop wraps the full extraction + block call on every attempt.
     *
     * @param key storage key for the result
     * @param dep the single dependency key
     * @param type the expected type of the dependency value
     * @param maxAttempts total number of attempts including the first; must be ≥ 1
     * @param initialDelayMs delay before the second attempt in milliseconds; must be ≥ 0
     * @param retryOn predicate called with each exception — return `false` to stop retrying immediately
     * @param block the suspending lambda to invoke; receives the typed dep value
     */
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

    /**
     * Registers a dependent list-producing DAG task with retry where the single dependency
     * value is extracted and typed automatically.
     * See [addAfterWithRetry] for the full contract.
     *
     * @param key storage key for the result list
     * @param dep the single dependency key
     * @param type the expected type of the dependency value
     * @param maxAttempts total number of attempts including the first; must be ≥ 1
     * @param initialDelayMs delay before the second attempt in milliseconds; must be ≥ 0
     * @param retryOn predicate called with each exception — return `false` to stop retrying immediately
     * @param block the suspending lambda to invoke; receives the typed dep value
     */
    @JvmOverloads
    fun <T : Base, R : Base> addListAfterWithRetry(
        key: String,
        dep: String,
        type: KClass<T>,
        maxAttempts: Int = 3,
        initialDelayMs: Long = 500,
        retryOn: (Exception) -> Boolean = { true },
        block: suspend (T) -> Collection<R>
    ): AsyncOperationResult = addListAfterWithRetry(
        key, dep,
        maxAttempts = maxAttempts,
        initialDelayMs = initialDelayMs,
        retryOn = retryOn
    ) { deps ->
        val value = deps[dep]!!.filterIsInstance(type.java).first()
        block(value)
    }

    /**
     * Registers a dependent DAG task with retry where all values stored under [dep] are
     * filtered to [type] and injected as a typed list on every attempt.
     * See [addAfterWithRetry] for the full contract.
     *
     * @param key storage key for the result
     * @param dep the single dependency key
     * @param type the expected type of the dependency values
     * @param maxAttempts total number of attempts including the first; must be ≥ 1
     * @param initialDelayMs delay before the second attempt in milliseconds; must be ≥ 0
     * @param retryOn predicate called with each exception — return `false` to stop retrying immediately
     * @param block the suspending lambda to invoke; receives the typed dep list
     */
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

    /**
     * Registers a dependent list-producing DAG task with retry where all values stored under
     * [dep] are filtered to [type] and injected as a typed list on every attempt.
     * See [addAfterWithRetry] for the full contract.
     *
     * @param key storage key for the result list
     * @param dep the single dependency key
     * @param type the expected type of the dependency values
     * @param maxAttempts total number of attempts including the first; must be ≥ 1
     * @param initialDelayMs delay before the second attempt in milliseconds; must be ≥ 0
     * @param retryOn predicate called with each exception — return `false` to stop retrying immediately
     * @param block the suspending lambda to invoke; receives the typed dep list
     */
    @JvmOverloads
    fun <T : Base, R : Base> addListAfterAllWithRetry(
        key: String,
        dep: String,
        type: KClass<T>,
        maxAttempts: Int = 3,
        initialDelayMs: Long = 500,
        retryOn: (Exception) -> Boolean = { true },
        block: suspend (List<T>) -> Collection<R>
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

    /**
     * Registers an independent DAG task that stores [default] when [block] throws,
     * rather than leaving the key absent and recording a failed task.
     *
     * Unlike [add], a failure here is silent at the DAG level: the key IS present in the
     * final [OperationResult], no [OperationOutcome] is added, and downstream [addAfter]
     * tasks that depend on this key will still execute — receiving the [default] value.
     *
     * A WARN log line is emitted on fallback, mirroring sync `OperationResult.addOrDefault`.
     *
     * @param key storage key for the result
     * @param default value to store when [block] throws
     * @param block suspending lambda that produces the primary value
     */
    fun <R : Base> addWithDefault(key: String, default: R, block: suspend () -> R): AsyncOperationResult = also {
        registerNode(key, TaskNode.Independent(key) {
            try {
                listOf(block())
            } catch (e: Exception) {
                logger.warn("FHIRMason.async | task='{}' | using default: {}", key, e.message)
                listOf(default)
            }
        })
    }

    /**
     * Registers an independent list-producing DAG task that stores [default] when [block] throws.
     * See [addWithDefault] for the full contract.
     *
     * @param key storage key for the result list
     * @param default list to store when [block] throws
     * @param block suspending lambda that produces the primary list
     */
    fun <R : Base> addListWithDefault(key: String, default: Collection<R>, block: suspend () -> Collection<R>): AsyncOperationResult = also {
        val defaultList = default.toList()
        registerNode(key, TaskNode.Independent(key) {
            try {
                block().toList()
            } catch (e: Exception) {
                logger.warn("FHIRMason.async | task='{}' | using default list: {}", key, e.message)
                defaultList
            }
        })
    }

    // ── Conditional task registration ────────────────────────────────────────

    /**
     * Registers an independent DAG task via [add] only when [condition] is `true`.
     * When [condition] is `false`, returns `this` unchanged — no node is registered.
     *
     * This is an ergonomic alternative to:
     * ```kotlin
     * val dag = AsyncOperationResult().add("patient") { fetchPatient() }
     * if (includeAppointments) dag.add("appointment") { fetchAppointment() }
     * ```
     * which can be written as:
     * ```kotlin
     * AsyncOperationResult()
     *     .add("patient") { fetchPatient() }
     *     .addIf(includeAppointments, "appointment") { fetchAppointment() }
     * ```
     *
     * Note: a key skipped by `addIf(false, ...)` is never registered and cannot be referenced
     * as a dependency in [addAfter] or [addListAfter] — [requireKeysExist] will throw.
     */
    fun addIf(condition: Boolean, key: String, block: suspend () -> Base): AsyncOperationResult =
        if (condition) add(key, block) else this

    /**
     * Registers an independent list-producing DAG task via [addList] only when [condition] is `true`.
     * When [condition] is `false`, returns `this` unchanged. See [addIf] for full contract.
     */
    fun <R : Base> addListIf(condition: Boolean, key: String, block: suspend () -> Collection<R>): AsyncOperationResult =
        if (condition) addList(key, block) else this

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

    /**
     * Executes all registered tasks as a coroutine DAG and returns the accumulated
     * [OperationResult]. Independent tasks run in parallel; dependent tasks wait for their
     * dependencies via [kotlinx.coroutines.Deferred.await].
     *
     * If a DAG-level timeout was configured via [timeout], the execution is wrapped in
     * [kotlinx.coroutines.withTimeout]. On timeout, an [OperationResult] containing a single
     * `TIMEOUT`-coded [OperationOutcome] is returned with no task results.
     */
    suspend fun run(): OperationResult<Base> {
        val ctx = executor?.asCoroutineDispatcher() ?: Dispatchers.IO
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
        return withContext(ctx) { execute() }
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

    /**
     * Blocking wrapper around [run] — calls [run] inside [kotlinx.coroutines.runBlocking].
     *
     * Inherits the DAG-level timeout configured via [timeout], if set.
     * Prefer [run] from a coroutine context; use this method only from non-suspending call sites.
     */
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
