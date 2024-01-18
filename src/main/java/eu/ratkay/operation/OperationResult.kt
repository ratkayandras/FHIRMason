package eu.ratkay.operation

import eu.ratkay.dto.ResourceHolder
import eu.ratkay.extension.getParameterName
import eu.ratkay.extension.hasError
import eu.ratkay.extension.toBundleEntryComponent
import eu.ratkay.extension.toParameterComponent
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.OperationOutcome
import org.hl7.fhir.r4.model.Parameters
import org.hl7.fhir.r4.model.Resource

sealed class OperationResult<T>(private val resources: MutableList<ResourceHolder>) {

    private data class ResourceSuccess<T : Resource>(val name: String, val resource: T) : OperationResult<T>(
        mutableListOf(
            ResourceHolder(name, resource)
        )
    )

    private data class CollectionSuccess<C : Collection<T>, T : Resource>(val name: String, val resource: C) :
        OperationResult<C>(
            resource.map { ResourceHolder(name, it) }.toMutableList()
        )

    private data class Error<T>(val operationOutcome: OperationOutcome) : OperationResult<T>(mutableListOf())

    companion object {
        fun <T : Resource> of(resource: T, name: String? = null): OperationResult<T> {
            val paramName = name ?: resource.getParameterName()
            return if (resource is OperationOutcome && resource.hasIssue() && resource.hasError()) {
                Error(resource)
            } else {
                ResourceSuccess(paramName, resource)
            }
        }

        fun <C : Collection<R>, R : Resource> ofCollection(resource: C, name: String? = null): OperationResult<C> {
            val paramName = name ?: resource.firstNotNullOfOrNull { it.getParameterName() } ?: "resource"
            return if (resource is OperationOutcome && resource.hasIssue() && resource.hasError()) {
                Error(resource)
            } else {
                CollectionSuccess(paramName, resource)
            }
        }
    }

    fun asParameters(): Resource {
        return when (this) {
            is ResourceSuccess -> this.toParameters()
            is CollectionSuccess<*, *> -> this.toParameters()
            is Error -> this.operationOutcome
        }
    }

    fun asBundle(): Resource {
        return when (this) {
            is ResourceSuccess -> this.toBundle()
            is CollectionSuccess<*, *> -> this.toBundle()
            is Error -> this.operationOutcome
        }
    }

    fun <T : Resource> operate(lambda: () -> T): OperationResult<T> =
        when (this) {
            is ResourceSuccess -> of(lambda())
            is CollectionSuccess<*, *> -> of(lambda())
            is Error -> Error(this.operationOutcome)
        }

    fun <T : Resource> operate(name: String, lambda: () -> T): OperationResult<T> =
        when (this) {
            is ResourceSuccess -> of(lambda(), name)
            is CollectionSuccess<*, *> -> of(lambda(), name)
            is Error -> Error(this.operationOutcome)
        }

    fun <T : Resource> operateCombined(lambda: () -> T): OperationResult<T> =
        when (this) {
            is ResourceSuccess -> of(lambda()) combine this.resources
            is CollectionSuccess<*, *> -> of(lambda()) combine this.resources
            is Error -> Error(this.operationOutcome)
        }

    fun <T : Resource> operateCombined(name: String, lambda: () -> T): OperationResult<T> =
        when (this) {
            is ResourceSuccess -> of(lambda(), name) combine this.resources
            is CollectionSuccess<*, *> -> of(lambda(), name) combine this.resources
            is Error -> Error(this.operationOutcome)
        }

    fun <C : Collection<R>, R : Resource> operateList(lambda: () -> C): OperationResult<C> =
        when (this) {
            is ResourceSuccess -> ofCollection(lambda())
            is CollectionSuccess<*, *> -> ofCollection(lambda())
            is Error -> Error(this.operationOutcome)
        }

    @Suppress("UNCHECKED_CAST")
    fun <C : Collection<R>, R : Resource> operateResourceList(lambda: (T) -> C): OperationResult<C> =
        when (this) {
            is ResourceSuccess -> ofCollection(lambda(this.resource))
            is CollectionSuccess<*, *> -> ofCollection(lambda(this.resource as T))
            is Error -> Error(this.operationOutcome)
        }

