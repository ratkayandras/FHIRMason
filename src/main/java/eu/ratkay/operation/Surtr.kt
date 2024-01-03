package eu.ratkay.operation

import eu.ratkay.extension.hasError
import eu.ratkay.extension.toParameters
import org.hl7.fhir.instance.model.api.IBaseResource
import org.hl7.fhir.r4.model.OperationOutcome
import org.hl7.fhir.r4.model.Parameters
import org.hl7.fhir.r4.model.Resource

sealed class Surtr private constructor(private val parameters: Parameters) {

    companion object {
        fun of(resource: Resource, name: String = "resource"): Surtr {
            return if (resource is OperationOutcome && resource.hasIssue() && resource.hasError()) {
                Error(resource)
            } else {
                SuccessResource(name, resource)
            }
        }
    }

    val resource: IBaseResource
        get() = when (this) {
            is SuccessResource -> this.parameters
            is Error -> this.operationOutcome
        }

    private data class SuccessResource(val name: String, val source: Resource): Surtr(source.toParameters(name))
    private data class Error(val operationOutcome: OperationOutcome): Surtr(Parameters())

    fun operate(lambda: () -> Resource): Surtr {
        return if (this is SuccessResource) of(lambda.invoke()) else this
    }

    fun operateCombined(lambda: () -> Resource): Surtr {
        return if (this is SuccessResource) of(lambda.invoke()) combine this.parameters else this
    }

    fun operateResource(lambda: (Resource) -> Resource): Surtr {
        return if (this is SuccessResource) of(lambda.invoke(this.source)) else this
    }

    fun operateParameters(lambda: (Parameters) -> Resource): Surtr {
        return if (this is SuccessResource) of(lambda.invoke(this.resource as Parameters)) else this
    }

    /**
     * Helper function to combine a given Parameters resource with a Surtr.Source Parameters resource
     * If the Surtr is Error then the method just returns
     */
    private infix fun combine(parameters: Parameters): Surtr {
        return if (this is SuccessResource) {
            val newParams = parameters.parameter + this.parameters.parameter
            of(Parameters().apply {
                parameter = newParams
            })
        } else {
            this
        }
    }
}