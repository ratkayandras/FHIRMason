package eu.ratkay.operation

import eu.ratkay.extension.getParameterName
import eu.ratkay.extension.hasError
import eu.ratkay.extension.toParameters
import org.hl7.fhir.instance.model.api.IBaseResource
import org.hl7.fhir.r4.model.OperationOutcome
import org.hl7.fhir.r4.model.Parameters
import org.hl7.fhir.r4.model.Resource

sealed class OperationResult<out T: Resource> private constructor(private var parameters: Parameters) {

    companion object {
        fun <T: Resource> of(resource: T, name: String? = null): OperationResult<T> {
            val paramName = name ?: resource.getParameterName()
            return if (resource is OperationOutcome && resource.hasIssue() && resource.hasError()) {
                Error(resource)
            } else {
                SuccessResource(paramName, resource)
            }
        }
    }

    val resource: IBaseResource
        get() = when (this) {
            is SuccessResource<*> -> this.parameters
            is Error -> this.operationOutcome
        }

    private data class SuccessResource<T: Resource>(val name: String, val source: T): OperationResult<T>(source.toParameters(name))
    private data class Error(val operationOutcome: OperationOutcome): OperationResult<Nothing>(Parameters())

    fun <R: Resource> operate(lambda: () -> R): OperationResult<R> {
        return when (this) {
            is SuccessResource -> of(lambda.invoke())
            is Error -> this
        }
    }

    fun <R: Resource> operateCombined(lambda: () -> R): OperationResult<R> {
        return when (this) {
            is SuccessResource -> of(lambda.invoke()) combine this.parameters
            is Error -> this
        }
    }

    fun <R: Resource> operateResource(lambda: (T) -> R): OperationResult<R> {
        return when (this) {
            is SuccessResource -> of(lambda.invoke(this.source))
            is Error -> this
        }
    }

    fun <R: Resource> operateResourceCombined(lambda: (T) -> R): OperationResult<R> {
        return when (this) {
            is SuccessResource -> of(lambda.invoke(this.source)) combine this.parameters
            is Error -> this
        }
    }

    fun <R: Resource> operateParameters(lambda: (Parameters) -> R): OperationResult<R> {
        return when (this) {
            is SuccessResource -> of(lambda.invoke(this.parameters))
            is Error -> this
        }
    }

    fun <R: Resource> operateParametersCombined(lambda: (Parameters) -> R): OperationResult<R> {
        return when (this) {
            is SuccessResource -> of(lambda.invoke(this.parameters)) combine this.parameters
            is Error -> this
        }
    }

    /**
     * Helper function to combine a given Parameters resource with a OperationResult.Source Parameters resource
     * If the OperationResult is Error then the method just returns
     */
    private infix fun <R: Resource> combine(parameters: Parameters): OperationResult<R> {
        return when (this) {
            is SuccessResource -> {
                val newParams = parameters.parameter + this.parameters.parameter
                val combinedParameters = Parameters().apply { parameter = newParams }
                this.parameters = combinedParameters
                @Suppress("UNCHECKED_CAST")
                this as OperationResult<R>
            }
            is Error -> this
        }
    }
}