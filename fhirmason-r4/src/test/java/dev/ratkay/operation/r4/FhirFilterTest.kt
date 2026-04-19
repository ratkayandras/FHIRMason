package dev.ratkay.operation.r4

import org.hl7.fhir.r4.model.Base
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.Extension
import org.hl7.fhir.r4.model.Identifier
import org.hl7.fhir.r4.model.Meta
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.StringType
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FhirFilterTest {

    // ── hasAllExtensions ──────────────────────────────────────────────────────

    @Test
    fun `hasAllExtensions returns true when resource has all given URLs`() {
        val patient = patient().apply {
            addExtension("http://example.org/a", StringType("1"))
            addExtension("http://example.org/b", StringType("2"))
        }
        assertTrue(FhirFilter.hasAllExtensions("http://example.org/a", "http://example.org/b")(patient))
    }

    @Test
    fun `hasAllExtensions returns false when resource is missing one URL`() {
        val patient = patient().apply { addExtension("http://example.org/a", StringType("1")) }
        assertFalse(FhirFilter.hasAllExtensions("http://example.org/a", "http://example.org/b")(patient))
    }

    // ── hasAnyExtension ───────────────────────────────────────────────────────

    @Test
    fun `hasAnyExtension returns true when resource has at least one URL`() {
        val patient = patient().apply { addExtension("http://example.org/a", StringType("1")) }
        assertTrue(FhirFilter.hasAnyExtension("http://example.org/a", "http://example.org/b")(patient))
    }

    @Test
    fun `hasAnyExtension returns false when resource has none of the URLs`() {
        val patient = patient()
        assertFalse(FhirFilter.hasAnyExtension("http://example.org/a")(patient))
    }

    // ── hasExtensionWithValueType ─────────────────────────────────────────────

    @Test
    fun `hasExtensionWithValueType KClass returns true when value type matches`() {
        val patient = patient().apply { addExtension("http://example.org/flag", StringType("yes")) }
        assertTrue(FhirFilter.hasExtensionWithValueType("http://example.org/flag", StringType::class)(patient))
    }

    @Test
    fun `hasExtensionWithValueType reified returns true when value type matches`() {
        val patient = patient().apply { addExtension("http://example.org/flag", StringType("yes")) }
        assertTrue(FhirFilter.hasExtensionWithValueType<StringType>("http://example.org/flag")(patient))
    }

    @Test
    fun `hasExtensionWithValueType Class overload returns true when value type matches`() {
        val patient = patient().apply { addExtension("http://example.org/flag", StringType("yes")) }
        assertTrue(FhirFilter.hasExtensionWithValueType("http://example.org/flag", StringType::class.java)(patient))
    }

    @Test
    fun `hasExtensionWithValueType returns false when URL absent`() {
        val patient = patient()
        assertFalse(FhirFilter.hasExtensionWithValueType<StringType>("http://example.org/missing")(patient))
    }

    // ── hasExtensionValueMatching ─────────────────────────────────────────────

    @Test
    fun `hasExtensionValueMatching reified returns true when value satisfies predicate`() {
        val patient = patient().apply { addExtension("http://example.org/score", StringType("high")) }
        assertTrue(
            FhirFilter.hasExtensionValueMatching<StringType>("http://example.org/score") { it.value == "high" }(patient)
        )
    }

    @Test
    fun `hasExtensionValueMatching Class overload returns true when value satisfies predicate`() {
        val patient = patient().apply { addExtension("http://example.org/score", StringType("high")) }
        assertTrue(
            FhirFilter.hasExtensionValueMatching("http://example.org/score", StringType::class.java) { it.value == "high" }(patient)
        )
    }

    @Test
    fun `hasExtensionValueMatching returns false when predicate not satisfied`() {
        val patient = patient().apply { addExtension("http://example.org/score", StringType("low")) }
        assertFalse(
            FhirFilter.hasExtensionValueMatching<StringType>("http://example.org/score") { it.value == "high" }(patient)
        )
    }

    // ── hasIdentifierWithSystem ───────────────────────────────────────────────

    @Test
    fun `hasIdentifierWithSystem returns true when system matches`() {
        val patient = patient().apply {
            addIdentifier(Identifier().setSystem("http://example.org/mrn").setValue("123"))
        }
        assertTrue(FhirFilter.hasIdentifierWithSystem("http://example.org/mrn")(patient))
    }

    @Test
    fun `hasIdentifierWithSystem returns false when system does not match`() {
        val patient = patient().apply {
            addIdentifier(Identifier().setSystem("http://other.org/mrn").setValue("123"))
        }
        assertFalse(FhirFilter.hasIdentifierWithSystem("http://example.org/mrn")(patient))
    }

    // ── hasIdentifierWithValue ────────────────────────────────────────────────

    @Test
    fun `hasIdentifierWithValue returns true when value matches`() {
        val patient = patient().apply {
            addIdentifier(Identifier().setSystem("http://example.org/mrn").setValue("abc"))
        }
        assertTrue(FhirFilter.hasIdentifierWithValue("abc")(patient))
    }

    // ── hasIdentifier ─────────────────────────────────────────────────────────

    @Test
    fun `hasIdentifier returns true when both system and value match`() {
        val patient = patient().apply {
            addIdentifier(Identifier().setSystem("http://example.org/mrn").setValue("123"))
        }
        assertTrue(FhirFilter.hasIdentifier("http://example.org/mrn", "123")(patient))
    }

    @Test
    fun `hasIdentifier returns false when value does not match`() {
        val patient = patient().apply {
            addIdentifier(Identifier().setSystem("http://example.org/mrn").setValue("999"))
        }
        assertFalse(FhirFilter.hasIdentifier("http://example.org/mrn", "123")(patient))
    }

    // ── hasMetaTagWithSystem ──────────────────────────────────────────────────

    @Test
    fun `hasMetaTagWithSystem returns true when tag system matches`() {
        val patient = patientWithTag("http://example.org/tags", "reviewed")
        assertTrue(FhirFilter.hasMetaTagWithSystem("http://example.org/tags")(patient))
    }

    @Test
    fun `hasMetaTagWithSystem returns false when tag system does not match`() {
        val patient = patientWithTag("http://other.org/tags", "reviewed")
        assertFalse(FhirFilter.hasMetaTagWithSystem("http://example.org/tags")(patient))
    }

    // ── hasMetaTagWithCode ────────────────────────────────────────────────────

    @Test
    fun `hasMetaTagWithCode returns true when tag code matches`() {
        val patient = patientWithTag("http://example.org/tags", "reviewed")
        assertTrue(FhirFilter.hasMetaTagWithCode("reviewed")(patient))
    }

    // ── hasMetaTag ────────────────────────────────────────────────────────────

    @Test
    fun `hasMetaTag returns true when both system and code match`() {
        val patient = patientWithTag("http://example.org/tags", "reviewed")
        assertTrue(FhirFilter.hasMetaTag("http://example.org/tags", "reviewed")(patient))
    }

    @Test
    fun `hasMetaTag returns false when code does not match`() {
        val patient = patientWithTag("http://example.org/tags", "pending")
        assertFalse(FhirFilter.hasMetaTag("http://example.org/tags", "reviewed")(patient))
    }

    // ── hasMetaSecurityWithSystem ─────────────────────────────────────────────

    @Test
    fun `hasMetaSecurityWithSystem returns true when security system matches`() {
        val patient = patientWithSecurity("http://example.org/sec", "R")
        assertTrue(FhirFilter.hasMetaSecurityWithSystem("http://example.org/sec")(patient))
    }

    // ── hasMetaSecurityWithCode ───────────────────────────────────────────────

    @Test
    fun `hasMetaSecurityWithCode returns true when security code matches`() {
        val patient = patientWithSecurity("http://example.org/sec", "R")
        assertTrue(FhirFilter.hasMetaSecurityWithCode("R")(patient))
    }

    // ── hasMetaSecurity ───────────────────────────────────────────────────────

    @Test
    fun `hasMetaSecurity returns true when both system and code match`() {
        val patient = patientWithSecurity("http://example.org/sec", "R")
        assertTrue(FhirFilter.hasMetaSecurity("http://example.org/sec", "R")(patient))
    }

    @Test
    fun `hasMetaSecurity returns false when code does not match`() {
        val patient = patientWithSecurity("http://example.org/sec", "N")
        assertFalse(FhirFilter.hasMetaSecurity("http://example.org/sec", "R")(patient))
    }

    // ── hasMetaProfile ────────────────────────────────────────────────────────

    @Test
    fun `hasMetaProfile returns true when profile URL is present`() {
        val patient = Patient().apply { meta = Meta().addProfile("http://example.org/profile/v1") }
        assertTrue(FhirFilter.hasMetaProfile("http://example.org/profile/v1")(patient))
    }

    @Test
    fun `hasMetaProfile returns false when profile URL is absent`() {
        val patient = patient()
        assertFalse(FhirFilter.hasMetaProfile("http://example.org/profile/v1")(patient))
    }

    // ── Composition ───────────────────────────────────────────────────────────

    @Test
    fun `predicates compose correctly with and-logic`() {
        val patient = patient().apply {
            addExtension("http://example.org/enrolled", StringType("true"))
            addIdentifier(Identifier().setSystem("http://example.org/mrn").setValue("123"))
        }
        val composed: (Base) -> Boolean = { r ->
            FhirFilter.hasAllExtensions("http://example.org/enrolled")(r) &&
                FhirFilter.hasIdentifierWithSystem("http://example.org/mrn")(r)
        }
        assertTrue(composed(patient))

        val noIdentifier = patient().apply {
            addExtension("http://example.org/enrolled", StringType("true"))
        }
        assertFalse(composed(noIdentifier))
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun patient() = Patient()

    private fun patientWithTag(system: String, code: String) = Patient().apply {
        meta = Meta().addTag(Coding().setSystem(system).setCode(code))
    }

    private fun patientWithSecurity(system: String, code: String) = Patient().apply {
        meta = Meta().addSecurity(Coding().setSystem(system).setCode(code))
    }
}
