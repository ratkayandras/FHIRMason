package dev.ratkay.operation

import org.hl7.fhir.r4.model.*
import kotlin.reflect.KClass

class OperationResult<T> private constructor(
    private val parameters: MutableMap<String, MutableList<Base>>,
    private val result: T?
) {

    // Builder methods - single item

    fun <R : Base> add(name: String? = null, builder: () -> R): OperationResult<R> {
        val value = builder()
        val key = name ?: value.fhirType().lowercase()
        parameters.getOrPut(key) { mutableListOf() }.add(value)
        return OperationResult(parameters, value)
    }

    fun <R : Base> add(name: String? = null, builder: (T) -> R): OperationResult<R> {
        val value = builder(getResult())
        val key = name ?: value.fhirType().lowercase()
        parameters.getOrPut(key) { mutableListOf() }.add(value)
        return OperationResult(parameters, value)
    }

    // Builder methods - list

    fun <R : Base> addAll(name: String? = null, builder: () -> List<R>): OperationResult<List<R>> {
        val values = builder()
        addToParameters(values, name)
        return OperationResult(parameters, values)
    }

    fun <R : Base> addAll(name: String? = null, builder: (T) -> List<R>): OperationResult<List<R>> {
        val values = builder(getResult())
        addToParameters(values, name)
        return OperationResult(parameters, values)
    }

    // Builder methods - from existing parameters

    fun <I : Base, R : Base> addFrom(name: String, type: KClass<I>, builder: (List<I>) -> R): OperationResult<R> {
        val filtered = parameters[name]?.filterIsInstance(type.java) ?: emptyList()
        val value = builder(filtered)
        parameters.getOrPut(name) { mutableListOf() }.add(value)
        return OperationResult(parameters, value)
    }

    fun <I : Base, R : Base> addAllFrom(name: String, type: KClass<I>, builder: (List<I>) -> List<R>): OperationResult<List<R>> {
        val filtered = parameters[name]?.filterIsInstance(type.java) ?: emptyList()
        val values = builder(filtered)
        addToParameters(values, name)
        return OperationResult(parameters, values)
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
        return OperationResult(filtered, result)
    }

    fun filterByName(name: String): OperationResult<T> {
        val filtered = mutableMapOf<String, MutableList<Base>>()
        parameters[name]?.let { values ->
            filtered[name] = values.toMutableList()
        }
        return OperationResult(filtered, result)
    }

    fun mapValues(transform: (Base) -> Base): OperationResult<T> {
        val transformed = parameters.mapValues { (_, values) ->
            values.map(transform).toMutableList()
        }.toMutableMap()
        return OperationResult(transformed, result)
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
        fun <T : Base> of(value: T, name: String? = null): OperationResult<T> {
            val key = name ?: value.fhirType().lowercase()
            val params = mutableMapOf<String, MutableList<Base>>()
            params.getOrPut(key) { mutableListOf() }.add(value)
            return OperationResult(params, value)
        }

        fun <T : Base> of(values: List<T>, name: String? = null): OperationResult<List<T>> {
            val params = mutableMapOf<String, MutableList<Base>>()
            val instance = OperationResult(params, values)
            instance.addToParameters(values, name)
            return instance
        }
    }
}