    @Suppress("UNCHECKED_CAST")
    fun <R : Resource> operateResource(lambda: (T) -> R): OperationResult<R> =
        when (this) {
            is ResourceSuccess -> of(lambda(this.resource))
            is CollectionSuccess<*, *> -> of(lambda(this.resource as T))
            is Error -> Error(this.operationOutcome)
        }

    @Suppress("UNCHECKED_CAST")
    fun <R : Resource> operateResource(name: String, lambda: (T) -> R): OperationResult<R> =
        when (this) {
            is ResourceSuccess -> of(lambda(this.resource), name)
            is CollectionSuccess<*, *> -> of(lambda(this.resource as T), name)
            is Error -> Error(this.operationOutcome)
        }

    @Suppress("UNCHECKED_CAST")
    fun <R : Resource> operateResourceCombined(lambda: (T) -> R): OperationResult<R> =
        when (this) {
            is ResourceSuccess -> of(lambda(this.resource)) combine this.resources
            is CollectionSuccess<*, *> -> of(lambda(this.resource as T)) combine this.resources
            is Error -> Error(this.operationOutcome)
        }

    @Suppress("UNCHECKED_CAST")
    fun <R : Resource> operateResourceCombined(name: String, lambda: (T) -> R): OperationResult<R> =
        when (this) {
            is ResourceSuccess -> of(lambda(this.resource), name) combine this.resources
            is CollectionSuccess<*, *> -> of(lambda(this.resource as T), name) combine this.resources
            is Error -> Error(this.operationOutcome)
        }

    fun <R : Resource> operateParameters(name: String, lambda: (Parameters) -> R): OperationResult<R> {
        return when (this) {
            is ResourceSuccess -> of(lambda(this.toParameters()), name)
            is CollectionSuccess<*, *> -> of(lambda(this.toParameters()), name)
            is Error -> Error(this.operationOutcome)
        }
    }

    fun <R : Resource> operateParameters(lambda: (Parameters) -> R): OperationResult<R> {
        return when (this) {
            is ResourceSuccess -> of(lambda(this.toParameters()))
            is CollectionSuccess<*, *> -> of(lambda(this.toParameters()))
            is Error -> Error(this.operationOutcome)
        }
    }

    fun <R : Resource> operateParametersCombined(name: String, lambda: (Parameters) -> R): OperationResult<R> {
        return when (this) {
            is ResourceSuccess -> of(lambda(this.toParameters()), name) combine this.resources
            is CollectionSuccess<*, *> -> of(lambda(this.toParameters()), name) combine this.resources
            is Error -> Error(this.operationOutcome)
        }
    }

    fun <R : Resource> operateParametersCombined(lambda: (Parameters) -> R): OperationResult<R> {
        return when (this) {
            is ResourceSuccess -> of(lambda(this.toParameters())) combine this.resources
            is CollectionSuccess<*, *> -> of(lambda(this.toParameters())) combine this.resources
            is Error -> Error(this.operationOutcome)
        }
    }

    /**
     * Helper function to combine a given List<ResourceHolder> with an OperationResult.SuccessResource's resources
     * If the OperationResult is Error then the method just returns
     */
    private infix fun combine(resources: List<ResourceHolder>): OperationResult<T> {
        return when (this) {
            is ResourceSuccess -> {
                this.resources.addAll(resources)
                this
            }

            is CollectionSuccess<*, *> -> {
                this.resources.addAll(resources)
                this
            }

            is Error -> this
        }
    }

    private fun toParameters(): Parameters {
        return this.resources
            .map { it.resource.toParameterComponent(it.name) }
            .let { Parameters().apply { parameter = it } }
    }

    private fun toBundle(): Bundle {
        return this.resources
            .map { it.resource.toBundleEntryComponent() }
            .let { Bundle().apply { entry = it } }
    }
}