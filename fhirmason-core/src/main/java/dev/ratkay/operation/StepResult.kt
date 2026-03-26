package dev.ratkay.operation

import org.hl7.fhir.r4.model.OperationOutcome

sealed class StepResult<T> {
    class Success<T>(val value: T) : StepResult<T>()
    class Failure<T>(val outcome: OperationOutcome) : StepResult<T>()
}
