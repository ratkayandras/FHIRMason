package dev.ratkay.operation.dstu3

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
import java.util.concurrent.Callable
import java.util.concurrent.Executor
import java.util.function.Function
import java.util.function.Predicate
import org.hl7.fhir.dstu3.model.Base
import org.hl7.fhir.dstu3.model.OperationOutcome
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.measureTime
import kotlin.time.measureTimedValue

/**
 * Builds and executes a DAG of asynchronous FHIR tasks, collecting results into an
 * [OperationResult].
 *
 * Tasks are registered before execution:
 * - Independent tasks ([add], [addList], [addWithRetry], etc.) have no dependencies and run
 *   in parallel immediately when [execute] is called.
 * - Dependent tasks ([addAfter], [addListAfter], etc.) declare their dependencies by key and
 *   start only after all named dependencies resolve successfully.
 *
 * Execution is started by calling [execute] (suspending) or [executeBlocking] (blocking). The returned
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
     * If [execute] does not complete within [durationMs] milliseconds, it returns an
     * [OperationResult] containing a single `TIMEOUT`-coded [OperationOutcome] and no task
     * results. [getTotalDuration] will return `0` on timeout because the duration assignment
     * inside the DAG execution is interrupted before it can run.
     *
     * [executeBlocking] inherits this timeout automatically.
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
     * When not called, [execute] defaults to [kotlinx.coroutines.Dispatchers.IO], which keeps
     * up to 64 threads available for blocking HAPI FHIR client calls so independent tasks
     * execute in parallel regardless of how [execute] or [executeBlocking] is invoked.
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

    /** Returns a snapshot of [StepMetrics] collected per task after [execute] or [executeBlocking]. */
    fun getMetrics(): Map<String, StepMetrics> = taskMetrics.toMap()

    /** Returns total wall-clock DAG execution time in milliseconds. Zero before [execute] completes. */
    fun getTotalDuration(): Long = totalDurationMs

    private fun registerNode(key: String, node: TaskNode) {
        require(!nodes.containsKey(key)) { "Duplicate task key: '$key'" }
        nodes[key] = node
    }

    /**
     * Registers an independent DAG task that produces a single [Base] resource stored under [key].
     *
     * [block] is invoked with no arguments when [execute] is called. If it throws, the key is absent
     * from the final [OperationResult] and an ERROR-severity [OperationOutcome] is recorded.
     * Downstream tasks that depend on this key are skipped.
     */
    fun add(key: String, block: suspend () -> Base): AsyncOperationResult = also {
        registerNode(key, TaskNode.Independent(key) { listOf(block()) })
    }

    /** Java-friendly [Callable] overload of [add]. */
    fun add(key: String, block: Callable<out Base>): AsyncOperationResult =
        add(key) { block.call() }

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

    /** Java-friendly [Callable] overload of [addList]. */
    fun <R : Base> addList(key: String, block: Callable<out Collection<out R>>): AsyncOperationResult =
        addList(key) { block.call() }

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

    /** Java-friendly [Function] overload of [addAfter]. */
    fun addAfter(
        key: String,
        vararg deps: String,
        block: Function<Map<String, List<Base>>, out Base>
    ): AsyncOperationResult =
        addAfter(key, *deps) { map -> block.apply(map) }

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

    /** Java-friendly [Function] overload of [addListAfter]. */
    fun <R : Base> addListAfter(
        key: String,
        vararg deps: String,
        block: Function<Map<String, List<Base>>, out Collection<out R>>
    ): AsyncOperationResult =
        addListAfter(key, *deps) { map -> block.apply(map) }

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
        type: Class<T>,
        block: suspend (T) -> Base
    ): AsyncOperationResult = addAfter(key, dep) { deps ->
        val value = deps[dep]!!.filterIsInstance(type).firstOrNull()
            ?: error("No value of type ${type.simpleName} found under dependency '$dep'")
        block(value)
    }

    /** Java-friendly [Function] overload of [addAfter] (typed). */
    fun <T : Base> addAfter(
        key: String, dep: String, type: Class<T>,
        block: Function<T, out Base>
    ): AsyncOperationResult =
        addAfter(key, dep, type) { block.apply(it) }

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
        type: Class<T>,
        block: suspend (T) -> Collection<R>
    ): AsyncOperationResult = addListAfter(key, dep) { deps ->
        val value = deps[dep]!!.filterIsInstance(type).firstOrNull()
            ?: error("No value of type ${type.simpleName} found under dependency '$dep'")
        block(value)
    }

    /** Java-friendly [Function] overload of [addListAfter] (typed). */
    fun <T : Base, R : Base> addListAfter(
        key: String, dep: String, type: Class<T>,
        block: Function<T, out Collection<out R>>
    ): AsyncOperationResult =
        addListAfter(key, dep, type) { block.apply(it) }

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
        type: Class<T>,
        block: suspend (List<T>) -> Base
    ): AsyncOperationResult = addAfter(key, dep) { deps ->
        val values = deps[dep]!!.filterIsInstance(type)
        block(values)
    }

    /** Java-friendly [Function] overload of [addAfterAll]. */
    fun <T : Base> addAfterAll(
        key: String, dep: String, type: Class<T>,
        block: Function<List<T>, out Base>
    ): AsyncOperationResult =
        addAfterAll(key, dep, type) { block.apply(it) }

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
        type: Class<T>,
        block: suspend (List<T>) -> Collection<R>
    ): AsyncOperationResult = addListAfter(key, dep) { deps ->
        val values = deps[dep]!!.filterIsInstance(type)
        block(values)
    }

    /** Java-friendly [Function] overload of [addListAfterAll]. */
    fun <T : Base, R : Base> addListAfterAll(
        key: String, dep: String, type: Class<T>,
        block: Function<List<T>, out Collection<out R>>
    ): AsyncOperationResult =
        addListAfterAll(key, dep, type) { block.apply(it) }

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

    /** Java-friendly [Callable]/[Predicate] overload of [addWithRetry]. */
    @JvmOverloads
    fun <R : Base> addWithRetry(
        key: String,
        maxAttempts: Int = 3,
        initialDelayMs: Long = 500,
        retryOn: Predicate<Exception> = Predicate { true },
        block: Callable<out R>
    ): AsyncOperationResult =
        addWithRetry(key, maxAttempts, initialDelayMs, retryOn::test) { block.call() }

    // ── Independent task with timeout ────────────────────────────────────────

    fun addWithTimeout(key: String, timeoutMs: Long, block: suspend () -> Base): AsyncOperationResult = also {
        require(timeoutMs > 0) { "timeoutMs must be positive" }
        registerNode(key, TaskNode.Independent(key) {
            withTimeout(timeoutMs) { listOf(block()) }
        })
    }

    /** Java-friendly [Callable] overload of [addWithTimeout]. */
    fun addWithTimeout(key: String, timeoutMs: Long, block: Callable<out Base>): AsyncOperationResult =
        addWithTimeout(key, timeoutMs) { block.call() }

    fun <R : Base> addListWithTimeout(key: String, timeoutMs: Long, block: suspend () -> Collection<R>): AsyncOperationResult = also {
        require(timeoutMs > 0) { "timeoutMs must be positive" }
        registerNode(key, TaskNode.Independent(key) {
            withTimeout(timeoutMs) { block() }.toList()
        })
    }

    /** Java-friendly [Callable] overload of [addListWithTimeout]. */
    fun <R : Base> addListWithTimeout(key: String, timeoutMs: Long, block: Callable<out Collection<out R>>): AsyncOperationResult =
        addListWithTimeout(key, timeoutMs) { block.call() }

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

    /** Java-friendly [Function] overload of [addAfterWithTimeout]. */
    fun addAfterWithTimeout(
        key: String,
        vararg deps: String,
        timeoutMs: Long,
        block: Function<Map<String, List<Base>>, out Base>
    ): AsyncOperationResult =
        addAfterWithTimeout(key, *deps, timeoutMs = timeoutMs) { map -> block.apply(map) }

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

    /** Java-friendly [Function] overload of [addListAfterWithTimeout]. */
    fun <R : Base> addListAfterWithTimeout(
        key: String,
        vararg deps: String,
        timeoutMs: Long,
        block: Function<Map<String, List<Base>>, out Collection<out R>>
    ): AsyncOperationResult =
        addListAfterWithTimeout(key, *deps, timeoutMs = timeoutMs) { map -> block.apply(map) }

    // ── Type-safe single-dependency overloads for timeout ────────────────────

    fun <T : Base> addAfterWithTimeout(
        key: String,
        dep: String,
        type: Class<T>,
        timeoutMs: Long,
        block: suspend (T) -> Base
    ): AsyncOperationResult = addAfterWithTimeout(key, dep, timeoutMs = timeoutMs) { deps ->
        val value = deps[dep]!!.filterIsInstance(type).firstOrNull()
            ?: error("No value of type ${type.simpleName} found under dependency '$dep'")
        block(value)
    }

    /** Java-friendly [Function] overload of [addAfterWithTimeout] (typed). */
    fun <T : Base> addAfterWithTimeout(
        key: String, dep: String, type: Class<T>, timeoutMs: Long,
        block: Function<T, out Base>
    ): AsyncOperationResult =
        addAfterWithTimeout(key, dep, type, timeoutMs) { block.apply(it) }

    fun <T : Base, R : Base> addListAfterWithTimeout(
        key: String,
        dep: String,
        type: Class<T>,
        timeoutMs: Long,
        block: suspend (T) -> Collection<R>
    ): AsyncOperationResult = addListAfterWithTimeout(key, dep, timeoutMs = timeoutMs) { deps ->
        val value = deps[dep]!!.filterIsInstance(type).firstOrNull()
            ?: error("No value of type ${type.simpleName} found under dependency '$dep'")
        block(value)
    }

    /** Java-friendly [Function] overload of [addListAfterWithTimeout] (typed). */
    fun <T : Base, R : Base> addListAfterWithTimeout(
        key: String, dep: String, type: Class<T>, timeoutMs: Long,
        block: Function<T, out Collection<out R>>
    ): AsyncOperationResult =
        addListAfterWithTimeout(key, dep, type, timeoutMs) { block.apply(it) }

    fun <T : Base> addAfterAllWithTimeout(
        key: String,
        dep: String,
        type: Class<T>,
        timeoutMs: Long,
        block: suspend (List<T>) -> Base
    ): AsyncOperationResult = addAfterWithTimeout(key, dep, timeoutMs = timeoutMs) { deps ->
        val values = deps[dep]!!.filterIsInstance(type)
        block(values)
    }

    /** Java-friendly [Function] overload of [addAfterAllWithTimeout]. */
    fun <T : Base> addAfterAllWithTimeout(
        key: String, dep: String, type: Class<T>, timeoutMs: Long,
        block: Function<List<T>, out Base>
    ): AsyncOperationResult =
        addAfterAllWithTimeout(key, dep, type, timeoutMs) { block.apply(it) }

    fun <T : Base, R : Base> addListAfterAllWithTimeout(
        key: String,
        dep: String,
        type: Class<T>,
        timeoutMs: Long,
        block: suspend (List<T>) -> Collection<R>
    ): AsyncOperationResult = addListAfterWithTimeout(key, dep, timeoutMs = timeoutMs) { deps ->
        val values = deps[dep]!!.filterIsInstance(type)
        block(values)
    }

    /** Java-friendly [Function] overload of [addListAfterAllWithTimeout]. */
    fun <T : Base, R : Base> addListAfterAllWithTimeout(
        key: String, dep: String, type: Class<T>, timeoutMs: Long,
        block: Function<List<T>, out Collection<out R>>
    ): AsyncOperationResult =
        addListAfterAllWithTimeout(key, dep, type, timeoutMs) { block.apply(it) }

    // ── Dependent task with retry ─────────────────────────────────────────────

    @JvmOverloads
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

    /** Java-friendly [Function]/[Predicate] overload of [addAfterWithRetry]. */
    @JvmOverloads
    fun addAfterWithRetry(
        key: String,
        vararg deps: String,
        maxAttempts: Int = 3,
        initialDelayMs: Long = 500,
        retryOn: Predicate<Exception> = Predicate { true },
        block: Function<Map<String, List<Base>>, out Base>
    ): AsyncOperationResult =
        addAfterWithRetry(key, *deps, maxAttempts = maxAttempts, initialDelayMs = initialDelayMs, retryOn = retryOn::test) { map -> block.apply(map) }

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

    /** Java-friendly [Function]/[Predicate] overload of [addListAfterWithRetry]. */
    @JvmOverloads
    fun <R : Base> addListAfterWithRetry(
        key: String,
        vararg deps: String,
        maxAttempts: Int = 3,
        initialDelayMs: Long = 500,
        retryOn: Predicate<Exception> = Predicate { true },
        block: Function<Map<String, List<Base>>, out Collection<out R>>
    ): AsyncOperationResult =
        addListAfterWithRetry(key, *deps, maxAttempts = maxAttempts, initialDelayMs = initialDelayMs, retryOn = retryOn::test) { map -> block.apply(map) }

    // ── Type-safe single-dependency overloads for retry ──────────────────────

    @JvmOverloads
    fun <T : Base> addAfterWithRetry(
        key: String,
        dep: String,
        type: Class<T>,
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
        val value = deps[dep]!!.filterIsInstance(type).firstOrNull()
            ?: error("No value of type ${type.simpleName} found under dependency '$dep'")
        block(value)
    }

    /** Java-friendly [Function]/[Predicate] overload of [addAfterWithRetry] (typed). */
    @JvmOverloads
    fun <T : Base> addAfterWithRetry(
        key: String, dep: String, type: Class<T>,
        maxAttempts: Int = 3,
        initialDelayMs: Long = 500,
        retryOn: Predicate<Exception> = Predicate { true },
        block: Function<T, out Base>
    ): AsyncOperationResult =
        addAfterWithRetry(key, dep, type, maxAttempts, initialDelayMs, retryOn::test) { block.apply(it) }

    @JvmOverloads
    fun <T : Base, R : Base> addListAfterWithRetry(
        key: String,
        dep: String,
        type: Class<T>,
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
        val value = deps[dep]!!.filterIsInstance(type).firstOrNull()
            ?: error("No value of type ${type.simpleName} found under dependency '$dep'")
        block(value)
    }

    /** Java-friendly [Function]/[Predicate] overload of [addListAfterWithRetry] (typed). */
    @JvmOverloads
    fun <T : Base, R : Base> addListAfterWithRetry(
        key: String, dep: String, type: Class<T>,
        maxAttempts: Int = 3,
        initialDelayMs: Long = 500,
        retryOn: Predicate<Exception> = Predicate { true },
        block: Function<T, out Collection<out R>>
    ): AsyncOperationResult =
        addListAfterWithRetry(key, dep, type, maxAttempts, initialDelayMs, retryOn::test) { block.apply(it) }

    @JvmOverloads
    fun <T : Base> addAfterAllWithRetry(
        key: String,
        dep: String,
        type: Class<T>,
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
        val values = deps[dep]!!.filterIsInstance(type)
        block(values)
    }

    /** Java-friendly [Function]/[Predicate] overload of [addAfterAllWithRetry]. */
    @JvmOverloads
    fun <T : Base> addAfterAllWithRetry(
        key: String, dep: String, type: Class<T>,
        maxAttempts: Int = 3,
        initialDelayMs: Long = 500,
        retryOn: Predicate<Exception> = Predicate { true },
        block: Function<List<T>, out Base>
    ): AsyncOperationResult =
        addAfterAllWithRetry(key, dep, type, maxAttempts, initialDelayMs, retryOn::test) { block.apply(it) }

    @JvmOverloads
    fun <T : Base, R : Base> addListAfterAllWithRetry(
        key: String,
        dep: String,
        type: Class<T>,
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
        val values = deps[dep]!!.filterIsInstance(type)
        block(values)
    }

    /** Java-friendly [Function]/[Predicate] overload of [addListAfterAllWithRetry]. */
    @JvmOverloads
    fun <T : Base, R : Base> addListAfterAllWithRetry(
        key: String, dep: String, type: Class<T>,
        maxAttempts: Int = 3,
        initialDelayMs: Long = 500,
        retryOn: Predicate<Exception> = Predicate { true },
        block: Function<List<T>, out Collection<out R>>
    ): AsyncOperationResult =
        addListAfterAllWithRetry(key, dep, type, maxAttempts, initialDelayMs, retryOn::test) { block.apply(it) }

    // ── Independent task with fallback value ─────────────────────────────────

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

    /** Java-friendly [Callable] overload of [addWithDefault]. */
    fun <R : Base> addWithDefault(key: String, default: R, block: Callable<out R>): AsyncOperationResult =
        addWithDefault(key, default) { block.call() }

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

    /** Java-friendly [Callable] overload of [addListWithDefault]. */
    fun <R : Base> addListWithDefault(key: String, default: Collection<R>, block: Callable<out Collection<out R>>): AsyncOperationResult =
        addListWithDefault(key, default) { block.call() }

    // ── Conditional task registration ────────────────────────────────────────

    fun addIf(condition: Boolean, key: String, block: suspend () -> Base): AsyncOperationResult =
        if (condition) add(key, block) else this

    /** Java-friendly [Callable] overload of [addIf]. */
    fun addIf(condition: Boolean, key: String, block: Callable<out Base>): AsyncOperationResult =
        if (condition) add(key, block) else this

    fun <R : Base> addListIf(condition: Boolean, key: String, block: suspend () -> Collection<R>): AsyncOperationResult =
        if (condition) addList(key, block) else this

    /** Java-friendly [Callable] overload of [addListIf]. */
    fun <R : Base> addListIf(condition: Boolean, key: String, block: Callable<out Collection<out R>>): AsyncOperationResult =
        if (condition) addList(key, block) else this

    // ── DAG composition ──────────────────────────────────────────────────────

    fun merge(other: AsyncOperationResult): AsyncOperationResult = also {
        other.nodes.values.forEach { node -> registerNode(node.key, node) }
    }

    fun merge(block: () -> AsyncOperationResult): AsyncOperationResult = merge(block())

    // ── DAG inspection ───────────────────────────────────────────────────────

    fun describe(): String {
        if (nodes.isEmpty()) return "AsyncOperationResult DAG: (empty)"

        // Iterative BFS tier assignment: tier(n) = 1 + max(tier(dep)) for all deps.
        // Processing nodes in reverse topological order (deps before dependents) avoids
        // the StackOverflowError that would occur in deep DAGs with recursive computeTier.
        val tierOf = mutableMapOf<String, Int>()
        val queue = ArrayDeque<String>()
        nodes.keys.filterTo(queue) { nodes[it]!!.deps.isEmpty() }
        queue.forEach { tierOf[it] = 1 }
        while (queue.isNotEmpty()) {
            val key = queue.removeFirst()
            val nextTier = tierOf[key]!! + 1
            nodes.values
                .filter { key in it.deps }
                .forEach { dependent ->
                    val current = tierOf[dependent.key] ?: 0
                    if (nextTier > current) {
                        tierOf[dependent.key] = nextTier
                        queue.addLast(dependent.key)
                    }
                }
        }

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

    suspend fun execute(): OperationResult<Base> {
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
     * Blocking wrapper around [execute] — calls [execute] inside [kotlinx.coroutines.runBlocking].
     *
     * Inherits the DAG-level timeout configured via [timeout], if set.
     * Prefer [execute] from a coroutine context; use this method only from non-suspending call sites.
     */
    fun executeBlocking(): OperationResult<Base> = runBlocking { execute() }

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
