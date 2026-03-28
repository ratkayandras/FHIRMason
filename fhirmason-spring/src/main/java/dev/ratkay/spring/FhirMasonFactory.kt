package dev.ratkay.spring

import dev.ratkay.operation.AsyncOperationResult
import dev.ratkay.operation.OperationResult
import org.hl7.fhir.r4.model.Base

/**
 * Spring-managed factory that creates pre-configured [OperationResult] pipelines and
 * [AsyncOperationResult] DAGs using the application's [FhirMasonProperties].
 *
 * Inject this bean wherever you need to start a FHIRMason pipeline:
 * ```kotlin
 * @Service
 * class PatientService(private val fhirMason: FhirMasonFactory) {
 *
 *     fun buildBundle(patient: Patient): Parameters =
 *         fhirMason.pipeline(patient)
 *             .add("encounter") { fetchEncounter(patient.idElement.idPart) }
 *             .toParameters()
 * }
 * ```
 */
class FhirMasonFactory(private val properties: FhirMasonProperties) {

    /**
     * Creates an [OperationResult] starting with [resource] as the seed value.
     * If `fhirmason.metrics.enabled` is `true` the pipeline will have timing enabled.
     */
    fun <T : Base> pipeline(resource: T): OperationResult<T> {
        var result = OperationResult.of(resource, errorStrategy = properties.errorStrategy)
        if (properties.metrics.enabled) result = result.timed()
        return result
    }

    /**
     * Creates an [AsyncOperationResult] DAG with optional timing enabled.
     * If `fhirmason.metrics.enabled` is `true` the DAG will have timing enabled.
     */
    fun asyncPipeline(): AsyncOperationResult {
        val dag = AsyncOperationResult()
        if (properties.metrics.enabled) dag.timed()
        return dag
    }
}
