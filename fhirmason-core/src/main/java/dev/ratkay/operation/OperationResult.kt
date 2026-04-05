package dev.ratkay.operation

import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException
import org.hl7.fhir.instance.model.api.IBaseHasExtensions
import org.hl7.fhir.r4.model.Base
import org.hl7.fhir.r4.model.BooleanType
import org.hl7.fhir.r4.model.Extension
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.CanonicalType
import org.hl7.fhir.r4.model.CodeType
import org.hl7.fhir.r4.model.CodeableConcept
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.DateTimeType
import org.hl7.fhir.r4.model.DateType
import org.hl7.fhir.r4.model.DecimalType
import org.hl7.fhir.r4.model.Identifier
import org.hl7.fhir.r4.model.InstantType
import org.hl7.fhir.r4.model.IntegerType
import org.hl7.fhir.r4.model.OperationOutcome
import org.hl7.fhir.r4.model.Parameters
import org.hl7.fhir.r4.model.Period
import org.hl7.fhir.r4.model.Quantity
import org.hl7.fhir.r4.model.Reference
import org.hl7.fhir.r4.model.Resource
import org.hl7.fhir.r4.model.StringType
import org.hl7.fhir.r4.model.TimeType
import org.hl7.fhir.r4.model.Type
import org.hl7.fhir.r4.model.UriType
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.Year
import java.time.YearMonth
import java.time.ZonedDateTime
import java.util.Calendar
import java.util.Date
import java.util.IdentityHashMap
import kotlin.reflect.KClass

