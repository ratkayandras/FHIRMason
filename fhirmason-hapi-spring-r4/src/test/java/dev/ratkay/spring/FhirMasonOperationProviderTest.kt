package dev.ratkay.spring

import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.Parameters
import org.hl7.fhir.r4.model.Patient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class FhirMasonOperationProviderTest {

    private val factory = FhirMasonFactory(FhirMasonProperties())

    private inner class TestProvider : FhirMasonOperationProvider(factory) {
        override fun getResourceType() = Patient::class.java
        fun doPipeline(p: Patient) = pipeline(p)
        fun doAsyncPipeline() = asyncPipeline()
        fun doParameters(block: () -> dev.ratkay.operation.r4.OperationResult<*>) = parameters(block)
        fun doBundle(type: Bundle.BundleType = Bundle.BundleType.COLLECTION, block: () -> dev.ratkay.operation.r4.OperationResult<*>) = bundle(type, block)
    }

    private val provider = TestProvider()
    private fun patient(id: String = "p1") = Patient().apply { setId(id) }

    // ── pipeline ──────────────────────────────────────────────────────────────

    @Test
    fun `pipeline delegates to factory and seeds with resource`() {
        val p = patient()
        val result = provider.doPipeline(p)
        assertEquals(p, result.getResult())
    }

    @Test
    fun `pipeline returns a new OperationResult for each call`() {
        val result1 = provider.doPipeline(patient("a"))
        val result2 = provider.doPipeline(patient("b"))
        assertNotNull(result1)
        assertNotNull(result2)
        assertEquals("a", result1.getResult()?.idElement?.idPart)
        assertEquals("b", result2.getResult()?.idElement?.idPart)
    }

    // ── asyncPipeline ─────────────────────────────────────────────────────────

    @Test
    fun `asyncPipeline returns a new AsyncOperationResult`() {
        val dag = provider.doAsyncPipeline()
        assertNotNull(dag)
    }

    // ── parameters DSL helper ─────────────────────────────────────────────────

    @Test
    fun `parameters DSL helper converts pipeline result to FHIR Parameters`() {
        val p = patient()
        val params = provider.doParameters { provider.doPipeline(p) }
        assertInstanceOf(Parameters::class.java, params)
    }

    @Test
    fun `parameters DSL helper includes accumulated resources`() {
        val p = patient()
        val params = provider.doParameters {
            provider.doPipeline(p).add("extra") { patient("extra") }
        }
        val hasExtra = params.parameter.any { it.name == "extra" }
        assertEquals(true, hasExtra)
    }

    // ── bundle DSL helper ─────────────────────────────────────────────────────

    @Test
    fun `bundle DSL helper converts pipeline result to FHIR Bundle`() {
        val p = patient()
        val b = provider.doBundle { provider.doPipeline(p) }
        assertInstanceOf(Bundle::class.java, b)
    }

    @Test
    fun `bundle DSL helper defaults to COLLECTION type`() {
        val b = provider.doBundle { provider.doPipeline(patient()) }
        assertEquals(Bundle.BundleType.COLLECTION, b.type)
    }

    @Test
    fun `bundle DSL helper respects specified bundle type`() {
        val b = provider.doBundle(Bundle.BundleType.TRANSACTION) { provider.doPipeline(patient()) }
        assertEquals(Bundle.BundleType.TRANSACTION, b.type)
    }
}
