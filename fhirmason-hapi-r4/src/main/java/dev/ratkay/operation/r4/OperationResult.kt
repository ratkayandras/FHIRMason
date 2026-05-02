package dev.ratkay.operation.r4

import dev.ratkay.operation.DateTimeInput
import dev.ratkay.operation.ErrorStrategy
import dev.ratkay.operation.FhirPath
import dev.ratkay.operation.IOperationResult
import dev.ratkay.operation.StepMetrics
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException
import org.hl7.fhir.r4.model.Base
import org.hl7.fhir.r4.model.BooleanType
import org.hl7.fhir.r4.model.Extension
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.CanonicalType
import org.hl7.fhir.r4.model.CodeType
import org.hl7.fhir.r4.model.CodeableConcept
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.DateTimeType
import org.hl7.fhir.r4.model.DecimalType
import org.hl7.fhir.r4.model.Identifier
import org.hl7.fhir.r4.model.IntegerType
import org.hl7.fhir.r4.model.OperationOutcome
import org.hl7.fhir.r4.model.Parameters
import org.hl7.fhir.r4.model.Period
import org.hl7.fhir.r4.model.Quantity
import org.hl7.fhir.r4.model.Reference
import org.hl7.fhir.r4.model.Resource
import org.hl7.fhir.r4.model.StringType
import org.hl7.fhir.r4.model.Type
import org.hl7.fhir.r4.model.UriType
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.util.IdentityHashMap
import kotlin.reflect.KClass
import kotlin.time.measureTime
import kotlin.time.measureTimedValue

/**
 * A typed, immutable pipeline builder that accumulates FHIR resources keyed by name and
 * carries the type of the most recently added resource through the generic parameter [T].
 *
 * The internal parameter map is always `Map<String, List<Base>>`; [T] only tracks the
 * "head" of the pipeline so that [getResult] returns the correct type without casting.
 * When the last step produced a list, [T] becomes `List<R>` and the result can be
 * retrieved via [getResult] or via the [getResultList] extension.
 *
 * ### Section map (for navigation)
 * | Section | Methods |
 * |---------|---------|
 * | Error state | `hasErrors`, `isSuccessful`, `getOutcomes`, `getFailedTasks`, `toOperationOutcome`, `throwIfErrors` |
 * | Metrics & timing | `timed`, `getMetrics` |
 * | Extension URL filters | `addFromHavingAllExtensions`, `addFromHavingAnyExtension`, `addAllFrom…`, `addFromHavingExtensionWithValueType`, `addFromHavingExtensionValueMatching`, … |
 * | Identifier filters | `addFromHavingIdentifierWithSystem`, `addFromHavingIdentifierWithValue`, `addFromHavingIdentifier`, `addAllFrom…` |
 * | Filtered builders | `addFromFiltered`, `addAllFromFiltered` — pass a [FhirFilter] predicate or any `(I) -> Boolean` |
 * | FHIRPath filters | `addFromMatching`, `addAllFromMatching` |
 * | Core builders | `add`, `addUsing`, `addAll`, `addAllUsing`, `addFrom`, `addAllFrom`, `addOrSkip`, `addOrDefault`, `addWithRetry`, `addWithRetryUsing` |
 * | Primitive values | `addString`, `addBoolean`, `addInteger`, `addDecimal`, `addDate([DateTimeInput])`, `addDateTime([DateTimeInput])`, `addInstant([DateTimeInput])`, `addTime([DateTimeInput])`, `addCanonical`, `addCoding`, `addReference`, `addIdentifier`, `addPeriod`, `addQuantity`, `addCodeableConcept` |
 * | Nested params | `addPart` |
 * | Extension support | `addWithExtension` |
 * | Query | `getAllParameters`, `getAll`, `getByType`, `containsKey`, `getKeys`, `count`, `totalCount`, `isEmpty`, `isNotEmpty` |
 * | Transformations | `filterByType`, `filterByName`, `flatMap`, `mapHead`, `mapHeadUsing`, `merge`, `remove`, `rename`, `mapStored`, `peek` |
 * | Conditional chaining | `whenTrue`, `ifPresent`, `guardFalse`, `whenPath`, `guardPath` |
 * | FHIRPath extraction | `selectByPath` |
 * | Extraction | `takeFirst`, `takeFirstTyped`, `extractParam`, `extractParamList` |
 * | Reference linking | `linkReferences` |
 * | Serialization | `toParameters`, `toBundle`, `toCollectionBundle`, `toTransactionBundle`, `toBatchBundle`, `getResult` |
 * | Factory *(companion)* | `of`, `fromParameters`, `fromParametersTyped`, `fromBundle`, `fromBundleTyped`, `empty` |
 */
