package dev.ratkay.operation

import org.hl7.fhir.r4.model.*
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
    private val failedTasks: MutableMap<String, OperationOutcome>
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

    // Builder helpers

    private fun shouldSkip() = errorStrategy == ErrorStrategy.FAIL_FAST && hasErrors()

    private fun <R> skippedResult(): OperationResult<R> {
        @Suppress("UNCHECKED_CAST")
        return OperationResult(parameters, null, outcomes, errorStrategy, failedTasks) as OperationResult<R>
    }

    // Builder methods - single item

    fun <R : Base> add(name: String? = null, builder: () -> R): OperationResult<R> {
        if (shouldSkip()) return skippedResult()
        return try {
            val value = builder()
            val key = name ?: value.fhirType().lowercase()
            parameters.getOrPut(key) { mutableListOf() }.add(value)
            OperationResult(parameters, value, outcomes, errorStrategy, failedTasks)
        } catch (e: Exception) {
            outcomes.add(e.toOperationOutcome())
            skippedResult()
        }
    }

    fun <R : Base> addUsing(name: String? = null, builder: (T) -> R): OperationResult<R> {
        if (shouldSkip()) return skippedResult()
        return try {
            val value = builder(getResult())
            val key = name ?: value.fhirType().lowercase()
            parameters.getOrPut(key) { mutableListOf() }.add(value)
            OperationResult(parameters, value, outcomes, errorStrategy, failedTasks)
        } catch (e: Exception) {
            outcomes.add(e.toOperationOutcome())
            skippedResult()
        }
    }

    // Builder methods - list
    // addAll/addAllUsing return OperationResult<List<R>>; use getResultList() or getResult()
    // to retrieve the typed list without casting.

    fun <R : Base> addAll(name: String? = null, builder: () -> List<R>): OperationResult<List<R>> {
        if (shouldSkip()) return skippedResult()
        return try {
            val values = builder()
            addToParameters(values, name)
            OperationResult(parameters, values, outcomes, errorStrategy, failedTasks)
        } catch (e: Exception) {
            outcomes.add(e.toOperationOutcome())
            skippedResult()
        }
    }

    fun <R : Base> addAllUsing(name: String? = null, builder: (T) -> List<R>): OperationResult<List<R>> {
        if (shouldSkip()) return skippedResult()
        return try {
            val values = builder(getResult())
            addToParameters(values, name)
            OperationResult(parameters, values, outcomes, errorStrategy, failedTasks)
        } catch (e: Exception) {
            outcomes.add(e.toOperationOutcome())
            skippedResult()
        }
    }

    // Builder methods - from existing parameters

    fun <I : Base, R : Base> addFrom(name: String, type: KClass<I>, builder: (List<I>) -> R): OperationResult<R> {
        if (shouldSkip()) return skippedResult()
        return try {
            val filtered = parameters[name]?.filterIsInstance(type.java) ?: emptyList()
            val value = builder(filtered)
            parameters.getOrPut(name) { mutableListOf() }.add(value)
            OperationResult(parameters, value, outcomes, errorStrategy, failedTasks)
        } catch (e: Exception) {
            outcomes.add(e.toOperationOutcome())
            skippedResult()
        }
    }

    fun <I : Base, R : Base> addAllFrom(name: String, type: KClass<I>, builder: (List<I>) -> List<R>): OperationResult<List<R>> {
        if (shouldSkip()) return skippedResult()
        return try {
            val filtered = parameters[name]?.filterIsInstance(type.java) ?: emptyList()
            val values = builder(filtered)
            addToParameters(values, name)
            OperationResult(parameters, values, outcomes, errorStrategy, failedTasks)
        } catch (e: Exception) {
            outcomes.add(e.toOperationOutcome())
            skippedResult()
        }
    }

    // Builder variants with explicit error handling

    fun <R : Base> addOrSkip(name: String? = null, builder: () -> R): OperationResult<T> {
        return try {
            val value = builder()
            val key = name ?: value.fhirType().lowercase()
            parameters.getOrPut(key) { mutableListOf() }.add(value)
            OperationResult(parameters, result, outcomes, errorStrategy, failedTasks)
        } catch (e: Exception) {
            outcomes.add(warningOutcome(e))
            OperationResult(parameters, result, outcomes, errorStrategy, failedTasks)
        }
    }

    fun <R : Base> addOrDefault(name: String? = null, default: R, builder: () -> R): OperationResult<R> {
        return try {
            val value = builder()
            val key = name ?: value.fhirType().lowercase()
            parameters.getOrPut(key) { mutableListOf() }.add(value)
            OperationResult(parameters, value, outcomes, errorStrategy, failedTasks)
        } catch (e: Exception) {
            outcomes.add(warningOutcome(e))
            val key = name ?: default.fhirType().lowercase()
            parameters.getOrPut(key) { mutableListOf() }.add(default)
            OperationResult(parameters, default, outcomes, errorStrategy, failedTasks)
        }
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
        return OperationResult(filtered, result, outcomes, errorStrategy, failedTasks)
    }

    /** Reified overload — no [KClass] argument needed at call sites. */
    inline fun <reified R : Base> filterByType(): OperationResult<T> = filterByType(R::class)

    fun filterByName(name: String): OperationResult<T> {
        val filtered = mutableMapOf<String, MutableList<Base>>()
        parameters[name]?.let { values ->
            filtered[name] = values.toMutableList()
        }
        return OperationResult(filtered, result, outcomes, errorStrategy, failedTasks)
    }

    fun mapValues(transform: (Base) -> Base): OperationResult<T> {
        val transformed = parameters.mapValues { (_, values) ->
            values.map(transform).toMutableList()
        }.toMutableMap()
        return OperationResult(transformed, result, outcomes, errorStrategy, failedTasks)
    }

    // Core methods

    fun toParameters(): Parameters = Parameters().apply {
        parameters.forEach { (name, values) ->
            values.forEach { value ->
                addParameter().apply {
                    this.name = name
                    when (value) {
                        is Type -> setValue(value)
                        is Resource -> setResource(value)
                    }
                }
            }
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

    private fun warningOutcome(e: Exception): OperationOutcome = OperationOutcome().apply {
        addIssue().apply {
            severity = OperationOutcome.IssueSeverity.WARNING
            code = OperationOutcome.IssueType.EXCEPTION
            diagnostics = e.message ?: e.javaClass.simpleName
        }
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
