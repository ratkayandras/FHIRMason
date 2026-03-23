package dev.ratkay.operation

import org.hl7.fhir.r4.model.*
import kotlin.reflect.KClass

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

    fun <R : Base> getByType(type: KClass<R>): List<R> =
        parameters.values.flatten().filterIsInstance(type.java)

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