class OperationResult<T> private constructor(
    private val parameters: MutableMap<String, MutableList<Base>>,
    private val result: T?,
    private val outcomes: MutableList<OperationOutcome>,
    private val errorStrategy: ErrorStrategy,
    private val failedTasks: MutableMap<String, OperationOutcome>,
    private val timingEnabled: Boolean = false,
    private val metrics: MutableList<StepMetrics> = mutableListOf(),
    private val extensions: MutableMap<String, MutableMap<Base, List<Extension>>> = mutableMapOf()
) : IOperationResult<T> {

    // Error state

    override fun hasErrors(): Boolean = outcomes.isNotEmpty()

    override fun isSuccessful(): Boolean = outcomes.isEmpty()

    /** Returns all [OperationOutcome] instances recorded by failed pipeline steps. */
    fun getOutcomes(): List<OperationOutcome> = outcomes.toList()

    /**
     * Returns the map of task keys to their [OperationOutcome] for steps that failed when this
     * result was produced by [AsyncOperationResult]. Empty for synchronous pipelines.
     */
    fun getFailedTasks(): Map<String, OperationOutcome> = failedTasks.toMap()

    /**
     * Merges all recorded [OperationOutcome] instances into a single composite
     * [OperationOutcome] whose issues are the union of every individual outcome's issues.
     *
     * All issue fields are preserved — `severity`, `code`, `diagnostics`, `location`,
     * `expression`, `details`, `extension`, etc. — via HAPI's own [Base.copy] mechanism.
     */
    fun toOperationOutcome(): OperationOutcome = OperationOutcome().apply {
        this@OperationResult.outcomes.forEach { oo ->
            oo.issue.forEach { issue -> addIssue(issue.copy()) }
        }
    }

    /**
     * Throws an [ca.uhn.fhir.rest.server.exceptions.InternalErrorException] containing the
     * merged [OperationOutcome] if [hasErrors] is `true`; otherwise returns this result unchanged.
     *
     * The exception message is taken from the first issue's `diagnostics` field, falling back to
     * `"Pipeline completed with errors"` when no diagnostic text is present.
     */
    override fun throwIfErrors(): OperationResult<T> {
        if (hasErrors()) {
            val outcome = toOperationOutcome()
            val message = outcome.issue.firstOrNull()?.diagnostics ?: "Pipeline completed with errors"
            throw InternalErrorException(message, outcome)
        }
        return this
    }

    // Builder helpers

    private fun shouldSkip() = errorStrategy == ErrorStrategy.FAIL_FAST && hasErrors()

    private fun <R> skippedResult(): OperationResult<R> = copyWith(null)

    private fun <R> copyWith(
        result: R?,
        params: MutableMap<String, MutableList<Base>> = parameters,
        timingEnabled: Boolean = this.timingEnabled,
        extensions: MutableMap<String, MutableMap<Base, List<Extension>>> = this.extensions,
        errorStrategy: ErrorStrategy = this.errorStrategy
    ): OperationResult<R> = OperationResult(params, result, outcomes, errorStrategy, failedTasks, timingEnabled, metrics, extensions)

    private fun shallowCopyParams(): MutableMap<String, MutableList<Base>> =
        parameters.mapValues { (_, values) -> values.toMutableList() }.toMutableMap()

    private fun shallowCopyExtensions(): MutableMap<String, MutableMap<Base, List<Extension>>> =
        extensions.mapValues { (_, inner) ->
            IdentityHashMap<Base, List<Extension>>(inner)
        }.toMutableMap()

    // Metrics and timing

    /**
     * Enables per-step metrics collection for subsequent pipeline steps.
     * When disabled (the default), [getMetrics] returns an empty list.
     */
    override fun timed(): OperationResult<T> = copyWith(result, timingEnabled = true)

    /** Returns a snapshot of [StepMetrics] collected so far. Empty when [timed] was not called. */
    override fun getMetrics(): List<StepMetrics> = metrics.toList()

    /**
     * Returns a new pipeline with [strategy] applied to all subsequent steps.
     *
     * Can be called at any point in the chain — changes take effect from that step onwards.
     * The default strategy is [ErrorStrategy.FAIL_FAST]; call this method to switch to
     * [ErrorStrategy.ACCUMULATE] or [ErrorStrategy.PROPAGATE] mid-chain.
     *
     * @param strategy the [ErrorStrategy] to apply from this point in the pipeline onwards.
     */
    fun useErrorStrategy(strategy: ErrorStrategy): OperationResult<T> = copyWith(result, errorStrategy = strategy)

    // Private logging/metrics helpers

    private fun logStep(key: String, typeDescription: String, durationMs: Long) {
        logger.debug("FHIRMason | step='{}' | type={} | duration={}ms", key, typeDescription, durationMs)
        if (logger.isTraceEnabled) {
            logger.trace("FHIRMason | state: {}", parameters.mapValues { it.value.size })
        }
    }

    private fun recordMetric(key: String, resourceType: String, durationMs: Long, success: Boolean) {
        if (timingEnabled) metrics.add(StepMetrics(key, resourceType, durationMs, success))
    }

    /**
     * Common try/catch skeleton shared by [runBuilderStep] and [runPrimitiveStep].
     *
     * Checks [shouldSkip] and delegates to [onSkip] for the early return, times the [block]
     * execution with a monotonic clock via [measureTimedValue], and on any exception delegates
     * to [onError] with the exception and elapsed milliseconds.  All three lambda parameters
     * are inlined.
     */
    private inline fun <R> runStep(
        onSkip: () -> OperationResult<R>,
        onError: (Exception, Long) -> OperationResult<R>,
        block: () -> OperationResult<R>
    ): OperationResult<R> {
        if (shouldSkip()) return onSkip()
        if (errorStrategy == ErrorStrategy.PROPAGATE) return block()
        val (result, duration) = measureTimedValue { runCatching { block() } }
        val durationMs = duration.inWholeMilliseconds
        return result.fold(
            onSuccess = { it },
            onFailure = { t ->
                if (t !is Exception) throw t
                onError(t, durationMs)
            }
        )
    }

    private fun <R> runBuilderStep(name: String?, block: () -> OperationResult<R>): OperationResult<R> =
        runStep(
            onSkip = { skippedResult() },
            onError = { e, durationMs ->
                recordMetric(name ?: "unknown", "", durationMs, false)
                outcomes.add(errorOutcome(e))
                skippedResult()
            },
            block = block
        )

    /**
     * Shared try/catch shell for all primitive-value convenience methods.
     *
     * On success: stores the FHIR value, records a metric, and logs the step.
     * On exception: logs a WARN, records a failed metric, and adds a WARNING-severity
     * [OperationOutcome] — preserving the rich embedded outcome from a
     * [BaseServerResponseException] rather than discarding it in favour of a plain message.
     * The pipeline head type [T] is never changed; [shouldSkip] is checked first.
     * Delegates to [runStep] for the shared try/catch skeleton.
     */
    private fun runPrimitiveStep(name: String, build: () -> Base): OperationResult<T> =
        runStep(
            onSkip = { copyWith(result) },
            onError = { e, durationMs ->
                logger.warn("FHIRMason | step='{}' | WARN: {}", name, e.message)
                recordMetric(name, "", durationMs, false)
                outcomes.add(warningOutcome(e))
                copyWith(result)
            },
            block = {
                val (fhirValue, duration) = measureTimedValue { build() }
                val durationMs = duration.inWholeMilliseconds
                parameters.getOrPut(name) { mutableListOf() }.add(fhirValue)
                recordMetric(name, fhirValue.fhirType(), durationMs, true)
                logStep(name, fhirValue.fhirType(), durationMs)
                copyWith(result)
            }
        )

    private fun <R : Base> storeAndCopy(name: String?, value: R, durationMs: Long): OperationResult<R> {
        val key = name ?: value.fhirType().lowercase()
        parameters.getOrPut(key) { mutableListOf() }.add(value)
        recordMetric(key, value.fhirType(), durationMs, true)
        logStep(key, value.fhirType(), durationMs)
        return copyWith(value)
    }

    private fun <R : Base> storeListAndCopy(name: String?, values: Collection<R>, durationMs: Long): OperationResult<List<R>> {
        val list = values.toList()
        addToParameters(list, name)
        val key = name ?: list.firstOrNull()?.fhirType()?.lowercase() ?: "list"
        val typeDesc = "${list.firstOrNull()?.fhirType() ?: "Empty"}[${list.size}]"
        recordMetric(key, typeDesc, durationMs, true)
        logStep(key, typeDesc, durationMs)
        return copyWith(list)
    }


    // Builder methods — filter accumulated parameters by an arbitrary predicate

    /**
     * Collects all accumulated resources of [type], keeps those satisfying [predicate],
     * and passes the filtered list to [builder] to produce the new pipeline head [R].
     *
     * Supply predicates from [FhirFilter] or any `(I) -> Boolean` lambda.
     * Use [addAllFromFiltered] when [builder] returns a `List<R>`.
     *
     * Error handling follows Pattern A.
     */
    @JvmOverloads
    fun <I : Base, R : Base> addFromFiltered(
        name: String? = null,
        type: KClass<I>,
        predicate: (I) -> Boolean,
        builder: (List<I>) -> R
    ): OperationResult<R> = runBuilderStep(name) {
        val (value, duration) = measureTimedValue {
            builder(parameters.values.flatten().filterIsInstance(type.java).filter(predicate))
        }
        storeAndCopy(name, value, duration.inWholeMilliseconds)
    }

    /** Reified overload of [addFromFiltered] — no explicit [KClass] needed. */
    inline fun <reified I : Base, R : Base> addFromFiltered(
        name: String? = null,
        noinline predicate: (I) -> Boolean,
        noinline builder: (List<I>) -> R
    ): OperationResult<R> = addFromFiltered(name, I::class, predicate, builder)

    /** Java-friendly overload of [addFromFiltered] — accepts [Class] instead of [KClass]. */
    @JvmOverloads
    fun <I : Base, R : Base> addFromFiltered(
        name: String? = null,
        type: Class<I>,
        predicate: (I) -> Boolean,
        builder: (List<I>) -> R
    ): OperationResult<R> = addFromFiltered(name, type.kotlin, predicate, builder)

    /**
     * Like [addFromFiltered] but [builder] returns a `Collection<R>`, making the new
     * pipeline head `List<R>`.
     *
     * Accepts any [Collection] return (e.g. [List], [Set], [LinkedHashSet]); the pipeline head is
     * always stored as a [List].
     */
    @JvmOverloads
    fun <I : Base, R : Base> addAllFromFiltered(
        name: String? = null,
        type: KClass<I>,
        predicate: (I) -> Boolean,
        builder: (List<I>) -> Collection<R>
    ): OperationResult<List<R>> = runBuilderStep(name) {
        val (values, duration) = measureTimedValue {
            builder(parameters.values.flatten().filterIsInstance(type.java).filter(predicate))
        }
        storeListAndCopy(name, values, duration.inWholeMilliseconds)
    }

    /** Reified overload of [addAllFromFiltered] — no explicit [KClass] needed. */
    inline fun <reified I : Base, R : Base> addAllFromFiltered(
        name: String? = null,
        noinline predicate: (I) -> Boolean,
        noinline builder: (List<I>) -> Collection<R>
    ): OperationResult<List<R>> = addAllFromFiltered(name, I::class, predicate, builder)

    /** Java-friendly overload of [addAllFromFiltered] — accepts [Class] instead of [KClass]. */
    @JvmOverloads
    fun <I : Base, R : Base> addAllFromFiltered(
        name: String? = null,
        type: Class<I>,
        predicate: (I) -> Boolean,
        builder: (List<I>) -> Collection<R>
    ): OperationResult<List<R>> = addAllFromFiltered(name, type.kotlin, predicate, builder)

    // Builder methods — filter all accumulated parameters by type and FHIRPath expression

    private fun <I : Base> collectByPath(type: KClass<I>, expression: String): List<I> =
        filterByPath(parameters.values.flatten(), type.java, expression)

    /**
     * Searches **all** accumulated parameters for instances of [type] where [expression]
     * evaluates to `true` (using [FhirPathHelper.matches] semantics), passes the typed list
     * to [builder], and stores the single result under [name] (or the builder result's
     * [Base.fhirType] lowercase when [name] is `null` — consistent with [add]).
     *
     * Error handling follows Pattern A: exceptions (including malformed FHIRPath syntax)
     * record an ERROR-severity [OperationOutcome] and skip the head value.
     *
     * [expression] is evaluated against each individual resource, so it should be written as
     * a relative path (e.g. `"active = true"`, not `"Patient.active = true"`).
     *
     * Use [addAllFromMatching] when [builder] returns a `List<R>`.
     */
    @JvmOverloads
    fun <I : Base, R : Base> addFromMatching(
        name: String? = null,
        type: KClass<I>,
        expression: String,
        builder: (List<I>) -> R
    ): OperationResult<R> {
        return runBuilderStep(name) {
            val (value, duration) = measureTimedValue { builder(collectByPath(type, expression)) }
            storeAndCopy(name, value, duration.inWholeMilliseconds)
        }
    }

    /** [FhirPath] overload of [addFromMatching] — builds the expression and delegates. */
    @JvmOverloads
    fun <I : Base, R : Base> addFromMatching(
        name: String? = null,
        type: KClass<I>,
        expression: FhirPath,
        builder: (List<I>) -> R
    ): OperationResult<R> = addFromMatching(name, type, expression.build(), builder)

    /**
     * Like [addFromMatching] but [builder] returns a `Collection<R>`.
     * When [name] is `null`, the output key defaults to the first element's [Base.fhirType]
     * (lowercase) — consistent with [addAll].
     *
     * Accepts any [Collection] return (e.g. [List], [Set], [LinkedHashSet]); the pipeline head is
     * always stored as a [List].
     */
    @JvmOverloads
    fun <I : Base, R : Base> addAllFromMatching(
        name: String? = null,
        type: KClass<I>,
        expression: String,
        builder: (List<I>) -> Collection<R>
    ): OperationResult<List<R>> {
        return runBuilderStep(name) {
            val (values, duration) = measureTimedValue { builder(collectByPath(type, expression)) }
            storeListAndCopy(name, values, duration.inWholeMilliseconds)
        }
    }

    /** [FhirPath] overload of [addAllFromMatching] — builds the expression and delegates. */
    @JvmOverloads
    fun <I : Base, R : Base> addAllFromMatching(
        name: String? = null,
        type: KClass<I>,
        expression: FhirPath,
        builder: (List<I>) -> Collection<R>
    ): OperationResult<List<R>> = addAllFromMatching(name, type, expression.build(), builder)

    /** Reified overload of [addFromMatching] — no [KClass] argument needed at call sites. */
    inline fun <reified I : Base, R : Base> addFromMatching(
        name: String? = null,
        expression: String,
        noinline builder: (List<I>) -> R
    ): OperationResult<R> = addFromMatching(name, I::class, expression, builder)

    /** [FhirPath] reified overload of [addFromMatching]. */
    inline fun <reified I : Base, R : Base> addFromMatching(
        name: String? = null,
        expression: FhirPath,
        noinline builder: (List<I>) -> R
    ): OperationResult<R> = addFromMatching(name, I::class, expression.build(), builder)

    /** Reified overload of [addAllFromMatching] — no [KClass] argument needed at call sites. */
    inline fun <reified I : Base, R : Base> addAllFromMatching(
        name: String? = null,
        expression: String,
        noinline builder: (List<I>) -> Collection<R>
    ): OperationResult<List<R>> = addAllFromMatching(name, I::class, expression, builder)

    /** [FhirPath] reified overload of [addAllFromMatching]. */
    inline fun <reified I : Base, R : Base> addAllFromMatching(
        name: String? = null,
        expression: FhirPath,
        noinline builder: (List<I>) -> Collection<R>
    ): OperationResult<List<R>> = addAllFromMatching(name, I::class, expression.build(), builder)

    // Builder methods - single item

    /**
     * Invokes [builder] to produce a new FHIR resource, stores it under [name] (or the
     * resource's [Base.fhirType] lowercase when [name] is `null`), and returns a new
     * [OperationResult] with [R] as the pipeline head.
     *
     * Error handling follows Pattern A: exceptions record an ERROR-severity [OperationOutcome]
     * and skip the head value.
     */
    @JvmOverloads
    fun <R : Base> add(name: String? = null, builder: () -> R): OperationResult<R> =
        runBuilderStep(name) {
            val (value, duration) = measureTimedValue { builder() }
            storeAndCopy(name, value, duration.inWholeMilliseconds)
        }

    /**
     * Like [add] but [builder] receives the current pipeline head value [T].
     *
     * Error handling follows Pattern A.
     */
    @JvmOverloads
    fun <R : Base> addUsing(name: String? = null, builder: (T) -> R): OperationResult<R> =
        runBuilderStep(name) {
            val (value, duration) = measureTimedValue { builder(getResult()) }
            storeAndCopy(name, value, duration.inWholeMilliseconds)
        }

    // Builder methods - list
    // addAll/addAllUsing return OperationResult<List<R>>; use getResultList() or getResult()
    // to retrieve the typed list without casting.

    /**
     * Invokes [builder] to produce a collection of FHIR resources, stores each element under
     * [name] (or each element's [Base.fhirType] lowercase when [name] is `null`), and returns a
     * new [OperationResult] with `List<R>` as the pipeline head.
     *
     * Accepts any [Collection] (e.g. [List], [Set], [LinkedHashSet]); the pipeline head is
     * always stored as a [List].
     *
     * Error handling follows Pattern A.
     */
    @JvmOverloads
    fun <R : Base> addAll(name: String? = null, builder: () -> Collection<R>): OperationResult<List<R>> =
        runBuilderStep(name) {
            val (values, duration) = measureTimedValue { builder() }
            storeListAndCopy(name, values, duration.inWholeMilliseconds)
        }

    /**
     * Like [addAll] but [builder] receives the current pipeline head value [T].
     *
     * Accepts any [Collection] (e.g. [List], [Set], [LinkedHashSet]); the pipeline head is
     * always stored as a [List].
     *
     * Error handling follows Pattern A.
     */
    @JvmOverloads
    fun <R : Base> addAllUsing(name: String? = null, builder: (T) -> Collection<R>): OperationResult<List<R>> =
        runBuilderStep(name) {
            val (values, duration) = measureTimedValue { builder(getResult()) }
            storeListAndCopy(name, values, duration.inWholeMilliseconds)
        }

    // Builder methods - from existing parameters

    /**
     * Reads all values already accumulated under [name], filters them to [type], passes the
     * typed list to [builder], and replaces the values stored under [name] with the single
     * result. The pipeline head becomes [R].
     *
     * Error handling follows Pattern A.
     */
    fun <I : Base, R : Base> addFrom(name: String, type: KClass<I>, builder: (List<I>) -> R): OperationResult<R> =
        runBuilderStep(name) {
            val (value, duration) = measureTimedValue {
                val filtered = parameters[name]?.filterIsInstance(type.java) ?: emptyList()
                builder(filtered)
            }
            storeAndCopy(name, value, duration.inWholeMilliseconds)
        }

    /**
     * Java-friendly overload of [addFrom] — accepts a [Class] instead of a [KClass].
     */
    fun <I : Base, R : Base> addFrom(name: String, type: Class<I>, builder: (List<I>) -> R): OperationResult<R> =
        addFrom(name, type.kotlin, builder)

    /**
     * Like [addFrom] but [builder] returns a `Collection<R>`. The pipeline head becomes `List<R>`.
     *
     * Accepts any [Collection] return (e.g. [List], [Set], [LinkedHashSet]); the pipeline head is
     * always stored as a [List].
     *
     * Error handling follows Pattern A.
     */
    fun <I : Base, R : Base> addAllFrom(name: String, type: KClass<I>, builder: (List<I>) -> Collection<R>): OperationResult<List<R>> =
        runBuilderStep(name) {
            val (values, duration) = measureTimedValue {
                val filtered = parameters[name]?.filterIsInstance(type.java) ?: emptyList()
                builder(filtered)
            }
            storeListAndCopy(name, values, duration.inWholeMilliseconds)
        }

    /**
     * Java-friendly overload of [addAllFrom] — accepts a [Class] instead of a [KClass].
     */
    fun <I : Base, R : Base> addAllFrom(name: String, type: Class<I>, builder: (List<I>) -> Collection<R>): OperationResult<List<R>> =
        addAllFrom(name, type.kotlin, builder)

    // Builder variants with explicit error handling

    /**
     * Like [add] but on exception records a WARNING-severity [OperationOutcome] and preserves
     * the current head type [T] rather than skipping it (Pattern B — head-preserving).
     *
     * The result is still stored in the parameter map on success; on failure it is silently
     * omitted and the pipeline continues with the same head.
     */
    @JvmOverloads
    fun <R : Base> addOrSkip(name: String? = null, builder: () -> R): OperationResult<T> {
        if (shouldSkip()) return copyWith(result)
        val (builderResult, duration) = measureTimedValue { runCatching { builder() } }
        val durationMs = duration.inWholeMilliseconds
        return builderResult.fold(
            onSuccess = { value ->
                val key = name ?: value.fhirType().lowercase()
                parameters.getOrPut(key) { mutableListOf() }.add(value)
                recordMetric(key, value.fhirType(), durationMs, true)
                logStep(key, value.fhirType(), durationMs)
                copyWith(result)
            },
            onFailure = { t ->
                if (t !is Exception) throw t
                val key = name ?: "unknown"
                logger.warn("FHIRMason | step='{}' | WARN: {}", key, t.message)
                recordMetric(key, "", durationMs, false)
                outcomes.add(warningOutcome(t))
                copyWith(result)
            }
        )
    }

    /**
     * Like [add] but on exception stores [default] under [name] instead and returns a new
     * [OperationResult] with [R] as the pipeline head. A WARNING-severity [OperationOutcome]
     * is recorded on failure (Pattern B — head-preserving, result type changes to [R]).
     */
    @JvmOverloads
    fun <R : Base> addOrDefault(name: String? = null, default: R, builder: () -> R): OperationResult<R> {
        if (shouldSkip()) return skippedResult()
        val (builderResult, duration) = measureTimedValue { runCatching { builder() } }
        val durationMs = duration.inWholeMilliseconds
        return builderResult.fold(
            onSuccess = { value ->
                val key = name ?: value.fhirType().lowercase()
                parameters.getOrPut(key) { mutableListOf() }.add(value)
                recordMetric(key, value.fhirType(), durationMs, true)
                logStep(key, value.fhirType(), durationMs)
                copyWith(value)
            },
            onFailure = { t ->
                if (t !is Exception) throw t
                val key = name ?: default.fhirType().lowercase()
                logger.warn("FHIRMason | step='{}' | WARN: {}", key, t.message)
                recordMetric(key, "", durationMs, false)
                outcomes.add(warningOutcome(t))
                parameters.getOrPut(key) { mutableListOf() }.add(default)
                copyWith(default)
            }
        )
    }

    // ── Builder variant with retry ────────────────────────────────────────────

    /**
     * Executes [block] up to [maxAttempts] times with exponential backoff between retries.
     * Returns the first successful result, or throws the last caught exception when all
     * attempts are exhausted or [retryOn] returns `false`.
     *
     * Thread interruption during a sleep re-interrupts the thread and triggers an immediate throw.
     */
    private fun <R> executeWithRetry(
        maxAttempts: Int,
        initialDelayMs: Long,
        retryOn: (Exception) -> Boolean,
        block: () -> R
    ): R {
        var lastException: Exception? = null
        var delayMs = initialDelayMs
        for (attempt in 1..maxAttempts) {
            try {
                return block()
            } catch (e: Exception) {
                lastException = e
                if (!retryOn(e) || attempt == maxAttempts) break
                try {
                    Thread.sleep(delayMs)
                } catch (i: InterruptedException) {
                    Thread.currentThread().interrupt()
                    lastException = i
                    break
                }
                delayMs *= 2
            }
        }
        throw lastException!!
    }

    /**
     * Calls [builder] up to [maxAttempts] times, retrying on transient failures with
     * exponential backoff between attempts.
     *
     * The backoff schedule (no delay before attempt 1):
     * ```
     * Before attempt 2 → sleep initialDelayMs
     * Before attempt 3 → sleep initialDelayMs × 2
     * Before attempt k → sleep initialDelayMs × 2^(k-2)
     * ```
     *
     * On each failure [retryOn] is consulted — if it returns `false` the failure is recorded
     * immediately without further retries. If all [maxAttempts] are exhausted, the last
     * exception is recorded as an ERROR-severity [OperationOutcome] and the pipeline head is
     * skipped (Pattern A — identical to [add]).
     *
     * Thread interruption during a backoff sleep re-interrupts the current thread and
     * causes immediate Pattern A failure.
     *
     * @param maxAttempts total number of attempts including the first; must be ≥ 1
     * @param initialDelayMs sleep duration before the second attempt in milliseconds; must be ≥ 0
     * @param retryOn predicate called with each exception — return `false` to stop retrying
     * @param builder the lambda to invoke; invoked up to [maxAttempts] times
     */
    @JvmOverloads
    fun <R : Base> addWithRetry(
        name: String? = null,
        maxAttempts: Int = 3,
        initialDelayMs: Long = 500,
        retryOn: (Exception) -> Boolean = { true },
        builder: () -> R
    ): OperationResult<R> {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
        require(initialDelayMs >= 0) { "initialDelayMs must be non-negative" }
        return runBuilderStep(name) {
            val (value, duration) = measureTimedValue {
                executeWithRetry(maxAttempts, initialDelayMs, retryOn) { builder() }
            }
            storeAndCopy(name, value, duration.inWholeMilliseconds)
        }
    }

    /**
     * Like [addWithRetry] but the builder receives the current pipeline head value [T].
     * Useful when the resource to fetch depends on data already in the pipeline.
     *
     * @param maxAttempts total number of attempts including the first; must be ≥ 1
     * @param initialDelayMs sleep duration before the second attempt in milliseconds; must be ≥ 0
     * @param retryOn predicate called with each exception — return `false` to stop retrying
     * @param builder the lambda to invoke with the current head; invoked up to [maxAttempts] times
     */
    @JvmOverloads
    fun <R : Base> addWithRetryUsing(
        name: String? = null,
        maxAttempts: Int = 3,
        initialDelayMs: Long = 500,
        retryOn: (Exception) -> Boolean = { true },
        builder: (T) -> R
    ): OperationResult<R> {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
        require(initialDelayMs >= 0) { "initialDelayMs must be non-negative" }
        return runBuilderStep(name) {
            // Resolve head once, before the retry loop. If result is null this throws
            // IllegalStateException here — caught by runBuilderStep's outer try/catch
            // (Pattern A, one attempt), not inside the retry loop.
            val head = getResult()
            val (value, duration) = measureTimedValue {
                executeWithRetry(maxAttempts, initialDelayMs, retryOn) { builder(head) }
            }
            storeAndCopy(name, value, duration.inWholeMilliseconds)
        }
    }

    // ── Primitive value convenience methods ──────────────────────────────────
    //
    // All primitive methods follow Pattern B: exceptions record a WARNING-severity
    // OperationOutcome and preserve the current head type T unchanged.

    /** Stores a FHIR [StringType] under [name]. On error records a WARNING (Pattern B). */
    fun addString(name: String, value: String): OperationResult<T>       = runPrimitiveStep(name) { StringType(value) }

    /** Stores a FHIR [BooleanType] under [name]. On error records a WARNING (Pattern B). */
    fun addBoolean(name: String, value: Boolean): OperationResult<T>     = runPrimitiveStep(name) { BooleanType(value) }

    /** Stores a FHIR [IntegerType] under [name]. On error records a WARNING (Pattern B). */
    fun addInteger(name: String, value: Int): OperationResult<T>         = runPrimitiveStep(name) { IntegerType(value) }

    /** Stores a FHIR [DecimalType] under [name]. On error records a WARNING (Pattern B). */
    fun addDecimal(name: String, value: BigDecimal): OperationResult<T>  = runPrimitiveStep(name) { DecimalType(value) }

    /** Stores a FHIR [CodeType] under [name]. On error records a WARNING (Pattern B). */
    fun addCode(name: String, value: String): OperationResult<T>         = runPrimitiveStep(name) { CodeType(value) }

    /** Stores a FHIR [UriType] under [name]. On error records a WARNING (Pattern B). */
    fun addUri(name: String, value: String): OperationResult<T>          = runPrimitiveStep(name) { UriType(value) }

    /** Converts [value] to a FHIR date and stores it under [name]. On error records a WARNING (Pattern B). */
    fun addDate(name: String, value: DateTimeInput): OperationResult<T>     = runPrimitiveStep(name) { FhirDateTimeConverter.toFhirDate(value) }

    /** Converts [value] to a FHIR dateTime and stores it under [name]. On error records a WARNING (Pattern B). */
    fun addDateTime(name: String, value: DateTimeInput): OperationResult<T> = runPrimitiveStep(name) { FhirDateTimeConverter.toFhirDateTime(value) }

    /** Converts [value] to a FHIR instant and stores it under [name]. On error records a WARNING (Pattern B). */
    fun addInstant(name: String, value: DateTimeInput): OperationResult<T>  = runPrimitiveStep(name) { FhirDateTimeConverter.toFhirInstant(value) }

    /** Converts [value] to a FHIR time and stores it under [name]. On error records a WARNING (Pattern B). */
    fun addTime(name: String, value: DateTimeInput): OperationResult<T>     = runPrimitiveStep(name) { FhirDateTimeConverter.toFhirTime(value) }

    /** Stores a FHIR [CanonicalType] under [name]. On error records a WARNING (Pattern B). */
    fun addCanonical(name: String, value: String): OperationResult<T>    = runPrimitiveStep(name) { CanonicalType(value) }

    /** Like [addString] but derives [value] from the current pipeline head via [builder]. */
    fun addStringUsing(name: String, builder: (T) -> String): OperationResult<T>           = runPrimitiveStep(name) { StringType(builder(getResult())) }

    /** Like [addBoolean] but derives [value] from the current pipeline head via [builder]. */
    fun addBooleanUsing(name: String, builder: (T) -> Boolean): OperationResult<T>         = runPrimitiveStep(name) { BooleanType(builder(getResult())) }

    /** Like [addInteger] but derives [value] from the current pipeline head via [builder]. */
    fun addIntegerUsing(name: String, builder: (T) -> Int): OperationResult<T>             = runPrimitiveStep(name) { IntegerType(builder(getResult())) }

    /** Like [addDecimal] but derives [value] from the current pipeline head via [builder]. */
    fun addDecimalUsing(name: String, builder: (T) -> BigDecimal): OperationResult<T>      = runPrimitiveStep(name) { DecimalType(builder(getResult())) }

    /** Like [addCode] but derives [value] from the current pipeline head via [builder]. */
    fun addCodeUsing(name: String, builder: (T) -> String): OperationResult<T>             = runPrimitiveStep(name) { CodeType(builder(getResult())) }

    /** Like [addUri] but derives [value] from the current pipeline head via [builder]. */
    fun addUriUsing(name: String, builder: (T) -> String): OperationResult<T>              = runPrimitiveStep(name) { UriType(builder(getResult())) }

    /** Like [addDate] but derives [value] from the current pipeline head via [builder]. */
    fun addDateUsing(name: String, builder: (T) -> DateTimeInput): OperationResult<T>      = runPrimitiveStep(name) { FhirDateTimeConverter.toFhirDate(builder(getResult())) }

    /** Like [addDateTime] but derives [value] from the current pipeline head via [builder]. */
    fun addDateTimeUsing(name: String, builder: (T) -> DateTimeInput): OperationResult<T>  = runPrimitiveStep(name) { FhirDateTimeConverter.toFhirDateTime(builder(getResult())) }

    /** Like [addInstant] but derives [value] from the current pipeline head via [builder]. */
    fun addInstantUsing(name: String, builder: (T) -> DateTimeInput): OperationResult<T>   = runPrimitiveStep(name) { FhirDateTimeConverter.toFhirInstant(builder(getResult())) }

    /** Like [addTime] but derives [value] from the current pipeline head via [builder]. */
    fun addTimeUsing(name: String, builder: (T) -> DateTimeInput): OperationResult<T>      = runPrimitiveStep(name) { FhirDateTimeConverter.toFhirTime(builder(getResult())) }

    /** Like [addCanonical] but derives [value] from the current pipeline head via [builder]. */
    fun addCanonicalUsing(name: String, builder: (T) -> String): OperationResult<T>        = runPrimitiveStep(name) { CanonicalType(builder(getResult())) }

    // ── Complex data type convenience methods ────────────────────────────────
    //
    // All complex type methods follow Pattern B: on error a WARNING is recorded and the
    // head type T is preserved unchanged.

    /**
     * Stores a [Coding] under [name] with the given [system], [code], and optional [display].
     * On error records a WARNING (Pattern B).
     */
    @JvmOverloads
    fun addCoding(name: String, system: String, code: String, display: String? = null): OperationResult<T> =
        runPrimitiveStep(name) {
            Coding().apply {
                this.system = system
                this.code = code
                if (display != null) this.display = display
            }
        }

    /** Stores a [Reference] under [name] with the given [reference] string. On error records a WARNING (Pattern B). */
    fun addReference(name: String, reference: String): OperationResult<T> =
        runPrimitiveStep(name) { Reference(reference) }

    /** Stores an [Identifier] under [name] with the given [system] and [value]. On error records a WARNING (Pattern B). */
    fun addIdentifier(name: String, system: String, value: String): OperationResult<T> =
        runPrimitiveStep(name) {
            Identifier().apply {
                this.system = system
                this.value = value
            }
        }

    /**
     * Stores a [Period] under [name] from raw FHIR date-time strings. `null` values are omitted
     * from the period. On error records a WARNING (Pattern B).
     */
    fun addPeriod(name: String, start: String?, end: String?): OperationResult<T> =
        runPrimitiveStep(name) {
            Period().apply {
                if (start != null) this.startElement = DateTimeType(start)
                if (end != null) this.endElement = DateTimeType(end)
            }
        }

    /** Stores a [Period] under [name] from [LocalDateTime] values. `null` values are omitted. On error records a WARNING (Pattern B). */
    fun addPeriod(name: String, start: LocalDateTime?, end: LocalDateTime?): OperationResult<T> =
        runPrimitiveStep(name) {
            Period().apply {
                if (start != null) this.startElement = FhirDateTimeConverter.toFhirDateTime(start)
                if (end != null) this.endElement = FhirDateTimeConverter.toFhirDateTime(end)
            }
        }

    /** Stores a [Period] under [name] from [ZonedDateTime] values. `null` values are omitted. On error records a WARNING (Pattern B). */
    fun addPeriod(name: String, start: ZonedDateTime?, end: ZonedDateTime?): OperationResult<T> =
        runPrimitiveStep(name) {
            Period().apply {
                if (start != null) this.startElement = FhirDateTimeConverter.toFhirDateTime(start)
                if (end != null) this.endElement = FhirDateTimeConverter.toFhirDateTime(end)
            }
        }

    /** Stores a [Period] under [name] from [OffsetDateTime] values. `null` values are omitted. On error records a WARNING (Pattern B). */
    fun addPeriod(name: String, start: OffsetDateTime?, end: OffsetDateTime?): OperationResult<T> =
        runPrimitiveStep(name) {
            Period().apply {
                if (start != null) this.startElement = FhirDateTimeConverter.toFhirDateTime(start)
                if (end != null) this.endElement = FhirDateTimeConverter.toFhirDateTime(end)
            }
        }

    /**
     * Stores a [Quantity] under [name] with [value], [unit], and optional [system] and [code].
     * On error records a WARNING (Pattern B).
     */
    @JvmOverloads
    fun addQuantity(name: String, value: BigDecimal, unit: String, system: String? = null, code: String? = null): OperationResult<T> =
        runPrimitiveStep(name) {
            Quantity().apply {
                this.value = value
                this.unit = unit
                if (system != null) this.system = system
                if (code != null) this.code = code
            }
        }

    /**
     * Stores a [CodeableConcept] under [name] with a single [Coding] built from [system], [code],
     * and optional [display], plus an optional free-text [text]. On error records a WARNING (Pattern B).
     */
    @JvmOverloads
    fun addCodeableConcept(name: String, system: String, code: String, display: String? = null, text: String? = null): OperationResult<T> =
        runPrimitiveStep(name) {
            CodeableConcept().apply {
                addCoding().apply {
                    this.system = system
                    this.code = code
                    if (display != null) this.display = display
                }
                if (text != null) this.text = text
            }
        }

    // ── Nested parameter construction (parts) ───────────────────────────────

    /**
     * Builds a nested FHIR `Parameters.part` structure using a sub-pipeline builder lambda,
     * without relying on dot-delimited key names.
     *
     * The sub-pipeline receives its own empty parameter map so the parent map is not polluted.
     * Entries produced by the builder are prefixed with `"name."` and stored in the parent map.
     * [toParameters] then reconstructs the nested `part` structure automatically from these
     * dot-delimited keys.
     *
     * The sub-pipeline's outcomes/errors are NOT merged into the parent — only parameter map
     * entries are merged. The pipeline head type [T] is preserved.
     */
    fun addPart(name: String, builder: OperationResult<T>.() -> OperationResult<*>): OperationResult<T> {
        if (shouldSkip()) return copyWith(result)

        val subPipeline = OperationResult<T>(
            mutableMapOf(), result, mutableListOf(), errorStrategy, mutableMapOf(), timingEnabled, mutableListOf()
        )

        val (built, duration) = measureTimedValue { builder(subPipeline) }

        built.getAllParameters().forEach { (childKey, values) ->
            val prefixedKey = "$name.$childKey"
            parameters.getOrPut(prefixedKey) { mutableListOf() }.addAll(values)
        }

        val durationMs = duration.inWholeMilliseconds
        recordMetric(name, "part", durationMs, true)
        logStep(name, "part", durationMs)
        return copyWith(result)
    }

    // ── Extension support ──────────────────────────────────────────────

    /**
     * Adds [value] to the parameter map under [name] with FHIR [exts] attached.
     * When serialized via [toParameters], the extensions are added to the emitted
     * [Parameters.ParametersParameterComponent].
     *
     * Uses an [IdentityHashMap] for the inner extension lookup so that two different
     * instances that are `.equals()` but not the same object each keep independent
     * extension lists.
     *
     * Returns [OperationResult<T>] (unchanged head), like [addOrSkip].
     */
    fun <R : Base> addWithExtension(
        name: String,
        value: R,
        vararg exts: Extension
    ): OperationResult<T> {
        if (shouldSkip()) return copyWith(result)
        val durationMs = measureTime {
            parameters.getOrPut(name) { mutableListOf() }.add(value)
            if (exts.isNotEmpty()) {
                val innerMap = extensions.getOrPut(name) { IdentityHashMap() }
                innerMap[value] = exts.toList()
            }
        }.inWholeMilliseconds
        recordMetric(name, value.fhirType(), durationMs, true)
        logStep(name, value.fhirType(), durationMs)
        return copyWith(result)
    }

    // Query methods

    /** Returns a read-only snapshot of the full parameter map (all keys and all their values). */
    fun getAllParameters(): Map<String, List<Base>> =
        parameters.mapValues { it.value.toList() }

    /** Returns all values accumulated under [name], or an empty list if the key is absent. */
    fun getAll(name: String): List<Base> =
        parameters[name]?.toList() ?: emptyList()

    /**
     * Returns all accumulated values that are instances of [type], across all keys.
     * Prefer the inline reified overload [getByType] where the type can be inferred.
     */
    fun <R : Base> getByType(type: KClass<R>): List<R> =
        parameters.values.flatten().filterIsInstance(type.java)

    /** Reified overload — no [KClass] argument needed at call sites. */
    inline fun <reified R : Base> getByType(): List<R> = getByType(R::class)

    /**
     * Java-friendly overload of [getByType] — accepts a [Class] instead of a [KClass].
     */
    fun <R : Base> getByType(type: Class<R>): List<R> = getByType(type.kotlin)

    override fun containsKey(name: String): Boolean =
        parameters.containsKey(name)

    override fun getKeys(): Set<String> =
        parameters.keys.toSet()

    override fun count(name: String): Int =
        parameters[name]?.size ?: 0

    override fun totalCount(): Int =
        parameters.values.sumOf { it.size }

    override fun isEmpty(): Boolean =
        parameters.isEmpty()

    override fun isNotEmpty(): Boolean =
        !isEmpty()

    // Functional transformations

    /**
     * Returns a new result containing only entries whose values match [type].
     * Prefer the inline reified overload [filterByType] where the type can be inferred.
     */
    fun <R : Base> filterByType(type: KClass<R>): OperationResult<T> {
        val filtered = mutableMapOf<String, MutableList<Base>>()
        parameters.forEach { (name, values) ->
            val matchingValues = values.filterIsInstance(type.java)
            if (matchingValues.isNotEmpty()) {
                filtered[name] = matchingValues.toMutableList()
            }
        }
        return copyWith(result, params = filtered)
    }

    /** Reified overload — no [KClass] argument needed at call sites. */
    inline fun <reified R : Base> filterByType(): OperationResult<T> = filterByType(R::class)

    /**
     * Java-friendly overload of [filterByType] — accepts a [Class] instead of a [KClass].
     */
    fun <R : Base> filterByType(type: Class<R>): OperationResult<T> = filterByType(type.kotlin)

    /** Returns a new result containing only the entry for [name]. All other keys are dropped. No-op if [name] is absent. */
    fun filterByName(name: String): OperationResult<T> {
        val filtered = mutableMapOf<String, MutableList<Base>>()
        parameters[name]?.let { values ->
            filtered[name] = values.toMutableList()
        }
        return copyWith(result, params = filtered)
    }

    /**
     * Chains an inner pipeline on the current typed result [T], merges all of its parameter map
     * entries into the outer map, and returns an [OperationResult] whose head type and current
     * result come from the inner pipeline.
     *
     * On key collision with the outer map the values are accumulated under the same key.
     */
    fun <R : Base> flatMap(transform: (T) -> OperationResult<R>): OperationResult<R> {
        if (shouldSkip()) return skippedResult()
        val inner = transform(getResult())
        val newParams = shallowCopyParams()
        inner.parameters.forEach { (key, values) ->
            newParams.getOrPut(key) { mutableListOf() }.addAll(values)
        }
        return copyWith(inner.result, params = newParams, extensions = shallowCopyExtensions())
    }

    /**
     * Transforms the pipeline head from [T] to [R] using [transform], without adding any entry to
     * the parameter map.
     *
     * This is the lightweight alternative to [flatMap] when you only need to change the working
     * type mid-pipeline and do not want an intermediate value to appear in the output parameters.
     *
     * On exception: records an ERROR-severity [OperationOutcome] and skips the head (Pattern A).
     */
    fun <R : Base> mapHead(transform: (T) -> R): OperationResult<R> =
        runBuilderStep("mapHead") {
            val (r, duration) = measureTimedValue { transform(getResult()) }
            val durationMs = duration.inWholeMilliseconds
            recordMetric("mapHead", r.fhirType(), durationMs, true)
            logStep("mapHead", r.fhirType(), durationMs)
            copyWith(r)
        }

    /**
     * Receiver-lambda overload of [mapHead] — [transform] is called with the head as `this`.
     *
     * On exception: records an ERROR-severity [OperationOutcome] and skips the head (Pattern A).
     */
    fun <R : Base> mapHeadUsing(transform: T.() -> R): OperationResult<R> = mapHead { it.transform() }

    /**
     * Combines the parameter maps of this result and [other]. On key collision the values from
     * both results are accumulated under the same key. The current typed result [T] is preserved.
     */
    fun merge(other: OperationResult<*>): OperationResult<T> {
        val newParams = shallowCopyParams()
        other.parameters.forEach { (key, values) ->
            newParams.getOrPut(key) { mutableListOf() }.addAll(values)
        }
        return copyWith(result, params = newParams, extensions = shallowCopyExtensions())
    }

    /** Returns a new [OperationResult] without the entry for [name]. No-op if [name] is absent. */
    fun remove(name: String): OperationResult<T> {
        val newParams = shallowCopyParams()
        newParams.remove(name)
        return copyWith(result, params = newParams, extensions = shallowCopyExtensions())
    }

    /**
     * Returns a new [OperationResult] where all values stored under [oldName] are moved to
     * [newName]. If [oldName] does not exist, the result is unchanged. If [newName] already
     * exists, the moved values are accumulated alongside the existing ones.
     */
    fun rename(oldName: String, newName: String): OperationResult<T> {
        val newParams = shallowCopyParams()
        newParams.remove(oldName)?.let { newParams.getOrPut(newName) { mutableListOf() }.addAll(it) }
        return copyWith(result, params = newParams, extensions = shallowCopyExtensions())
    }

    /**
     * Applies [transform] to every value stored under [name] that is an instance of [type],
     * replacing each matching value with the result of [transform]. Values that are not
     * instances of [type] are passed through unchanged.
     *
     * If [name] is absent from the parameter map the result is returned unchanged (no-op).
     * The pipeline head type [T] is always preserved.
     *
     * On exception inside [transform]: a WARNING-severity [OperationOutcome] is recorded and
     * the original parameter map is preserved unchanged (Pattern B — head-preserving).
     * Respects [ErrorStrategy.FAIL_FAST]: returns immediately when the pipeline has errors.
     *
     * @param name the key whose values to transform.
     * @param type the [KClass] of values to match; non-matching values pass through.
     * @param transform element-wise mapping function applied to each matching value.
     */
    fun <I : Base, R : Base> mapStored(name: String, type: KClass<I>, transform: (I) -> R): OperationResult<T> {
        if (shouldSkip()) return copyWith(result)
        val (outcome, duration) = measureTimedValue {
            runCatching {
                val newParams = shallowCopyParams()
                val existing = newParams[name]
                if (existing != null) {
                    newParams[name] = existing.map {
                        if (type.java.isInstance(it)) transform(type.java.cast(it)) else it
                    }.toMutableList()
                }
                newParams
            }
        }
        val durationMs = duration.inWholeMilliseconds
        return outcome.fold(
            onSuccess = { newParams ->
                recordMetric(name, "mapStored", durationMs, true)
                copyWith(result, params = newParams, extensions = shallowCopyExtensions())
            },
            onFailure = { t ->
                if (t !is Exception) throw t
                logger.warn("FHIRMason | step='mapStored' | WARN: {}", t.message)
                recordMetric(name, "", durationMs, false)
                outcomes.add(warningOutcome(t))
                copyWith(result)
            }
        )
    }

    /** Java-friendly [Class] overload of [mapStored] — targets values under [name]. */
    fun <I : Base, R : Base> mapStored(name: String, type: Class<I>, transform: (I) -> R): OperationResult<T> =
        mapStored(name, type.kotlin, transform)

    /** Reified overload — no [KClass] argument needed at call sites. Targets values under [name]. */
    inline fun <reified I : Base, R : Base> mapStored(name: String, noinline transform: (I) -> R): OperationResult<T> =
        mapStored(name, I::class, transform)

    /**
     * Applies [transform] to every value across all stored keys that is an instance of [type],
     * replacing each matching value with the result of [transform]. Values that are not instances
     * of [type] are passed through unchanged. All keys are visited.
     *
     * If no stored value matches [type] the result is returned unchanged (no-op).
     * The pipeline head type [T] is always preserved.
     *
     * On exception inside [transform]: a WARNING-severity [OperationOutcome] is recorded and
     * the original parameter map is preserved unchanged (Pattern B — head-preserving).
     * Respects [ErrorStrategy.FAIL_FAST]: returns immediately when the pipeline has errors.
     *
     * @param type the [KClass] of values to match; non-matching values pass through.
     * @param transform element-wise mapping function applied to each matching value.
     */
    fun <I : Base, R : Base> mapStored(type: KClass<I>, transform: (I) -> R): OperationResult<T> {
        if (shouldSkip()) return copyWith(result)
        val (outcome, duration) = measureTimedValue {
            runCatching {
                val newParams = shallowCopyParams()
                newParams.replaceAll { _, values ->
                    values.map { if (type.java.isInstance(it)) transform(type.java.cast(it)) else it }.toMutableList()
                }
                newParams
            }
        }
        val durationMs = duration.inWholeMilliseconds
        return outcome.fold(
            onSuccess = { newParams ->
                recordMetric("mapStored", "mapStored", durationMs, true)
                copyWith(result, params = newParams, extensions = shallowCopyExtensions())
            },
            onFailure = { t ->
                if (t !is Exception) throw t
                logger.warn("FHIRMason | step='mapStored' | WARN: {}", t.message)
                recordMetric("mapStored", "", durationMs, false)
                outcomes.add(warningOutcome(t))
                copyWith(result)
            }
        )
    }

    /** Java-friendly [Class] overload of [mapStored] — targets all stored values of [type] across all keys. */
    fun <I : Base, R : Base> mapStored(type: Class<I>, transform: (I) -> R): OperationResult<T> =
        mapStored(type.kotlin, transform)

    /** Reified overload — no [KClass] argument needed at call sites. Targets all stored values of the type across all keys. */
    inline fun <reified I : Base, R : Base> mapStored(noinline transform: (I) -> R): OperationResult<T> =
        mapStored(I::class, transform)

    /**
     * Invokes [block] with a snapshot of the current parameter map for side-effects (logging,
     * debugging) and returns this [OperationResult] unchanged.
     */
    fun peek(block: (Map<String, List<Base>>) -> Unit): OperationResult<T> {
        block(getAllParameters())
        return this
    }

    // ── Conditional chaining ────────────────────────────────────────────

    /**
     * Runs [block] on this result only when [condition] is true, merges all accumulated
     * parameters and outcomes from the block result into the current map, and returns an
     * [OperationResult] with the original head type [T] preserved.
     *
     * When [condition] is false the result is returned unchanged.
     *
     * On exception inside [block]: records a WARNING-severity [OperationOutcome] and returns
     * the current result unchanged (Pattern B — head-preserving).
     */
    fun whenTrue(condition: Boolean, block: OperationResult<T>.() -> OperationResult<*>): OperationResult<T> {
        if (shouldSkip()) return copyWith(result)
        if (!condition) return copyWith(result)
        val subPipeline = OperationResult<T>(
            mutableMapOf(), result, mutableListOf(), errorStrategy, mutableMapOf(), timingEnabled, mutableListOf()
        )
        val (innerResult, duration) = measureTimedValue { runCatching { block(subPipeline) } }
        val durationMs = duration.inWholeMilliseconds
        return innerResult.fold(
            onSuccess = { inner ->
                val newParams = shallowCopyParams()
                inner.parameters.forEach { (key, values) ->
                    newParams.getOrPut(key) { mutableListOf() }.addAll(values)
                }
                outcomes.addAll(inner.outcomes)
                recordMetric("whenTrue", "", durationMs, true)
                copyWith(result, params = newParams, extensions = shallowCopyExtensions())
            },
            onFailure = { t ->
                if (t !is Exception) throw t
                logger.warn("FHIRMason | step='whenTrue' | WARN: {}", t.message)
                recordMetric("whenTrue", "", durationMs, false)
                outcomes.add(warningOutcome(t))
                copyWith(result)
            }
        )
    }

    /**
     * Runs [block] with the current head value only when it is non-null, merges the block's
     * accumulated parameters and outcomes, and returns [OperationResult<T>] with the original
     * head preserved.
     *
     * When the head is null the result is returned unchanged.
     *
     * On exception inside [block]: records a WARNING-severity [OperationOutcome] and returns
     * the current result unchanged (Pattern B — head-preserving).
     */
    fun ifPresent(block: (T) -> OperationResult<*>): OperationResult<T> {
        if (shouldSkip()) return copyWith(result)
        val current = result ?: return copyWith(result)
        val (innerResult, duration) = measureTimedValue { runCatching { block(current) } }
        val durationMs = duration.inWholeMilliseconds
        return innerResult.fold(
            onSuccess = { inner ->
                val newParams = shallowCopyParams()
                inner.parameters.forEach { (key, values) ->
                    newParams.getOrPut(key) { mutableListOf() }.addAll(values)
                }
                outcomes.addAll(inner.outcomes)
                recordMetric("ifPresent", "", durationMs, true)
                copyWith(result, params = newParams, extensions = shallowCopyExtensions())
            },
            onFailure = { t ->
                if (t !is Exception) throw t
                logger.warn("FHIRMason | step='ifPresent' | WARN: {}", t.message)
                recordMetric("ifPresent", "", durationMs, false)
                outcomes.add(warningOutcome(t))
                copyWith(result)
            }
        )
    }

    /**
     * Returns this result unchanged when [condition] is true.
     *
     * When [condition] is false, records a WARNING-severity [OperationOutcome] with the
     * supplied [message] as diagnostics and returns the result unchanged. Does NOT skip
     * the head or throw.
     *
     * Intended for precondition checks inline in a pipeline, e.g.:
     * ```
     * result.guardFalse(patient.active, "Patient is not active")
     *       .add { buildEncounter(patient) }
     * ```
     */
    fun guardFalse(condition: Boolean, message: String): OperationResult<T> {
        if (shouldSkip()) return copyWith(result)
        if (condition) return copyWith(result)
        val outcome = OperationOutcome().apply {
            addIssue().apply {
                severity = OperationOutcome.IssueSeverity.WARNING
                code = OperationOutcome.IssueType.BUSINESSRULE
                diagnostics = message
            }
        }
        outcomes.add(outcome)
        return copyWith(result)
    }

    // ── FHIRPath conditional chaining ────────────────────────────────────

    /**
     * Runs [block] on this result only when [expression] evaluates to `true` against the
     * current head value (using [FhirPathHelper.matches] semantics), merges all accumulated
     * parameters and outcomes from the block result into the current map, and returns an
     * [OperationResult] with the original head type [T] preserved.
     *
     * When the head is `null` or not a FHIR [Base] resource, the block is skipped silently
     * and the result is returned unchanged.
     *
     * [expression] is evaluated against the head resource directly, so write it as a relative
     * path (e.g. `"active = true"`, not `"Patient.active = true"`).
     *
     * On exception inside [block] or during expression evaluation: records a WARNING-severity
     * [OperationOutcome] and returns the current result unchanged (Pattern B — head-preserving).
     */
    fun whenPath(expression: String, block: OperationResult<T>.() -> OperationResult<*>): OperationResult<T> {
        if (shouldSkip()) return copyWith(result)
        val head = result
        if (head !is Base) return copyWith(result)
        val subPipeline = OperationResult<T>(
            mutableMapOf(), result, mutableListOf(), errorStrategy, mutableMapOf(), timingEnabled, mutableListOf()
        )
        val (innerResult, duration) = measureTimedValue {
            runCatching {
                if (!FhirPathHelper.matches(head, expression)) null
                else block(subPipeline)
            }
        }
        val durationMs = duration.inWholeMilliseconds
        return innerResult.fold(
            onSuccess = { inner ->
                if (inner != null) {
                    val newParams = shallowCopyParams()
                    inner.parameters.forEach { (key, values) ->
                        newParams.getOrPut(key) { mutableListOf() }.addAll(values)
                    }
                    outcomes.addAll(inner.outcomes)
                    recordMetric("whenPath", "", durationMs, true)
                    copyWith(result, params = newParams, extensions = shallowCopyExtensions())
                } else {
                    copyWith(result)  // expression evaluated to false
                }
            },
            onFailure = { t ->
                if (t !is Exception) throw t
                logger.warn("FHIRMason | step='whenPath' | WARN: {}", t.message)
                recordMetric("whenPath", "", durationMs, false)
                outcomes.add(warningOutcome(t))
                copyWith(result)
            }
        )
    }

    /** [FhirPath] overload of [whenPath] — builds the expression and delegates. */
    fun whenPath(expression: FhirPath, block: OperationResult<T>.() -> OperationResult<*>): OperationResult<T> =
        whenPath(expression.build(), block)

    /**
     * Returns this result unchanged when [expression] evaluates to `true` against the current
     * head value.
     *
     * When [expression] evaluates to `false`, records a WARNING-severity [OperationOutcome]
     * with [message] as diagnostics. When the head is `null` or not a FHIR [Base] resource,
     * also records a WARNING indicating that the expression could not be evaluated.
     *
     * [expression] is evaluated against the head resource directly, so write it as a relative
     * path (e.g. `"active = true"`, not `"Patient.active = true"`).
     *
     * On expression evaluation exception, records a WARNING and returns unchanged
     * (Pattern B — head-preserving).
     */
    fun guardPath(expression: String, message: String): OperationResult<T> {
        if (shouldSkip()) return copyWith(result)
        val head = result
        if (head !is Base) {
            val outcome = OperationOutcome().apply {
                addIssue().apply {
                    severity = OperationOutcome.IssueSeverity.WARNING
                    code = OperationOutcome.IssueType.BUSINESSRULE
                    diagnostics = "guardPath: head value is not a FHIR resource; expression '$expression' could not be evaluated"
                }
            }
            outcomes.add(outcome)
            return copyWith(result)
        }
        return try {
            if (FhirPathHelper.matches(head, expression)) {
                copyWith(result)
            } else {
                val outcome = OperationOutcome().apply {
                    addIssue().apply {
                        severity = OperationOutcome.IssueSeverity.WARNING
                        code = OperationOutcome.IssueType.BUSINESSRULE
                        diagnostics = message
                    }
                }
                outcomes.add(outcome)
                copyWith(result)
            }
        } catch (e: Exception) {
            logger.warn("FHIRMason | step='guardPath' | WARN: {}", e.message)
            outcomes.add(warningOutcome(e))
            copyWith(result)
        }
    }

    /** [FhirPath] overload of [guardPath] — builds the expression and delegates. */
    fun guardPath(expression: FhirPath, message: String): OperationResult<T> =
        guardPath(expression.build(), message)

    // ── FHIRPath extraction ───────────────────────────────────────────────

    /**
     * Evaluates [expression] against the current head resource, promotes the first matching
     * result of type [type] to the new head, and stores it under [name] (or [expression] when
     * [name] is `null`).
     *
     * Error handling follows Pattern A: if the head is `null` or not a [Base], if the
     * expression matches no values of [type], or if the expression is malformed, an
     * ERROR-severity [OperationOutcome] is recorded and the head is skipped.
     *
     * [expression] is evaluated against the head resource directly, so write it as a relative
     * path (e.g. `"name.where(use='official').first()"`, not `"Patient.name…"`).
     *
     * Use the reified overload to avoid passing [type] explicitly:
     * `result.selectByPath<HumanName>("name.first()")`
     */
    @JvmOverloads
    fun <R : Base> selectByPath(type: KClass<R>, expression: String, name: String? = null): OperationResult<R> {
        val stepName = name ?: expression
        return runBuilderStep(stepName) {
            val head = result
            require(head is Base) { "selectByPath: head value is not a FHIR resource" }
            val (match, duration) = measureTimedValue {
                FhirPathHelper.evaluateFirst(head as Base, expression, type.java)
                    ?: error("selectByPath: expression '$expression' matched no ${type.simpleName} values")
            }
            storeAndCopy(name, match, duration.inWholeMilliseconds)
        }
    }

    /** [FhirPath] overload of [selectByPath] — builds the expression and delegates. */
    @JvmOverloads
    fun <R : Base> selectByPath(type: KClass<R>, expression: FhirPath, name: String? = null): OperationResult<R> =
        selectByPath(type, expression.build(), name)

    /** Reified overload of [selectByPath] — no [KClass] argument needed at call sites. */
    inline fun <reified R : Base> selectByPath(expression: String, name: String? = null): OperationResult<R> =
        selectByPath(R::class, expression, name)

    /** [FhirPath] reified overload of [selectByPath]. */
    inline fun <reified R : Base> selectByPath(expression: FhirPath, name: String? = null): OperationResult<R> =
        selectByPath(R::class, expression.build(), name)

    /** Returns the first value stored under [name], or `null` if the key is absent or empty. */
    fun takeFirst(name: String): Base? = parameters[name]?.firstOrNull()

    /**
     * Returns the first value stored under [name] that is an instance of [type], or `null`.
     * Prefer the inline reified overload [takeFirstTyped] where the type can be inferred.
     */
    fun <R : Base> takeFirstTyped(name: String, type: KClass<R>): R? =
        parameters[name]?.filterIsInstance(type.java)?.firstOrNull()

    /** Reified overload — no [KClass] argument needed at call sites. */
    inline fun <reified R : Base> takeFirstTyped(name: String): R? = takeFirstTyped(name, R::class)

    /**
     * Extracts the first value of type [R] stored under [name] and sets it as the pipeline head.
     *
     * Follows Pattern A: when no value of [R] exists under [name], records an ERROR-severity
     * [OperationOutcome] and returns a skipped (null-head) result. The pipeline head type changes
     * from [T] to [R] on success, and the active [ErrorStrategy] is respected (FAIL_FAST will skip
     * all subsequent steps, ACCUMULATE will continue).
     *
     * @param name the parameter-map key to look up.
     * @param type the expected [KClass] of the stored resource.
     */
    fun <R : Base> extractParam(name: String, type: KClass<R>): OperationResult<R> =
        runBuilderStep(name) {
            val value = parameters[name]?.filterIsInstance(type.java)?.firstOrNull()
                ?: error("No value of type '${type.simpleName}' found under key '$name'")
            copyWith(value)
        }

    /** Reified overload — no [KClass] argument needed at call sites. */
    inline fun <reified R : Base> extractParam(name: String): OperationResult<R> =
        extractParam(name, R::class)

    /**
     * Java-friendly overload of [extractParam] — accepts a [Class] instead of a [KClass].
     */
    fun <R : Base> extractParam(name: String, type: Class<R>): OperationResult<R> =
        extractParam(name, type.kotlin)

    /**
     * Extracts all values of type [R] stored under [name] and sets the list as the pipeline head.
     * Returns an empty list (not an error) if no values match.
     */
    fun <R : Base> extractParamList(name: String, type: KClass<R>): OperationResult<List<R>> {
        val values = parameters[name]
            ?.filterIsInstance(type.java)
            ?: emptyList()
        return copyWith(values)
    }

    /** Reified overload — no [KClass] argument needed at call sites. */
    inline fun <reified R : Base> extractParamList(name: String): OperationResult<List<R>> =
        extractParamList(name, R::class)

    /**
     * Java-friendly overload of [extractParamList] — accepts a [Class] instead of a [KClass].
     */
    fun <R : Base> extractParamList(name: String, type: Class<R>): OperationResult<List<R>> =
        extractParamList(name, type.kotlin)

    // Reference linking

    /**
     * Wires FHIR references between accumulated resources using explicit [rules].
     *
     * For each rule:
     * - There must be **at most one** resource of [ReferenceLinkRule.targetType]; if multiple
     *   are found an [IllegalArgumentException] is thrown because the wiring would be ambiguous.
     * - Every resource of [ReferenceLinkRule.sourceType] has its reference set to the single target.
     * - Self-pairs (source === target) are skipped.
     *
     * Resources are deep-copied before modification so the original [OperationResult] and its
     * accumulated resources remain unchanged.
     *
     * @throws IllegalArgumentException if more than one target resource exists for any rule.
     */
    fun linkReferences(vararg rules: ReferenceLinkRule<*, *>): OperationResult<T> {
        val copiedParams = deepCopyParameters(parameters)
        applyReferenceLinkRules(copiedParams, rules)
        return copyWith(result, params = copiedParams)
    }

    /**
     * Automatically wires FHIR references between accumulated resources by introspecting
     * each resource's HAPI child properties.
     *
     * Algorithm:
     * 1. Build an index of ID-bearing resources keyed by `fhirType().lowercase()`.
     * 2. For every accumulated resource, iterate its [Base.children] properties.
     * 3. For each unset property whose typeCode starts with `"Reference("`, parse the
     *    allowed target types from the typeCode (e.g. `"Reference(Patient|Group)"`).
     * 4. If exactly one matching resource exists in the index for one of those types,
     *    assign a `Reference("ResourceType/id")` via [Base.setProperty].
     *
     * Only unset reference properties are touched; already-populated references are left
     * unchanged. Ambiguous cases (multiple candidates for the same reference property)
     * are silently skipped to avoid incorrect wiring.
     *
     * Resources are deep-copied before modification so the original [OperationResult] and its
     * accumulated resources remain unchanged.
     */
    fun linkReferences(): OperationResult<T> {
        val copiedParams = deepCopyParameters(parameters)
        autoLinkReferences(copiedParams)
        return copyWith(result, params = copiedParams)
    }

    // Core methods

    /**
     * Serialises the accumulated resources to a FHIR [Parameters] resource.
     *
     * Keys that contain a dot are treated as hierarchical paths, matching the composite
     * `"parent.child"` keys produced by [fromParameters] when it flattens nested
     * [Parameters.ParametersParameterComponent.part] entries.  This ensures a full
     * round-trip: `fromParameters(p).toParameters()` reconstructs the original nested
     * structure rather than emitting flat `"parent.child"` parameter names.
     *
     * Keys without dots are emitted as ordinary top-level parameters (one entry per value),
     * exactly as before.
     */
    fun toParameters(): Parameters = ParameterMapSerializer.unflatten(parameters, extensions)

    private fun toBundleEntry(resource: Base): Bundle.BundleEntryComponent =
        Bundle.BundleEntryComponent().apply {
            if (resource is Resource) setResource(resource)
        }

    /**
     * Serialises all accumulated [Resource] values to a FHIR [Bundle] of the given [type].
     *
     * Only [Resource] subtypes from the parameter map are written as bundle entries.
     * Primitive and complex FHIR [Type] values added via `addString`, `addCoding`, `addPart`,
     * etc. are not representable in a Bundle entry and are silently omitted.
     * Use [toParameters] to serialise the full accumulated state including non-Resource values.
     *
     * Type-specific behaviour:
     * - **TRANSACTION / BATCH**: each entry gets a `request` component; PUT with
     *   `"ResourceType/id"` when the resource has an id, POST with `"ResourceType"` otherwise.
     * - **SEARCHSET**: each entry gets `search.mode = MATCH`; `bundle.total` is set to the
     *   number of entries.
     * - All other types (e.g. COLLECTION): plain entries with no request/search metadata.
     *
     * @param configBlock optional lambda applied to each [Bundle.BundleEntryComponent] after the
     *   automatic type-specific logic, useful for setting `fullUrl` or overriding search mode.
     */
    @JvmOverloads
    fun toBundle(
        type: Bundle.BundleType,
        configBlock: ((Bundle.BundleEntryComponent) -> Unit)? = null
    ): Bundle = Bundle().apply {
        this.type = type
        val allResources = parameters.values.flatten().filterIsInstance<Resource>()
        allResources.forEach { resource ->
            val entry = toBundleEntry(resource)
            when (type) {
                Bundle.BundleType.TRANSACTION, Bundle.BundleType.BATCH -> {
                    entry.request = Bundle.BundleEntryRequestComponent().apply {
                        if (resource.hasId()) {
                            method = Bundle.HTTPVerb.PUT
                            url = "${resource.resourceType}/${resource.idPart}"
                        } else {
                            method = Bundle.HTTPVerb.POST
                            url = resource.resourceType.toString()
                        }
                    }
                }
                Bundle.BundleType.SEARCHSET -> {
                    entry.search = Bundle.BundleEntrySearchComponent().apply {
                        mode = Bundle.SearchEntryMode.MATCH
                    }
                }
                else -> {}
            }
            configBlock?.invoke(entry)
            addEntry(entry)
        }
        if (type == Bundle.BundleType.SEARCHSET) {
            total = allResources.size
        }
    }

    /**
     * Convenience alias for `toBundle(Bundle.BundleType.COLLECTION, configBlock)`.
     *
     * Only [Resource] values are included; non-Resource accumulated values are silently omitted.
     * Use [toParameters] to serialise the full state.
     *
     * @param configBlock optional lambda applied to each entry, e.g. to set `fullUrl`.
     */
    @JvmOverloads
    fun toCollectionBundle(
        configBlock: ((Bundle.BundleEntryComponent) -> Unit)? = null
    ): Bundle = toBundle(Bundle.BundleType.COLLECTION, configBlock)

    /**
     * Convenience alias for `toBundle(Bundle.BundleType.TRANSACTION, configBlock)`.
     *
     * Only [Resource] values are included; non-Resource accumulated values are silently omitted.
     * Use [toParameters] to serialise the full state.
     *
     * @param configBlock optional lambda applied to each entry after the automatic PUT/POST
     *   inference, e.g. to set `fullUrl` for cross-entry reference resolution.
     */
    @JvmOverloads
    fun toTransactionBundle(
        configBlock: ((Bundle.BundleEntryComponent) -> Unit)? = null
    ): Bundle = toBundle(Bundle.BundleType.TRANSACTION, configBlock)

    /**
     * Convenience alias for `toBundle(Bundle.BundleType.BATCH, configBlock)`.
     *
     * Only [Resource] values are included; non-Resource accumulated values are silently omitted.
     * Use [toParameters] to serialise the full state.
     *
     * @param configBlock optional lambda applied to each entry after the automatic PUT/POST
     *   inference, e.g. to set `fullUrl`.
     */
    @JvmOverloads
    fun toBatchBundle(
        configBlock: ((Bundle.BundleEntryComponent) -> Unit)? = null
    ): Bundle = toBundle(Bundle.BundleType.BATCH, configBlock)

    /**
     * Returns the most recently added value, typed as [T].
     * No cast is needed because [T] is tracked through the pipeline generics.
     * For list-producing steps use the [getResultList] extension.
     */
    fun getResult(): T = result ?: throw IllegalStateException("No result set")

    // Private helpers

    private fun addToParameters(values: Collection<Base>, name: String?) {
        values.forEach { value ->
            val key = name ?: value.fhirType().lowercase()
            parameters.getOrPut(key) { mutableListOf() }.add(value)
        }
    }

    // Factory methods

    companion object {

        private val logger = LoggerFactory.getLogger(OperationResult::class.java)

        internal fun fromMap(
            params: Map<String, List<Base>>,
            outcomes: List<OperationOutcome> = emptyList(),
            failedTasks: Map<String, OperationOutcome> = emptyMap()
        ): OperationResult<Base> {
            val mutable = params.mapValues { it.value.toMutableList() }.toMutableMap()
            return OperationResult(mutable, null, outcomes.toMutableList(), ErrorStrategy.FAIL_FAST, failedTasks.toMutableMap())
        }

        /**
         * Creates an [OperationResult] with [value] as both the pipeline head and the first
         * parameter-map entry, stored under [name] (or [value]'s [Base.fhirType] lowercase when
         * [name] is `null`).
         *
         * The pipeline starts with [ErrorStrategy.FAIL_FAST]; call [useErrorStrategy] to change it.
         */
        @JvmStatic
        @JvmOverloads
        fun <T : Base> of(value: T, name: String? = null): OperationResult<T> {
            val key = name ?: value.fhirType().lowercase()
            val params = mutableMapOf<String, MutableList<Base>>()
            params.getOrPut(key) { mutableListOf() }.add(value)
            return OperationResult(params, value, mutableListOf(), ErrorStrategy.FAIL_FAST, mutableMapOf())
        }

        /**
         * Creates an [OperationResult] with [values] as both the pipeline head (`List<T>`) and
         * the initial parameter-map entries. Each element is stored under [name] (or the element's
         * [Base.fhirType] lowercase when [name] is `null`).
         *
         * Accepts any [Collection] (e.g. [List], [Set], [LinkedHashSet]); the pipeline head is
         * always stored as a [List].
         *
         * The pipeline starts with [ErrorStrategy.FAIL_FAST]; call [useErrorStrategy] to change it.
         */
        @JvmStatic
        @JvmOverloads
        fun <T : Base> of(values: Collection<T>, name: String? = null): OperationResult<List<T>> {
            val list = values.toList()
            val params = mutableMapOf<String, MutableList<Base>>()
            val instance = OperationResult(params, list, mutableListOf(), ErrorStrategy.FAIL_FAST, mutableMapOf())
            instance.addToParameters(list, name)
            return instance
        }

        /**
         * Builds an [OperationResult] from an existing FHIR [Parameters] resource.
         *
         * Each top-level [Parameters.parameter] entry is stored using its [Parameters.ParametersParameterComponent.name]
         * as the map key. Both resource parameters and primitive-type parameters (e.g. [StringType],
         * [BooleanType]) are supported.
         *
         * Parameters that contain nested [Parameters.ParametersParameterComponent.part] entries (but no
         * direct value or resource) are flattened recursively: each part is stored under the composite
         * key `"<parentName>.<partName>"`, preserving the hierarchy in the key name.
         *
         * The returned result has no "current" typed head ([getResult] will throw); use
         * [fromParametersTyped] when a typed head is required.
         */
        @JvmStatic
        fun fromParameters(parameters: Parameters): OperationResult<Base> {
            val (params, exts) = ParameterMapSerializer.flatten(parameters)
            return OperationResult(params, null, mutableListOf(), ErrorStrategy.FAIL_FAST, mutableMapOf(), extensions = exts)
        }

        /**
         * Like [fromParameters] but also sets the typed pipeline head to the first value stored
         * under [primaryKey] that is an instance of [type].
         *
         * When no value of [type] is found under [primaryKey], returns a pipeline with
         * [hasErrors] `== true` and a null head (consistent with Pattern A mid-pipeline steps).
         * Call [useErrorStrategy] on the returned result to change the strategy if needed.
         *
         * @param primaryKey the parameter-map key whose value should become the pipeline head.
         * @param type the expected [KClass] of the primary resource.
         */
        @JvmStatic
        fun <T : Base> fromParametersTyped(
            parameters: Parameters,
            primaryKey: String,
            type: KClass<T>
        ): OperationResult<T> {
            val (params, exts) = ParameterMapSerializer.flatten(parameters)
            val primary = params[primaryKey]?.filterIsInstance(type.java)?.firstOrNull()
            return if (primary != null) {
                OperationResult(params, primary, mutableListOf(), ErrorStrategy.FAIL_FAST, mutableMapOf(), extensions = exts)
            } else {
                val outcome = messageOutcome("No value of type '${type.simpleName}' found under key '$primaryKey'")
                OperationResult(params, null, mutableListOf(outcome), ErrorStrategy.FAIL_FAST, mutableMapOf(), extensions = exts)
            }
        }

        /** Reified overload of [fromParametersTyped] — no [KClass] argument needed at call sites. */
        inline fun <reified T : Base> fromParametersTyped(
            parameters: Parameters,
            primaryKey: String
        ): OperationResult<T> = fromParametersTyped(parameters, primaryKey, T::class)

        /**
         * Java-friendly overload of [fromParametersTyped] — accepts a [Class] instead of a [KClass]
         * so Java callers can write `fromParametersTyped(params, "patient", Patient.class)`.
         *
         * When no value of [type] is found under [primaryKey], returns a pipeline with
         * [hasErrors] `== true` and a null head.
         */
        @JvmStatic
        fun <T : Base> fromParametersTyped(
            parameters: Parameters,
            primaryKey: String,
            type: Class<T>
        ): OperationResult<T> = fromParametersTyped(parameters, primaryKey, type.kotlin)

        /**
         * Builds an [OperationResult] from a FHIR [Bundle].
         *
         * Each entry that carries a resource is stored using `resource.fhirType().lowercase()` as
         * the key. Entries without a resource (e.g. bare transaction-response entries) are silently
         * skipped. Resources of the same FHIR type accumulate under the same key.
         *
         * The returned result has no typed head; use [fromParametersTyped] pattern via
         * [fromBundle] with a custom [keyStrategy] or cast after the fact if a head is needed.
         *
         * @see fromBundle overload with [keyStrategy] for custom key extraction.
         */
        @JvmStatic
        fun fromBundle(bundle: Bundle): OperationResult<Base> =
            fromBundle(bundle) { entry -> entry.resource.fhirType().lowercase() }

        /**
         * Builds an [OperationResult] from a FHIR [Bundle] with a custom key extractor.
         *
         * [keyStrategy] receives each [Bundle.BundleEntryComponent] that has a resource and must
         * return the map key for that resource.  Returning an empty string causes the entry to be
         * skipped, which is useful when the caller wants to filter entries conditionally.
         *
         * Example — key by `fullUrl`:
         * ```kotlin
         * OperationResult.fromBundle(bundle) { entry -> entry.fullUrl ?: entry.resource.fhirType() }
         * ```
         */
        @JvmStatic
        fun fromBundle(
            bundle: Bundle,
            keyStrategy: (Bundle.BundleEntryComponent) -> String
        ): OperationResult<Base> {
            val params = mutableMapOf<String, MutableList<Base>>()
            bundle.entry
                .filter { it.hasResource() }
                .forEach { entry ->
                    val key = keyStrategy(entry)
                    if (key.isNotEmpty()) {
                        params.getOrPut(key) { mutableListOf() }.add(entry.resource)
                    }
                }
            return OperationResult(params, null, mutableListOf(), ErrorStrategy.FAIL_FAST, mutableMapOf())
        }

        /**
         * Builds an [OperationResult] from a FHIR [Bundle] and sets the typed pipeline head to
         * the first resource stored under [primaryKey] that is an instance of [type].
         *
         * Resources are keyed by `resource.fhirType().lowercase()` (same as the default
         * [fromBundle] overload).  All resources are loaded into the parameter map; only the
         * head is narrowed to [T].
         *
         * When no resource of [type] is found under [primaryKey], returns a pipeline with
         * [hasErrors] `== true` and a null head (consistent with Pattern A mid-pipeline steps).
         *
         * @param primaryKey the parameter-map key whose value should become the pipeline head.
         * @param type the expected [KClass] of the primary resource.
         */
        @JvmStatic
        fun <T : Resource> fromBundleTyped(
            bundle: Bundle,
            primaryKey: String,
            type: KClass<T>
        ): OperationResult<T> {
            val params = mutableMapOf<String, MutableList<Base>>()
            bundle.entry
                .filter { it.hasResource() }
                .forEach { entry ->
                    val key = entry.resource.fhirType().lowercase()
                    params.getOrPut(key) { mutableListOf() }.add(entry.resource)
                }
            val primary = params[primaryKey]?.filterIsInstance(type.java)?.firstOrNull()
            return if (primary != null) {
                OperationResult(params, primary, mutableListOf(), ErrorStrategy.FAIL_FAST, mutableMapOf())
            } else {
                val outcome = messageOutcome("No value of type '${type.simpleName}' found under key '$primaryKey'")
                OperationResult(params, null, mutableListOf(outcome), ErrorStrategy.FAIL_FAST, mutableMapOf())
            }
        }

        /**
         * Java-friendly overload of [fromBundleTyped] — accepts [Class] instead of [KClass].
         *
         * When no resource of [type] is found under [primaryKey], returns a pipeline with
         * [hasErrors] `== true` and a null head.
         */
        @JvmStatic
        fun <T : Resource> fromBundleTyped(
            bundle: Bundle,
            primaryKey: String,
            type: Class<T>
        ): OperationResult<T> = fromBundleTyped(bundle, primaryKey, type.kotlin)

        /** Reified overload of [fromBundleTyped] — no [KClass] argument needed at call sites. */
        inline fun <reified T : Resource> fromBundleTyped(
            bundle: Bundle,
            primaryKey: String
        ): OperationResult<T> = fromBundleTyped(bundle, primaryKey, T::class)

        /**
         * Creates an empty [OperationResult] with no parameters and no pipeline head.
         *
         * Useful for:
         * - Starting a pipeline that conditionally adds parameters
         * - Building a [Parameters] resource that may legitimately have zero entries
         * - Serving as a merge target
         *
         * [getResult] will throw [IllegalStateException] until a value is added via [add] or similar.
         * [toParameters] returns a valid empty [Parameters] resource.
         *
         * The pipeline starts with [ErrorStrategy.FAIL_FAST]; call [useErrorStrategy] to change it.
         */
        @JvmStatic
        fun empty(): OperationResult<Base> =
            OperationResult(
                mutableMapOf(),
                null,
                mutableListOf(),
                ErrorStrategy.FAIL_FAST,
                mutableMapOf()
            )

    }
}

/**
 * Type-safe accessor for pipeline stages that produced a list (i.e. after [OperationResult.addAll]
 * or [OperationResult.addAllUsing]).  Because [T] is already `List<R>`, [OperationResult.getResult]
 * returns the correctly typed list — this extension is a readable alias for that call.
 *
 * ```kotlin
 * val appointments: List<Appointment> = OperationResult.of(patient())
 *     .addAll { listOf(Appointment(), Appointment()) }
 *     .getResultList()
 * ```
 */
fun <R : Base> OperationResult<List<R>>.getResultList(): List<R> = getResult()
