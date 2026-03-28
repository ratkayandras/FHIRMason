package dev.ratkay.spring

import ca.uhn.fhir.rest.server.IResourceProvider
import dev.ratkay.operation.AsyncOperationResult
import dev.ratkay.operation.OperationResult
import org.hl7.fhir.r4.model.Base
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.Parameters

/**
 * Convenience base class for HAPI FHIR `IResourceProvider` implementations that use FHIRMason
 * to build their responses.
 *
 * Subclasses inherit helper methods for creating pre-configured pipelines and for converting
 * the results into FHIR [Parameters] or [Bundle] resources.
 *
 * Example:
 * ```kotlin
 * @Component
 * class PatientOperationProvider(fhirMason: FhirMasonFactory) :
 *     FhirMasonOperationProvider(fhirMason) {
 *
 *     override fun getResourceType() = Patient::class.java
 *
 *     @Operation(name = "\$summary")
 *     fun summary(@IdParam id: IdType): Parameters =
 *         parameters {
 *             pipeline(fetchPatient(id))
 *                 .add("encounter") { fetchEncounter(id) }
 *                 .add("coverage")  { fetchCoverage(id) }
 *         }
 *
 *     @Operation(name = "\$bundle")
 *     fun bundle(@IdParam id: IdType): Bundle =
 *         bundle(Bundle.BundleType.COLLECTION) {
 *             pipeline(fetchPatient(id))
 *                 .addAll("observations") { fetchObservations(id) }
 *         }
 * }
 * ```
 */
abstract class FhirMasonOperationProvider(private val factory: FhirMasonFactory) : IResourceProvider {

    /**
     * Starts a FHIRMason pipeline with [resource] as the seed value, using the factory's
     * configured [dev.ratkay.operation.ErrorStrategy] and optional timing.
     */
    protected fun <T : Base> pipeline(resource: T): OperationResult<T> = factory.pipeline(resource)

    /**
     * Starts an [AsyncOperationResult] DAG with the factory's configured timing.
     */
    protected fun asyncPipeline(): AsyncOperationResult = factory.asyncPipeline()

    /**
     * DSL helper: builds a [Parameters] resource from the [OperationResult] produced by [block].
     *
     * ```kotlin
     * parameters {
     *     pipeline(patient)
     *         .add("encounter") { fetchEncounter(patientId) }
     * }
     * ```
     */
    protected fun parameters(block: () -> OperationResult<*>): Parameters = block().toParameters()

    /**
     * DSL helper: builds a [Bundle] of the given [type] from the [OperationResult] produced
     * by [block].
     */
    protected fun bundle(type: Bundle.BundleType = Bundle.BundleType.COLLECTION, block: () -> OperationResult<*>): Bundle =
        block().toBundle(type)
}
