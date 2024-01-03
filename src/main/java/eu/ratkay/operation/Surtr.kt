package eu.ratkay.operation

import eu.ratkay.extension.hasError
import eu.ratkay.extension.toParameters
import org.hl7.fhir.instance.model.api.IBaseResource
import org.hl7.fhir.r4.model.OperationOutcome
import org.hl7.fhir.r4.model.Parameters
import org.hl7.fhir.r4.model.Resource

sealed class Surtr<out T: Resource> private constructor(private var parameters: Parameters) {

    companion object {
        fun <T: Resource> of(resource: T, name: String = "resource"): Surtr<T> {
            return if (resource is OperationOutcome && resource.hasIssue() && resource.hasError()) {
                Error(resource)
            } else {
                SuccessResource(name, resource)
            }
        }
    }

    val resource: IBaseResource
        get() = when (this) {
            is SuccessResource<*> -> this.parameters
            is Error -> this.operationOutcome
        }

    private data class SuccessResource<T: Resource>(val name: String, val source: T): Surtr<T>(source.toParameters(name))
    private data class Error(val operationOutcome: OperationOutcome): Surtr<Nothing>(Parameters())

    fun <R: Resource> operate(lambda: () -> R): Surtr<R> {
        return when (this) {
            is SuccessResource -> of(lambda.invoke())
            is Error -> this
        }
    }

    fun <R: Resource> operateCombined(lambda: () -> R): Surtr<R> {
        return when (this) {
            is SuccessResource -> of(lambda.invoke()) combine this.parameters
            is Error -> this
        }
    }

    fun <R: Resource> operateResource(lambda: (T) -> R): Surtr<R> {
        return when (this) {
            is SuccessResource -> of(lambda.invoke(this.source))
            is Error -> this
        }
    }

    fun <R: Resource> operateParameters(lambda: (Parameters) -> R): Surtr<R> {
        return when (this) {
            is SuccessResource -> of(lambda.invoke(this.parameters))
            is Error -> this
        }
    }

    /**
     * Helper function to combine a given Parameters resource with a Surtr.Source Parameters resource
     * If the Surtr is Error then the method just returns
     */
    private infix fun <R: Resource> combine(parameters: Parameters): Surtr<R> {
        return when (this) {
            is SuccessResource -> {
                val newParams = parameters.parameter + this.parameters.parameter
                val combinedParameters = Parameters().apply { parameter = newParams }
                this.parameters = combinedParameters
                @Suppress("UNCHECKED_CAST")
                this as Surtr<R>
            }
            is Error -> this
        }
    }
}