/**
 * A typed, immutable pipeline builder that accumulates FHIR resources keyed by name and
 * carries the type of the most recently added resource through the generic parameter [T].
 *
 * The internal parameter map is always `Map<String, List<Base>>`; [T] only tracks the
 * "head" of the pipeline so that [getResult] returns the correct type without casting.
 * When the last step produced a list, [T] becomes `List<R>` and the result can be
 * retrieved via [getResult] or via the [getResultList] extension.
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
) {

    // Error state

    fun hasErrors(): Boolean = outcomes.isNotEmpty()

    fun isSuccessful(): Boolean = outcomes.isEmpty()

    fun getOutcomes(): List<OperationOutcome> = outcomes.toList()

    fun getFailedTasks(): Map<String, OperationOutcome> = failedTasks.toMap()

    fun toOperationOutcome(): OperationOutcome = OperationOutcome().apply {
        this@OperationResult.outcomes.forEach { oo ->
            oo.issue.forEach { issue ->
                addIssue().apply {
                    severity = issue.severity
                    code = issue.code
                    diagnostics = issue.diagnostics
                }
            }
        }
    }

    fun throwIfErrors(): OperationResult<T> {
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
        extensions: MutableMap<String, MutableMap<Base, List<Extension>>> = this.extensions
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
    fun timed(): OperationResult<T> = copyWith(result, timingEnabled = true)

    /** Returns a snapshot of [StepMetrics] collected so far. Empty when [timed] was not called. */
    fun getMetrics(): List<StepMetrics> = metrics.toList()

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

    private fun <R> runBuilderStep(name: String?, block: (Long) -> OperationResult<R>): OperationResult<R> {
        if (shouldSkip()) return skippedResult()
        val start = System.currentTimeMillis()
        return try {
            block(start)
        } catch (e: Exception) {
            val durationMs = System.currentTimeMillis() - start
            recordMetric(name ?: "unknown", "", durationMs, false)
            outcomes.add(when (e) {
                is BaseServerResponseException -> e.toOperationOutcome()
                else -> e.toOperationOutcome()
            })
            skippedResult()
        }
    }

    private fun <R : Base> storeAndCopy(name: String?, value: R, start: Long): OperationResult<R> {
        val durationMs = System.currentTimeMillis() - start
        val key = name ?: value.fhirType().lowercase()
        parameters.getOrPut(key) { mutableListOf() }.add(value)
        recordMetric(key, value.fhirType(), durationMs, true)
        logStep(key, value.fhirType(), durationMs)
        return copyWith(value)
    }

    /**
     * Shared try/catch shell for all primitive-value convenience methods.
     *
     * On success: stores the FHIR value, records a metric, and logs the step.
     * On exception: logs a WARN, records a failed metric, and adds a WARNING-severity
     * [OperationOutcome] — preserving the rich embedded outcome from a
     * [BaseServerResponseException] rather than discarding it in favour of a plain message.
     * The pipeline head type [T] is never changed; [shouldSkip] is checked first.
     */
    private fun runPrimitiveStep(name: String, build: () -> Base): OperationResult<T> {
        if (shouldSkip()) return copyWith(result)
        val start = System.currentTimeMillis()
        return try {
            val fhirValue = build()
            parameters.getOrPut(name) { mutableListOf() }.add(fhirValue)
            val durationMs = System.currentTimeMillis() - start
            recordMetric(name, fhirValue.fhirType(), durationMs, true)
            logStep(name, fhirValue.fhirType(), durationMs)
            copyWith(result)
        } catch (e: Exception) {
            val durationMs = System.currentTimeMillis() - start
            logger.warn("FHIRMason | step='{}' | WARN: {}", name, e.message)
            recordMetric(name, "", durationMs, false)
            val outcome = when (e) {
                is BaseServerResponseException -> e.toOperationOutcome()
                else -> e.toOperationOutcome()
            }
            outcome.issue.forEach { it.severity = OperationOutcome.IssueSeverity.WARNING }
            outcomes.add(outcome)
            copyWith(result)
        }
    }

    private fun <R : Base> storeListAndCopy(name: String?, values: List<R>, start: Long): OperationResult<List<R>> {
        val durationMs = System.currentTimeMillis() - start
        addToParameters(values, name)
        val key = name ?: values.firstOrNull()?.fhirType()?.lowercase() ?: "list"
        val typeDesc = "${values.firstOrNull()?.fhirType() ?: "Empty"}[${values.size}]"
        recordMetric(key, typeDesc, durationMs, true)
        logStep(key, typeDesc, durationMs)
        return copyWith(values)
    }

    // Builder methods — filter all accumulated parameters by type and extension URLs

    /**
     * Collects all values across every accumulated parameter, keeps only instances of [type],
     * and — if [extUrls] is non-empty — further filters by extension-URL presence.
     *
     * When [matchAll] is `true` (AND): a resource must carry **every** supplied URL.
     * When [matchAll] is `false` (OR): a resource must carry **at least one** of the URLs.
     * When [extUrls] is empty, all instances of [type] are returned regardless of [matchAll].
     */
    private fun <I : Base> collectByExtension(
        type: KClass<I>,
        extUrls: Array<out String>,
        matchAll: Boolean
    ): List<I> {
        val allOfType = parameters.values.flatten().filterIsInstance(type.java)
        return if (extUrls.isEmpty()) allOfType
        else allOfType.filter { resource ->
            resource is IBaseHasExtensions && if (matchAll) {
                extUrls.all { url -> FhirExtensionHelper.hasExtension(resource, url) }
            } else {
                extUrls.any { url -> FhirExtensionHelper.hasExtension(resource, url) }
            }
        }
    }

    /**
     * Searches **all** accumulated parameters for instances of [type] that have an [Extension] at
     * [url] whose value is an instance of [valueType] and satisfies [predicate].
     *
     * Resources that do not implement [IBaseHasExtensions], have no extension at [url], or whose
     * extension value is not an instance of [valueType] are silently excluded.
     * When the extension appears multiple times at [url], the resource passes if **any** value
     * satisfies [predicate].
     */
    private fun <I : Base, V : Type> collectByExtensionAndValueType(
        type: KClass<I>,
        url: String,
        valueType: KClass<V>,
        predicate: (V) -> Boolean
    ): List<I> =
        parameters.values.flatten()
            .filterIsInstance(type.java)
            .filter { resource ->
                resource is IBaseHasExtensions &&
                    FhirExtensionHelper.getAllValuesAs(resource, url, valueType.java).any(predicate)
            }

    /**
     * Searches **all** accumulated parameters for instances of [type] that carry **every** one of
     * the supplied [extUrls] (AND semantics), passes the typed list to [builder], and stores the
     * single result under [type]'s simple name (lowercase).
     *
     * Error handling follows Pattern A: exceptions record an ERROR-severity [OperationOutcome]
     * and skip the head value.
     *
     * Use [addFromHavingAnyExtension] when OR semantics are needed instead.
     */
    fun <I : Base, R : Base> addFromHavingAllExtensions(
        type: KClass<I>,
        vararg extUrls: String,
        builder: (List<I>) -> R
    ): OperationResult<R> {
        val stepName = type.java.simpleName.lowercase()
        return runBuilderStep(stepName) { start ->
            storeAndCopy(stepName, builder(collectByExtension(type, extUrls, matchAll = true)), start)
        }
    }

    /**
     * Like [addFromHavingAllExtensions] but stores the result under the explicit [name].
     * Placing [name] before the [extUrls] vararg keeps the Java call-site clean.
     */
    fun <I : Base, R : Base> addFromHavingAllExtensions(
        name: String,
        type: KClass<I>,
        vararg extUrls: String,
        builder: (List<I>) -> R
    ): OperationResult<R> =
        runBuilderStep(name) { start ->
            storeAndCopy(name, builder(collectByExtension(type, extUrls, matchAll = true)), start)
        }

    /**
     * Searches **all** accumulated parameters for instances of [type] that carry **at least one**
     * of the supplied [extUrls] (OR semantics), passes the typed list to [builder], and stores the
     * single result under [type]'s simple name (lowercase).
     *
     * Use [addFromHavingAllExtensions] when AND semantics are needed instead.
     */
    fun <I : Base, R : Base> addFromHavingAnyExtension(
        type: KClass<I>,
        vararg extUrls: String,
        builder: (List<I>) -> R
    ): OperationResult<R> {
        val stepName = type.java.simpleName.lowercase()
        return runBuilderStep(stepName) { start ->
            storeAndCopy(stepName, builder(collectByExtension(type, extUrls, matchAll = false)), start)
        }
    }

    /**
     * Like [addFromHavingAnyExtension] but stores the result under the explicit [name].
     */
    fun <I : Base, R : Base> addFromHavingAnyExtension(
        name: String,
        type: KClass<I>,
        vararg extUrls: String,
        builder: (List<I>) -> R
    ): OperationResult<R> =
        runBuilderStep(name) { start ->
            storeAndCopy(name, builder(collectByExtension(type, extUrls, matchAll = false)), start)
        }

    /**
     * Like [addFromHavingAllExtensions] but [builder] returns a `List<R>`.
     * The output name defaults to [type]'s simple name (lowercase).
     */
    fun <I : Base, R : Base> addAllFromHavingAllExtensions(
        type: KClass<I>,
        vararg extUrls: String,
        builder: (List<I>) -> List<R>
    ): OperationResult<List<R>> {
        val stepName = type.java.simpleName.lowercase()
        return runBuilderStep(stepName) { start ->
            storeListAndCopy(stepName, builder(collectByExtension(type, extUrls, matchAll = true)), start)
        }
    }

    /** Like [addAllFromHavingAllExtensions] but stores the result list under the explicit [name]. */
    fun <I : Base, R : Base> addAllFromHavingAllExtensions(
        name: String,
        type: KClass<I>,
        vararg extUrls: String,
        builder: (List<I>) -> List<R>
    ): OperationResult<List<R>> =
        runBuilderStep(name) { start ->
            storeListAndCopy(name, builder(collectByExtension(type, extUrls, matchAll = true)), start)
        }

    /**
     * Like [addFromHavingAnyExtension] but [builder] returns a `List<R>`.
     * The output name defaults to [type]'s simple name (lowercase).
     */
    fun <I : Base, R : Base> addAllFromHavingAnyExtension(
        type: KClass<I>,
        vararg extUrls: String,
        builder: (List<I>) -> List<R>
    ): OperationResult<List<R>> {
        val stepName = type.java.simpleName.lowercase()
        return runBuilderStep(stepName) { start ->
            storeListAndCopy(stepName, builder(collectByExtension(type, extUrls, matchAll = false)), start)
        }
    }

    /** Like [addAllFromHavingAnyExtension] but stores the result list under the explicit [name]. */
    fun <I : Base, R : Base> addAllFromHavingAnyExtension(
        name: String,
        type: KClass<I>,
        vararg extUrls: String,
        builder: (List<I>) -> List<R>
    ): OperationResult<List<R>> =
        runBuilderStep(name) { start ->
            storeListAndCopy(name, builder(collectByExtension(type, extUrls, matchAll = false)), start)
        }

    /**
     * Reified overload of [addFromHavingAllExtensions] (unnamed output key).
     *
     * **Named variants are intentionally not reified** — a reified `addFromHavingAllExtensions(name,
     * extUrls…, builder)` would be ambiguous with this overload when the first argument is a
     * `String` (Kotlin cannot determine whether it is `name` or the first vararg element).
     * Use the KClass overload when an explicit output key is required:
     * `addFromHavingAllExtensions("my-key", Patient::class, "http://ext/url") { … }`.
     */
    inline fun <reified I : Base, R : Base> addFromHavingAllExtensions(
        vararg extUrls: String,
        noinline builder: (List<I>) -> R
    ): OperationResult<R> = addFromHavingAllExtensions(I::class, *extUrls, builder = builder)

    /** Reified overload of [addFromHavingAnyExtension] (unnamed output key). See
     *  [addFromHavingAllExtensions] for why a named reified variant is not provided. */
    inline fun <reified I : Base, R : Base> addFromHavingAnyExtension(
        vararg extUrls: String,
        noinline builder: (List<I>) -> R
    ): OperationResult<R> = addFromHavingAnyExtension(I::class, *extUrls, builder = builder)

    /** Reified overload of [addAllFromHavingAllExtensions] (unnamed output key). */
    inline fun <reified I : Base, R : Base> addAllFromHavingAllExtensions(
        vararg extUrls: String,
        noinline builder: (List<I>) -> List<R>
    ): OperationResult<List<R>> = addAllFromHavingAllExtensions(I::class, *extUrls, builder = builder)

    /** Reified overload of [addAllFromHavingAnyExtension] (unnamed output key). */
    inline fun <reified I : Base, R : Base> addAllFromHavingAnyExtension(
        vararg extUrls: String,
        noinline builder: (List<I>) -> List<R>
    ): OperationResult<List<R>> = addAllFromHavingAnyExtension(I::class, *extUrls, builder = builder)

    // ── Extension URL + value-type / value-predicate filters ─────────────────

    /**
     * Searches **all** accumulated parameters for instances of [type] that have an extension at
     * [url] with a value of [valueType], passes the typed list to [builder], and stores the single
     * result under [type]'s simple name (lowercase).
     *
     * This is a convenience shorthand for [addFromHavingExtensionValueMatching] with a trivially
     * true predicate — use that method when you also need to inspect the value itself.
     *
     * Error handling follows Pattern A: exceptions record an ERROR-severity [OperationOutcome]
     * and skip the head value.
     */
    fun <I : Base, V : Type, R : Base> addFromHavingExtensionWithValueType(
        type: KClass<I>,
        url: String,
        valueType: KClass<V>,
        builder: (List<I>) -> R
    ): OperationResult<R> = addFromHavingExtensionValueMatching(type, url, valueType, { true }, builder)

    /**
     * Like [addFromHavingExtensionWithValueType] but stores the result under the explicit [name].
     */
    fun <I : Base, V : Type, R : Base> addFromHavingExtensionWithValueType(
        name: String,
        type: KClass<I>,
        url: String,
        valueType: KClass<V>,
        builder: (List<I>) -> R
    ): OperationResult<R> = addFromHavingExtensionValueMatching(name, type, url, valueType, { true }, builder)

    /**
     * Like [addFromHavingExtensionWithValueType] but [builder] returns a `List<R>`.
     * The output name defaults to [type]'s simple name (lowercase).
     */
    fun <I : Base, V : Type, R : Base> addAllFromHavingExtensionWithValueType(
        type: KClass<I>,
        url: String,
        valueType: KClass<V>,
        builder: (List<I>) -> List<R>
    ): OperationResult<List<R>> = addAllFromHavingExtensionValueMatching(type, url, valueType, { true }, builder)

    /** Like [addAllFromHavingExtensionWithValueType] but stores the result list under the explicit [name]. */
    fun <I : Base, V : Type, R : Base> addAllFromHavingExtensionWithValueType(
        name: String,
        type: KClass<I>,
        url: String,
        valueType: KClass<V>,
        builder: (List<I>) -> List<R>
    ): OperationResult<List<R>> = addAllFromHavingExtensionValueMatching(name, type, url, valueType, { true }, builder)

    /**
     * Reified overload of [addFromHavingExtensionWithValueType] (unnamed output key).
     *
     * **Named variants are intentionally not reified** — a reified overload whose first argument
     * is a `String` would be ambiguous with this one. Use the KClass overload when an explicit
     * output key is required.
     */
    inline fun <reified I : Base, reified V : Type, R : Base> addFromHavingExtensionWithValueType(
        url: String,
        noinline builder: (List<I>) -> R
    ): OperationResult<R> = addFromHavingExtensionWithValueType(I::class, url, V::class, builder)

    /** Reified overload of [addAllFromHavingExtensionWithValueType] (unnamed output key). */
    inline fun <reified I : Base, reified V : Type, R : Base> addAllFromHavingExtensionWithValueType(
        url: String,
        noinline builder: (List<I>) -> List<R>
    ): OperationResult<List<R>> = addAllFromHavingExtensionWithValueType(I::class, url, V::class, builder)

    /**
     * Searches **all** accumulated parameters for instances of [type] that have an extension at
     * [url] with a value of [valueType] satisfying [predicate], passes the typed list to [builder],
     * and stores the single result under [type]'s simple name (lowercase).
     *
     * The resource is included when **any** of its extension values at [url] satisfies [predicate].
     * Resources with no extension at [url], or whose value is not of [valueType], are excluded.
     *
     * Error handling follows Pattern A: exceptions record an ERROR-severity [OperationOutcome]
     * and skip the head value.
     *
     * Use [addFromHavingExtensionWithValueType] when only a type check (no value inspection) is needed.
     */
    fun <I : Base, V : Type, R : Base> addFromHavingExtensionValueMatching(
        type: KClass<I>,
        url: String,
        valueType: KClass<V>,
        predicate: (V) -> Boolean,
        builder: (List<I>) -> R
    ): OperationResult<R> {
        val stepName = type.java.simpleName.lowercase()
        return runBuilderStep(stepName) { start ->
            storeAndCopy(stepName, builder(collectByExtensionAndValueType(type, url, valueType, predicate)), start)
        }
    }

    /**
     * Like [addFromHavingExtensionValueMatching] but stores the result under the explicit [name].
     */
    fun <I : Base, V : Type, R : Base> addFromHavingExtensionValueMatching(
        name: String,
        type: KClass<I>,
        url: String,
        valueType: KClass<V>,
        predicate: (V) -> Boolean,
        builder: (List<I>) -> R
    ): OperationResult<R> =
        runBuilderStep(name) { start ->
            storeAndCopy(name, builder(collectByExtensionAndValueType(type, url, valueType, predicate)), start)
        }

    /**
     * Like [addFromHavingExtensionValueMatching] but [builder] returns a `List<R>`.
     * The output name defaults to [type]'s simple name (lowercase).
     */
    fun <I : Base, V : Type, R : Base> addAllFromHavingExtensionValueMatching(
        type: KClass<I>,
        url: String,
        valueType: KClass<V>,
        predicate: (V) -> Boolean,
        builder: (List<I>) -> List<R>
    ): OperationResult<List<R>> {
        val stepName = type.java.simpleName.lowercase()
        return runBuilderStep(stepName) { start ->
            storeListAndCopy(stepName, builder(collectByExtensionAndValueType(type, url, valueType, predicate)), start)
        }
    }

    /** Like [addAllFromHavingExtensionValueMatching] but stores the result list under the explicit [name]. */
    fun <I : Base, V : Type, R : Base> addAllFromHavingExtensionValueMatching(
        name: String,
        type: KClass<I>,
        url: String,
        valueType: KClass<V>,
        predicate: (V) -> Boolean,
        builder: (List<I>) -> List<R>
    ): OperationResult<List<R>> =
        runBuilderStep(name) { start ->
            storeListAndCopy(name, builder(collectByExtensionAndValueType(type, url, valueType, predicate)), start)
        }

    /**
     * Reified overload of [addFromHavingExtensionValueMatching] (unnamed output key).
     *
     * **Named variants are intentionally not reified** — a reified overload whose first argument
     * is a `String` would be ambiguous with this one. Use the KClass overload when an explicit
     * output key is required.
     */
    inline fun <reified I : Base, reified V : Type, R : Base> addFromHavingExtensionValueMatching(
        url: String,
        noinline predicate: (V) -> Boolean,
        noinline builder: (List<I>) -> R
    ): OperationResult<R> = addFromHavingExtensionValueMatching(I::class, url, V::class, predicate, builder)

    /** Reified overload of [addAllFromHavingExtensionValueMatching] (unnamed output key). */
    inline fun <reified I : Base, reified V : Type, R : Base> addAllFromHavingExtensionValueMatching(
        url: String,
        noinline predicate: (V) -> Boolean,
        noinline builder: (List<I>) -> List<R>
    ): OperationResult<List<R>> = addAllFromHavingExtensionValueMatching(I::class, url, V::class, predicate, builder)

    // Builder methods - single item

    fun <R : Base> add(name: String? = null, builder: () -> R): OperationResult<R> =
        runBuilderStep(name) { start -> storeAndCopy(name, builder(), start) }

    fun <R : Base> addUsing(name: String? = null, builder: (T) -> R): OperationResult<R> =
        runBuilderStep(name) { start -> storeAndCopy(name, builder(getResult()), start) }

    // Builder methods - list
    // addAll/addAllUsing return OperationResult<List<R>>; use getResultList() or getResult()
    // to retrieve the typed list without casting.

    fun <R : Base> addAll(name: String? = null, builder: () -> List<R>): OperationResult<List<R>> =
        runBuilderStep(name) { start -> storeListAndCopy(name, builder(), start) }

    fun <R : Base> addAllUsing(name: String? = null, builder: (T) -> List<R>): OperationResult<List<R>> =
        runBuilderStep(name) { start -> storeListAndCopy(name, builder(getResult()), start) }

    // Builder methods - from existing parameters

    fun <I : Base, R : Base> addFrom(name: String, type: KClass<I>, builder: (List<I>) -> R): OperationResult<R> =
        runBuilderStep(name) { start ->
            val filtered = parameters[name]?.filterIsInstance(type.java) ?: emptyList()
            storeAndCopy(name, builder(filtered), start)
        }

    fun <I : Base, R : Base> addAllFrom(name: String, type: KClass<I>, builder: (List<I>) -> List<R>): OperationResult<List<R>> =
        runBuilderStep(name) { start ->
            val filtered = parameters[name]?.filterIsInstance(type.java) ?: emptyList()
            storeListAndCopy(name, builder(filtered), start)
        }

    // Builder variants with explicit error handling

    fun <R : Base> addOrSkip(name: String? = null, builder: () -> R): OperationResult<T> {
        val start = System.currentTimeMillis()
        return try {
            val value = builder()
            val durationMs = System.currentTimeMillis() - start
            val key = name ?: value.fhirType().lowercase()
            parameters.getOrPut(key) { mutableListOf() }.add(value)
            recordMetric(key, value.fhirType(), durationMs, true)
            logStep(key, value.fhirType(), durationMs)
            copyWith(result)
        } catch (e: Exception) {
            val durationMs = System.currentTimeMillis() - start
            val key = name ?: "unknown"
            logger.warn("FHIRMason | step='{}' | WARN: {}", key, e.message)
            recordMetric(key, "", durationMs, false)
            outcomes.add(warningOutcome(e))
            copyWith(result)
        }
    }

    fun <R : Base> addOrDefault(name: String? = null, default: R, builder: () -> R): OperationResult<R> {
        val start = System.currentTimeMillis()
        return try {
            val value = builder()
            val durationMs = System.currentTimeMillis() - start
            val key = name ?: value.fhirType().lowercase()
            parameters.getOrPut(key) { mutableListOf() }.add(value)
            recordMetric(key, value.fhirType(), durationMs, true)
            logStep(key, value.fhirType(), durationMs)
            copyWith(value)
        } catch (e: Exception) {
            val durationMs = System.currentTimeMillis() - start
            val key = name ?: default.fhirType().lowercase()
            logger.warn("FHIRMason | step='{}' | WARN: {}", key, e.message)
            recordMetric(key, "", durationMs, false)
            outcomes.add(warningOutcome(e))
            parameters.getOrPut(key) { mutableListOf() }.add(default)
            copyWith(default)
        }
    }

    // ── Primitive value convenience methods ──────────────────────────────────

    fun addString(name: String, value: String): OperationResult<T>       = runPrimitiveStep(name) { StringType(value) }
    fun addBoolean(name: String, value: Boolean): OperationResult<T>     = runPrimitiveStep(name) { BooleanType(value) }
    fun addInteger(name: String, value: Int): OperationResult<T>         = runPrimitiveStep(name) { IntegerType(value) }
    fun addDecimal(name: String, value: BigDecimal): OperationResult<T>  = runPrimitiveStep(name) { DecimalType(value) }
    fun addCode(name: String, value: String): OperationResult<T>         = runPrimitiveStep(name) { CodeType(value) }
    fun addUri(name: String, value: String): OperationResult<T>          = runPrimitiveStep(name) { UriType(value) }
    fun addDate(name: String, value: String): OperationResult<T>         = runPrimitiveStep(name) { DateType(value) }
    fun addDate(name: String, value: LocalDate): OperationResult<T>      = runPrimitiveStep(name) { FhirDateTimeConverter.toFhirDate(value) }
    fun addDate(name: String, value: YearMonth): OperationResult<T>      = runPrimitiveStep(name) { FhirDateTimeConverter.toFhirDate(value) }
    fun addDate(name: String, value: Year): OperationResult<T>           = runPrimitiveStep(name) { FhirDateTimeConverter.toFhirDate(value) }
    fun addDate(name: String, value: Date): OperationResult<T>            = runPrimitiveStep(name) { FhirDateTimeConverter.toFhirDate(value) }
    fun addDate(name: String, value: LocalDateTime): OperationResult<T>  = runPrimitiveStep(name) { FhirDateTimeConverter.toFhirDate(value) }
    fun addDate(name: String, value: ZonedDateTime): OperationResult<T>  = runPrimitiveStep(name) { FhirDateTimeConverter.toFhirDate(value) }
    fun addDate(name: String, value: OffsetDateTime): OperationResult<T> = runPrimitiveStep(name) { FhirDateTimeConverter.toFhirDate(value) }

    fun addDateTime(name: String, value: String): OperationResult<T>          = runPrimitiveStep(name) { DateTimeType(value) }
    fun addDateTime(name: String, value: LocalDateTime): OperationResult<T>   = runPrimitiveStep(name) { FhirDateTimeConverter.toFhirDateTime(value) }
    fun addDateTime(name: String, value: ZonedDateTime): OperationResult<T>   = runPrimitiveStep(name) { FhirDateTimeConverter.toFhirDateTime(value) }
    fun addDateTime(name: String, value: OffsetDateTime): OperationResult<T>  = runPrimitiveStep(name) { FhirDateTimeConverter.toFhirDateTime(value) }
    fun addDateTime(name: String, value: Instant): OperationResult<T>         = runPrimitiveStep(name) { FhirDateTimeConverter.toFhirDateTime(value) }
    fun addDateTime(name: String, value: Date): OperationResult<T>            = runPrimitiveStep(name) { FhirDateTimeConverter.toFhirDateTime(value) }
    fun addDateTime(name: String, value: Calendar): OperationResult<T>        = runPrimitiveStep(name) { FhirDateTimeConverter.toFhirDateTime(value) }

    fun addInstant(name: String, value: String): OperationResult<T>           = runPrimitiveStep(name) { InstantType(value) }
    fun addInstant(name: String, value: Instant): OperationResult<T>          = runPrimitiveStep(name) { FhirDateTimeConverter.toFhirInstant(value) }
    fun addInstant(name: String, value: ZonedDateTime): OperationResult<T>    = runPrimitiveStep(name) { FhirDateTimeConverter.toFhirInstant(value) }
    fun addInstant(name: String, value: OffsetDateTime): OperationResult<T>   = runPrimitiveStep(name) { FhirDateTimeConverter.toFhirInstant(value) }
    fun addInstant(name: String, value: Date): OperationResult<T>             = runPrimitiveStep(name) { FhirDateTimeConverter.toFhirInstant(value) }

    fun addTime(name: String, value: String): OperationResult<T>              = runPrimitiveStep(name) { TimeType(value) }
    fun addTime(name: String, value: LocalTime): OperationResult<T>           = runPrimitiveStep(name) { FhirDateTimeConverter.toFhirTime(value) }

    fun addCanonical(name: String, value: String): OperationResult<T>    = runPrimitiveStep(name) { CanonicalType(value) }

    fun addStringUsing(name: String, builder: (T) -> String): OperationResult<T>      = runPrimitiveStep(name) { StringType(builder(getResult())) }
    fun addBooleanUsing(name: String, builder: (T) -> Boolean): OperationResult<T>    = runPrimitiveStep(name) { BooleanType(builder(getResult())) }
    fun addIntegerUsing(name: String, builder: (T) -> Int): OperationResult<T>        = runPrimitiveStep(name) { IntegerType(builder(getResult())) }
    fun addDecimalUsing(name: String, builder: (T) -> BigDecimal): OperationResult<T> = runPrimitiveStep(name) { DecimalType(builder(getResult())) }
    fun addCodeUsing(name: String, builder: (T) -> String): OperationResult<T>        = runPrimitiveStep(name) { CodeType(builder(getResult())) }
    fun addUriUsing(name: String, builder: (T) -> String): OperationResult<T>         = runPrimitiveStep(name) { UriType(builder(getResult())) }
    fun addDateUsing(name: String, builder: (T) -> String): OperationResult<T>        = runPrimitiveStep(name) { DateType(builder(getResult())) }
    fun addDateTimeUsing(name: String, builder: (T) -> String): OperationResult<T>   = runPrimitiveStep(name) { DateTimeType(builder(getResult())) }
    fun addInstantUsing(name: String, builder: (T) -> String): OperationResult<T>    = runPrimitiveStep(name) { InstantType(builder(getResult())) }
    fun addTimeUsing(name: String, builder: (T) -> String): OperationResult<T>       = runPrimitiveStep(name) { TimeType(builder(getResult())) }
    fun addCanonicalUsing(name: String, builder: (T) -> String): OperationResult<T>  = runPrimitiveStep(name) { CanonicalType(builder(getResult())) }

    // ── Java date/time Using variants (named to avoid JVM erasure clash) ─────
    // Lambda overloads that differ only in the lambda return type share the same JVM
    // signature after erasure and also cause Kotlin overload-resolution ambiguity at
    // call sites. Each variant is given a distinct name encoding the input type.

    fun addDateUsingLocalDate(name: String, builder: (T) -> LocalDate): OperationResult<T>               = runPrimitiveStep(name) { FhirDateTimeConverter.toFhirDate(builder(getResult())) }
    fun addDateUsingYearMonth(name: String, builder: (T) -> YearMonth): OperationResult<T>               = runPrimitiveStep(name) { FhirDateTimeConverter.toFhirDate(builder(getResult())) }
    fun addDateUsingYear(name: String, builder: (T) -> Year): OperationResult<T>                         = runPrimitiveStep(name) { FhirDateTimeConverter.toFhirDate(builder(getResult())) }
    fun addDateUsingLocalDateTime(name: String, builder: (T) -> LocalDateTime): OperationResult<T>       = runPrimitiveStep(name) { FhirDateTimeConverter.toFhirDate(builder(getResult())) }
    fun addDateUsingZonedDateTime(name: String, builder: (T) -> ZonedDateTime): OperationResult<T>       = runPrimitiveStep(name) { FhirDateTimeConverter.toFhirDate(builder(getResult())) }
    fun addDateUsingOffsetDateTime(name: String, builder: (T) -> OffsetDateTime): OperationResult<T>     = runPrimitiveStep(name) { FhirDateTimeConverter.toFhirDate(builder(getResult())) }
    fun addDateTimeUsingLocalDateTime(name: String, builder: (T) -> LocalDateTime): OperationResult<T>   = runPrimitiveStep(name) { FhirDateTimeConverter.toFhirDateTime(builder(getResult())) }
    fun addDateTimeUsingZonedDateTime(name: String, builder: (T) -> ZonedDateTime): OperationResult<T>   = runPrimitiveStep(name) { FhirDateTimeConverter.toFhirDateTime(builder(getResult())) }
    fun addDateTimeUsingOffsetDateTime(name: String, builder: (T) -> OffsetDateTime): OperationResult<T> = runPrimitiveStep(name) { FhirDateTimeConverter.toFhirDateTime(builder(getResult())) }
    fun addDateTimeUsingInstant(name: String, builder: (T) -> Instant): OperationResult<T>               = runPrimitiveStep(name) { FhirDateTimeConverter.toFhirDateTime(builder(getResult())) }
    fun addInstantUsingInstant(name: String, builder: (T) -> Instant): OperationResult<T>                = runPrimitiveStep(name) { FhirDateTimeConverter.toFhirInstant(builder(getResult())) }
    fun addInstantUsingZonedDateTime(name: String, builder: (T) -> ZonedDateTime): OperationResult<T>    = runPrimitiveStep(name) { FhirDateTimeConverter.toFhirInstant(builder(getResult())) }
    fun addInstantUsingOffsetDateTime(name: String, builder: (T) -> OffsetDateTime): OperationResult<T>  = runPrimitiveStep(name) { FhirDateTimeConverter.toFhirInstant(builder(getResult())) }
    fun addTimeUsingLocalTime(name: String, builder: (T) -> LocalTime): OperationResult<T>               = runPrimitiveStep(name) { FhirDateTimeConverter.toFhirTime(builder(getResult())) }

    // ── Complex data type convenience methods ────────────────────────────────

    fun addCoding(name: String, system: String, code: String, display: String? = null): OperationResult<T> =
        runPrimitiveStep(name) {
            Coding().apply {
                this.system = system
                this.code = code
                if (display != null) this.display = display
            }
        }

    fun addReference(name: String, reference: String): OperationResult<T> =
        runPrimitiveStep(name) { Reference(reference) }

    fun addIdentifier(name: String, system: String, value: String): OperationResult<T> =
        runPrimitiveStep(name) {
            Identifier().apply {
                this.system = system
                this.value = value
            }
        }

    fun addPeriod(name: String, start: String?, end: String?): OperationResult<T> =
        runPrimitiveStep(name) {
            Period().apply {
                if (start != null) this.startElement = DateTimeType(start)
                if (end != null) this.endElement = DateTimeType(end)
            }
        }

    fun addPeriod(name: String, start: LocalDateTime?, end: LocalDateTime?): OperationResult<T> =
        runPrimitiveStep(name) {
            Period().apply {
                if (start != null) this.startElement = FhirDateTimeConverter.toFhirDateTime(start)
                if (end != null) this.endElement = FhirDateTimeConverter.toFhirDateTime(end)
            }
        }

    fun addPeriod(name: String, start: ZonedDateTime?, end: ZonedDateTime?): OperationResult<T> =
        runPrimitiveStep(name) {
            Period().apply {
                if (start != null) this.startElement = FhirDateTimeConverter.toFhirDateTime(start)
                if (end != null) this.endElement = FhirDateTimeConverter.toFhirDateTime(end)
            }
        }

    fun addPeriod(name: String, start: OffsetDateTime?, end: OffsetDateTime?): OperationResult<T> =
        runPrimitiveStep(name) {
            Period().apply {
                if (start != null) this.startElement = FhirDateTimeConverter.toFhirDateTime(start)
                if (end != null) this.endElement = FhirDateTimeConverter.toFhirDateTime(end)
            }
        }

    fun addQuantity(name: String, value: BigDecimal, unit: String, system: String? = null, code: String? = null): OperationResult<T> =
        runPrimitiveStep(name) {
            Quantity().apply {
                this.value = value
                this.unit = unit
                if (system != null) this.system = system
                if (code != null) this.code = code
            }
        }

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
        val start = System.currentTimeMillis()

        val subPipeline = OperationResult<T>(
            mutableMapOf(), result, mutableListOf(), errorStrategy, mutableMapOf(), timingEnabled, mutableListOf()
        )

        val built = builder(subPipeline)

        built.getAllParameters().forEach { (childKey, values) ->
            val prefixedKey = "$name.$childKey"
            parameters.getOrPut(prefixedKey) { mutableListOf() }.addAll(values)
        }

        val durationMs = System.currentTimeMillis() - start
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
        val start = System.currentTimeMillis()
        parameters.getOrPut(name) { mutableListOf() }.add(value)
        if (exts.isNotEmpty()) {
            val innerMap = extensions.getOrPut(name) { IdentityHashMap() }
            innerMap[value] = exts.toList()
        }
        val durationMs = System.currentTimeMillis() - start
        recordMetric(name, value.fhirType(), durationMs, true)
        logStep(name, value.fhirType(), durationMs)
        return copyWith(result)
    }

    // Query methods

    fun getAllParameters(): Map<String, List<Base>> =
        parameters.mapValues { it.value.toList() }

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

    fun containsKey(name: String): Boolean =
        parameters.containsKey(name)

    fun getKeys(): Set<String> =
        parameters.keys.toSet()

    fun count(name: String): Int =
        parameters[name]?.size ?: 0

    fun totalCount(): Int =
        parameters.values.sumOf { it.size }

    fun isEmpty(): Boolean =
        parameters.isEmpty()

    fun isNotEmpty(): Boolean =
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

    fun filterByName(name: String): OperationResult<T> {
        val filtered = mutableMapOf<String, MutableList<Base>>()
        parameters[name]?.let { values ->
            filtered[name] = values.toMutableList()
        }
        return copyWith(result, params = filtered)
    }

    fun <R : Base> mapValues(transform: (Base) -> R): OperationResult<R> {
        val transformed = parameters.mapValues { (_, values) ->
            val newList = mutableListOf<Base>()
            values.forEach { newList.add(transform(it)) }
            newList
        }.toMutableMap()
        return copyWith<R>(null, params = transformed)
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
        val values = newParams.remove(oldName)
        if (values != null) {
            newParams.getOrPut(newName) { mutableListOf() }.addAll(values)
        }
        return copyWith(result, params = newParams, extensions = shallowCopyExtensions())
    }

    /**
     * Invokes [block] with a snapshot of the current parameter map for side-effects (logging,
     * debugging) and returns this [OperationResult] unchanged.
     */
    fun peek(block: (Map<String, List<Base>>) -> Unit): OperationResult<T> {
        block(getAllParameters())
        return this
    }

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
     * Throws [IllegalArgumentException] if no value of [R] exists under [name].
     */
    fun <R : Base> extractParam(name: String, type: KClass<R>): OperationResult<R> {
        val value = parameters[name]
            ?.filterIsInstance(type.java)
            ?.firstOrNull()
            ?: throw IllegalArgumentException(
                "No value of type '${type.simpleName}' found under key '$name'"
            )
        return copyWith(value)
    }

    /** Reified overload — no [KClass] argument needed at call sites. */
    inline fun <reified R : Base> extractParam(name: String): OperationResult<R> =
        extractParam(name, R::class)

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
        val copiedParams = deepCopyParameters()
        val allResources = copiedParams.values.flatten().filterIsInstance<Resource>()
        rules.forEach { rule ->
            val sources = allResources.filter { rule.sourceType.java.isInstance(it) }
            val targets = allResources.filter { rule.targetType.java.isInstance(it) }
            require(targets.size <= 1) {
                "Ambiguous reference target: ${targets.size} resources of type " +
                "'${rule.targetType.simpleName}' found; exactly one is required per rule"
            }
            val target = targets.singleOrNull() ?: return@forEach
            sources.forEach { source ->
                if (source !== target) rule.applyTo(source, target)
            }
        }
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
        val copiedParams = deepCopyParameters()
        val allResources = copiedParams.values.flatten().filterIsInstance<Resource>()

        val resourceIndex: Map<String, List<Resource>> = allResources
            .filter { it.hasId() }
            .groupBy { it.fhirType().lowercase() }

        allResources.forEach { source ->
            source.children().forEach { property ->
                val typeCode = property.typeCode ?: return@forEach
                if (!typeCode.startsWith("Reference(")) return@forEach
                if (property.hasValues()) return@forEach

                val allowedTypes = typeCode
                    .removePrefix("Reference(")
                    .removeSuffix(")")
                    .split("|")
                    .map { it.trim().lowercase() }

                val candidates: List<Resource> = allowedTypes.flatMap { type ->
                    resourceIndex[type]?.filter { it !== source } ?: emptyList()
                }

                if (candidates.size == 1) {
                    val target = candidates.single()
                    val ref = Reference("${target.fhirType()}/${target.idPart}")
                    runCatching { source.setProperty(property.name, ref) }
                }
            }
        }
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
    fun toParameters(): Parameters = Parameters().apply {
        val fhirParams = this
        buildParameterComponents(parameters) { name ->
            fhirParams.addParameter().also { it.name = name }
        }
    }

    fun toBundleEntry(resource: Base): Bundle.BundleEntryComponent =
        Bundle.BundleEntryComponent().apply {
            if (resource is Resource) setResource(resource)
        }

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

    fun toTransactionBundle(): Bundle = toBundle(Bundle.BundleType.TRANSACTION)

    fun toBatchBundle(): Bundle = toBundle(Bundle.BundleType.BATCH)

    /**
     * Returns the most recently added value, typed as [T].
     * No cast is needed because [T] is tracked through the pipeline generics.
     * For list-producing steps use the [getResultList] extension.
     */
    fun getResult(): T = result ?: throw IllegalStateException("No result set")

    // Private helpers

    /**
     * Recursively builds [Parameters.ParametersParameterComponent] entries from [entries].
     *
     * [addComponent] abstracts the difference between adding to a [Parameters] root
     * (`parameters.addParameter()`) and adding to a parent part (`component.addPart()`),
     * so the same logic handles both levels.
     *
     * Algorithm:
     * - Collect all unique top-level segments (the portion of each key before the first dot),
     *   preserving map insertion order.
     * - For a segment whose values live directly under that key (no dot sub-keys):
     *   emit one component per value (leaf node).
     * - For a segment whose values all live under dot-qualified sub-keys:
     *   emit a single parent component with no value/resource, then recurse for its children.
     *
     * This means `"address.city"` and `"address.country"` together produce:
     * ```
     * name="address"
     *   part: name="city",    value=…
     *   part: name="country", value=…
     * ```
     * and `"outer.inner.leaf"` produces three levels of nesting.
     */
    private fun buildParameterComponents(
        entries: Map<String, List<Base>>,
        keyPrefix: String = "",
        addComponent: (String) -> Parameters.ParametersParameterComponent
    ) {
        // Unique top-level segments in insertion order
        val topSegments = entries.keys.mapTo(linkedSetOf()) { it.substringBefore('.') }

        topSegments.forEach { segment ->
            val directValues = entries[segment]
            // Sub-entries for keys like "segment.rest" — strip the "segment." prefix
            val childEntries: Map<String, List<Base>> = entries
                .filterKeys { it.startsWith("$segment.") }
                .mapKeys { (key, _) -> key.removePrefix("$segment.") }

            val fullKey = if (keyPrefix.isEmpty()) segment else "$keyPrefix.$segment"

            if (childEntries.isEmpty()) {
                // Leaf: one component per value
                directValues?.forEach { value ->
                    addComponent(segment).apply {
                        when (value) {
                            is Type     -> setValue(value)
                            is Resource -> setResource(value)
                        }
                        val valueExts = extensions[fullKey]?.get(value)
                        valueExts?.forEach { ext -> addExtension(ext) }
                    }
                }
            } else {
                // Branch: single parent component whose children are built recursively
                val parent = addComponent(segment)
                buildParameterComponents(childEntries, fullKey) { childName ->
                    parent.addPart().also { it.name = childName }
                }
            }
        }
    }

    /**
     * Returns a deep copy of [parameters] so that [linkReferences] can mutate the copies
     * without affecting the original accumulated resources.
     *
     * HAPI FHIR's [Base.copy] performs a recursive clone of each element.
     */
    private fun deepCopyParameters(): MutableMap<String, MutableList<Base>> =
        parameters.mapValues { (_, values) ->
            values.map { base -> if (base is Resource) base.copy() else base }.toMutableList()
        }.toMutableMap()

    private fun warningOutcome(e: Exception): OperationOutcome {
        val base = when (e) {
            is BaseServerResponseException -> e.toOperationOutcome()
            else -> e.toOperationOutcome()
        }
        base.issue.forEach { it.severity = OperationOutcome.IssueSeverity.WARNING }
        return base
    }

    private fun addToParameters(values: List<Base>, name: String?) {
        if (name != null) {
            values.forEach { value ->
                parameters.getOrPut(name) { mutableListOf() }.add(value)
            }
        } else {
            values.forEach { value ->
                val key = value.fhirType().lowercase()
                parameters.getOrPut(key) { mutableListOf() }.add(value)
            }
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

        fun <T : Base> of(value: T, name: String? = null, errorStrategy: ErrorStrategy = ErrorStrategy.FAIL_FAST): OperationResult<T> {
            val key = name ?: value.fhirType().lowercase()
            val params = mutableMapOf<String, MutableList<Base>>()
            params.getOrPut(key) { mutableListOf() }.add(value)
            return OperationResult(params, value, mutableListOf(), errorStrategy, mutableMapOf())
        }

        fun <T : Base> of(values: List<T>, name: String? = null, errorStrategy: ErrorStrategy = ErrorStrategy.FAIL_FAST): OperationResult<List<T>> {
            val params = mutableMapOf<String, MutableList<Base>>()
            val instance = OperationResult(params, values, mutableListOf(), errorStrategy, mutableMapOf())
            instance.addToParameters(values, name)
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
        fun fromParameters(parameters: Parameters): OperationResult<Base> {
            val (params, exts) = buildParamsFrom(parameters)
            return OperationResult(params, null, mutableListOf(), ErrorStrategy.FAIL_FAST, mutableMapOf(), extensions = exts)
        }

        /**
         * Like [fromParameters] but also sets the typed pipeline head to the first value stored
         * under [primaryKey] that is an instance of [type].
         *
         * @throws IllegalArgumentException if no value of [type] exists under [primaryKey].
         */
        fun <T : Base> fromParametersTyped(
            parameters: Parameters,
            primaryKey: String,
            type: KClass<T>
        ): OperationResult<T> {
            val (params, exts) = buildParamsFrom(parameters)
            val primary = params[primaryKey]
                ?.filterIsInstance(type.java)
                ?.firstOrNull()
                ?: throw IllegalArgumentException(
                    "No value of type '${type.simpleName}' found under key '$primaryKey'"
                )
            return OperationResult(params, primary, mutableListOf(), ErrorStrategy.FAIL_FAST, mutableMapOf(), extensions = exts)
        }

        /** Reified overload of [fromParametersTyped] — no [KClass] argument needed at call sites. */
        inline fun <reified T : Base> fromParametersTyped(
            parameters: Parameters,
            primaryKey: String
        ): OperationResult<T> = fromParametersTyped(parameters, primaryKey, T::class)

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
         * Creates an empty [OperationResult] with no parameters and no pipeline head.
         *
         * Useful for:
         * - Starting a pipeline that conditionally adds parameters
         * - Building a [Parameters] resource that may legitimately have zero entries
         * - Serving as a merge target
         *
         * [getResult] will throw [IllegalStateException] until a value is added via [add] or similar.
         * [toParameters] returns a valid empty [Parameters] resource.
         */
        fun empty(errorStrategy: ErrorStrategy = ErrorStrategy.FAIL_FAST): OperationResult<Base> =
            OperationResult(
                mutableMapOf(),
                null,
                mutableListOf(),
                errorStrategy,
                mutableMapOf()
            )

        // ── Private helpers ───────────────────────────────────────────────────

        private fun buildParamsFrom(
            parameters: Parameters
        ): Pair<MutableMap<String, MutableList<Base>>, MutableMap<String, MutableMap<Base, List<Extension>>>> {
            val params = mutableMapOf<String, MutableList<Base>>()
            val exts = mutableMapOf<String, MutableMap<Base, List<Extension>>>()
            parameters.parameter.forEach { param -> populateFromParam(params, exts, param) }
            return params to exts
        }

        /**
         * Recursively populates [params] and [exts] from a single [Parameters.ParametersParameterComponent].
         *
         * - Resource parameter  → stored under [keyPrefix]`.<name>` (or just `<name>` at root);
         *                         component-level extensions on resource parameters are not captured
         *                         because the resource already carries its own extension list.
         * - Value parameter     → stored under [keyPrefix]`.<name>`; any component-level extensions
         *                         (i.e. [Parameters.ParametersParameterComponent.extension]) are
         *                         preserved in [exts] so that a subsequent [toParameters] call
         *                         re-emits them on the same component.
         * - Parts-only entry    → each part is processed recursively with the current key as prefix
         */
        private fun populateFromParam(
            params: MutableMap<String, MutableList<Base>>,
            exts: MutableMap<String, MutableMap<Base, List<Extension>>>,
            param: Parameters.ParametersParameterComponent,
            keyPrefix: String = ""
        ) {
            val key = if (keyPrefix.isEmpty()) param.name else "$keyPrefix.${param.name}"
            when {
                param.hasResource() -> params.getOrPut(key) { mutableListOf() }.add(param.resource)
                param.hasValue() -> {
                    val value = param.value
                    params.getOrPut(key) { mutableListOf() }.add(value)
                    if (param.hasExtension()) {
                        val innerMap = exts.getOrPut(key) { IdentityHashMap() }
                        innerMap[value] = param.extension.toList()
                    }
                }
                param.hasPart() -> param.part.forEach { part ->
                    populateFromParam(params, exts, part, key)
                }
            }
        }
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
