package eu.ratkay.operation

import eu.ratkay.dto.ResourceHolder
import eu.ratkay.extension.getParameterName
import eu.ratkay.extension.hasError
import eu.ratkay.extension.toBundleEntryComponent
import eu.ratkay.extension.toParameterComponent
import org.hl7.fhir.instance.model.api.IBaseResource
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.OperationOutcome
import org.hl7.fhir.r4.model.Parameters
import org.hl7.fhir.r4.model.Resource

sealed class OperationResult<out T : Resource> private constructor(private val resources: MutableList<ResourceHolder>) {

    companion object {
        fun <T : Resource> of(resource: T, name: String? = null): OperationResult<T> {
            val paramName = name ?: resource.getParameterName()
            return if (resource is OperationOutcome && resource.hasIssue() && resource.hasError()) {
                Error(resource)
            } else {
                SuccessResource(paramName, resource)
            }
        }
    }

    fun asParameters(): IBaseResource {
        return when (this) {
            is SuccessResource<*> -> this.toParameters()
            is Error -> this.operationOutcome
        }
    }

    fun asBundle(): IBaseResource {
        return when (this) {
            is SuccessResource<*> -> this.toBundle()
            is Error -> this.operationOutcome
        }
    }

    private data class SuccessResource<T : Resource>(val name: String, val resource: T) : OperationResult<T>(
        mutableListOf(
            ResourceHolder(name, resource)
        )
    )

    private data class Error(val operationOutcome: OperationOutcome) : OperationResult<Nothing>(mutableListOf())

    fun <R : Resource> operate(lambda: () -> R): OperationResult<R> {
        return when (this) {
            is SuccessResource -> of(lambda())
            is Error -> this
        }
    }

    fun <R : Resource> operate(name: String, lambda: () -> R): OperationResult<R> {
        return when (this) {
            is SuccessResource -> of(lambda(), name)
            is Error -> this
        }
    }

    fun <R : Resource> operateCombined(lambda: () -> R): OperationResult<R> {
        return when (this) {
            is SuccessResource -> of(lambda()) combine this.resources
            is Error -> this
        }
    }

    fun <R : Resource> operateCombined(name: String, lambda: () -> R): OperationResult<R> {
        return when (this) {
            is SuccessResource -> of(lambda(), name) combine this.resources
            is Error -> this
        }
    }

    fun <R : Resource> operateResource(lambda: (T) -> R): OperationResult<R> {
        return when (this) {
            is SuccessResource -> of(lambda(this.resource))
            is Error -> this
        }
    }

    fun <R : Resource> operateResource(name: String, lambda: (T) -> R): OperationResult<R> {
        return when (this) {
            is SuccessResource -> of(lambda(this.resource), name)
            is Error -> this
        }
    }

    fun <R : Resource> operateResourceCombined(lambda: (T) -> R): OperationResult<R> {
        return when (this) {
            is SuccessResource -> of(lambda(this.resource)) combine this.resources
            is Error -> this
        }
    }

    fun <R : Resource> operateResourceCombined(name: String, lambda: (T) -> R): OperationResult<R> {
        return when (this) {
            is SuccessResource -> of(lambda(this.resource), name) combine this.resources
            is Error -> this
        }
    }

    fun <R : Resource> operateParameters(lambda: (Parameters) -> R): OperationResult<R> {
        return when (this) {
            is SuccessResource -> of(lambda(this.toParameters()))
            is Error -> this
        }
    }

    fun <R : Resource> operateParameters(name: String, lambda: (Parameters) -> R): OperationResult<R> {
        return when (this) {
            is SuccessResource -> of(lambda(this.toParameters()), name)
            is Error -> this
        }
    }

    fun <R : Resource> operateParametersCombined(lambda: (Parameters) -> R): OperationResult<R> {
        return when (this) {
            is SuccessResource -> of(lambda(this.toParameters())) combine this.resources
            is Error -> this
        }
    }

    fun <R : Resource> operateParametersCombined(name: String, lambda: (Parameters) -> R): OperationResult<R> {
        return when (this) {
            is SuccessResource -> of(lambda(this.toParameters()), name) combine this.resources
            is Error -> this
        }
    }

    /**
     * Helper function to combine a given Parameters resource with a OperationResult.Source Parameters resource
     * If the OperationResult is Error then the method just returns
     */
    private infix fun <R : Resource> combine(resources: List<ResourceHolder>): OperationResult<R> {
        return when (this) {
            is SuccessResource -> {
                this.resources.addAll(resources)
                @Suppress("UNCHECKED_CAST")
                this as OperationResult<R>
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