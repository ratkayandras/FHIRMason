package dev.ratkay

import dev.ratkay.extension.toParameterComponent
import org.hl7.fhir.r4.model.*

fun getParametersWithPatientAndOperationOutcome(): Parameters {
    return Parameters().apply {
        addParameter(Patient().apply {
            name = listOf(HumanName().setText("Mr. Test"))
        }.toParameterComponent("patient"))
        addParameter(OperationOutcome().apply {
            issue = listOf(OperationOutcome.OperationOutcomeIssueComponent().apply {
                details = CodeableConcept(Coding("system", "code", "display"))
            })
        }.toParameterComponent("operationOutcome"))
    }
}

fun getParametersWithTwoPatients(): Parameters {
    return Parameters().apply {
        addParameter(Patient().apply {
            name = listOf(HumanName().setText("Mr. Test"))
        }.toParameterComponent("patient"))
        addParameter(Patient().apply {
            name = listOf(HumanName().setText("Mr. Test 2"))
        }.toParameterComponent("patient"))
    }
}

fun getParametersWithStringValue(): Parameters {
    return Parameters().apply {
        addParameter(Parameters.ParametersParameterComponent().apply {
            name = "name"
            value = StringType("stringValue")
        })
    }
}