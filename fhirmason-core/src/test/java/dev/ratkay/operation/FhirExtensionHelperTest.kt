package dev.ratkay.operation

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.empty
import org.hamcrest.Matchers.hasSize
import org.hl7.fhir.r4.model.BooleanType
import org.hl7.fhir.r4.model.CodeableConcept
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.Extension
import org.hl7.fhir.r4.model.HumanName
import org.hl7.fhir.r4.model.IntegerType
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.StringType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Optional

class FhirExtensionHelperTest {

    private val URL_A = "http://example.com/ext-a"
    private val URL_B = "http://example.com/ext-b"
    private val URL_NESTED = "http://example.com/nested"

    // ── hasExtension ──────────────────────────────────────────────────────

    @Test
    fun `hasExtension returns true when URL present at root`() {
        val patient = Patient().apply { addExtension(URL_A, StringType("v")) }
        assertTrue(FhirExtensionHelper.hasExtension(patient, URL_A))
    }

    @Test
    fun `hasExtension returns false when URL absent`() {
        val patient = Patient().apply { addExtension(URL_B, StringType("v")) }
        assertFalse(FhirExtensionHelper.hasExtension(patient, URL_A))
    }

    @Test
    fun `hasExtension returns false when source has no extensions`() {
        assertFalse(FhirExtensionHelper.hasExtension(Patient(), URL_A))
    }

    @Test
    fun `hasExtension returns true when URL present on nested child element`() {
        val patient = Patient().apply {
            addName().addExtension(URL_A, StringType("nameExt"))
        }
        assertTrue(FhirExtensionHelper.hasExtension(patient, URL_A))
    }

    // ── getByUrl ──────────────────────────────────────────────────────────

    @Test
    fun `getByUrl returns extension when present at root`() {
        val patient = Patient().apply { addExtension(URL_A, StringType("hello")) }
        val ext = FhirExtensionHelper.getByUrl(patient, URL_A)
        assertEquals(URL_A, ext?.url)
        assertEquals("hello", (ext?.value as? StringType)?.value)
    }

    @Test
    fun `getByUrl returns null when URL absent`() {
        val patient = Patient().apply { addExtension(URL_B, StringType("v")) }
        assertNull(FhirExtensionHelper.getByUrl(patient, URL_A))
    }

    @Test
    fun `getByUrl returns first extension when multiple with same URL`() {
        val patient = Patient().apply {
            addExtension(URL_A, StringType("first"))
            addExtension(URL_A, StringType("second"))
        }
        assertEquals("first", (FhirExtensionHelper.getByUrl(patient, URL_A)?.value as? StringType)?.value)
    }

    @Test
    fun `getByUrl finds extension on nested child element`() {
        val patient = Patient().apply {
            addName().addExtension(URL_A, StringType("nameExt"))
        }
        val ext = FhirExtensionHelper.getByUrl(patient, URL_A)
        assertEquals(URL_A, ext?.url)
    }

    // ── getAllByUrl ───────────────────────────────────────────────────────

    @Test
    fun `getAllByUrl returns all root-level matches`() {
        val patient = Patient().apply {
            addExtension(URL_A, StringType("v1"))
            addExtension(URL_A, StringType("v2"))
            addExtension(URL_B, StringType("other"))
        }
        val results = FhirExtensionHelper.getAllByUrl(patient, URL_A)
        assertThat(results, hasSize(2))
        assertEquals("v1", (results[0].value as StringType).value)
        assertEquals("v2", (results[1].value as StringType).value)
    }

    @Test
    fun `getAllByUrl returns empty list when URL absent`() {
        val patient = Patient().apply { addExtension(URL_B, StringType("v")) }
        assertThat(FhirExtensionHelper.getAllByUrl(patient, URL_A), empty())
    }

    @Test
    fun `getAllByUrl finds matches at multiple depths`() {
        val patient = Patient().apply {
            addExtension(URL_A, StringType("root"))
            addName().addExtension(URL_A, StringType("name"))
        }
        val results = FhirExtensionHelper.getAllByUrl(patient, URL_A)
        assertThat(results, hasSize(2))
    }

    // ── getByUrlOptional ──────────────────────────────────────────────────

    @Test
    fun `getByUrlOptional returns non-empty Optional when present`() {
        val patient = Patient().apply { addExtension(URL_A, StringType("v")) }
        val opt = FhirExtensionHelper.getByUrlOptional(patient, URL_A)
        assertTrue(opt.isPresent)
        assertEquals(URL_A, opt.get().url)
    }

    @Test
    fun `getByUrlOptional returns empty Optional when absent`() {
        assertEquals(Optional.empty<Extension>(), FhirExtensionHelper.getByUrlOptional(Patient(), URL_A))
    }

    // ── getValueAs (reified) ──────────────────────────────────────────────

    @Test
    fun `getValueAs returns correctly typed value`() {
        val patient = Patient().apply { addExtension(URL_A, StringType("hello")) }
        val value = FhirExtensionHelper.getValueAs<StringType>(patient, URL_A)
        assertEquals("hello", value?.value)
    }

    @Test
    fun `getValueAs returns null when extension absent`() {
        assertNull(FhirExtensionHelper.getValueAs<StringType>(Patient(), URL_A))
    }

    @Test
    fun `getValueAs returns null when value is wrong type`() {
        val patient = Patient().apply { addExtension(URL_A, BooleanType(true)) }
        assertNull(FhirExtensionHelper.getValueAs<StringType>(patient, URL_A))
    }

    @Test
    fun `getValueAs returns null when extension has no value`() {
        val ext = Extension(URL_A)
        val patient = Patient().apply { addExtension(ext) }
        assertNull(FhirExtensionHelper.getValueAs<StringType>(patient, URL_A))
    }

    // ── getValueAsOptional ────────────────────────────────────────────────

    @Test
    fun `getValueAsOptional returns non-empty Optional with correct value`() {
        val patient = Patient().apply { addExtension(URL_A, StringType("hello")) }
        val opt = FhirExtensionHelper.getValueAsOptional(patient, URL_A, StringType::class.java)
        assertTrue(opt.isPresent)
        assertEquals("hello", opt.get().value)
    }

    @Test
    fun `getValueAsOptional returns empty Optional when absent`() {
        val opt = FhirExtensionHelper.getValueAsOptional(Patient(), URL_A, StringType::class.java)
        assertFalse(opt.isPresent)
    }

    @Test
    fun `getValueAsOptional returns empty Optional when value is wrong type`() {
        val patient = Patient().apply { addExtension(URL_A, BooleanType(true)) }
        val opt = FhirExtensionHelper.getValueAsOptional(patient, URL_A, StringType::class.java)
        assertFalse(opt.isPresent)
    }

    // ── getAllValuesAs (reified) ───────────────────────────────────────────

    @Test
    fun `getAllValuesAs reified returns all typed values`() {
        val patient = Patient().apply {
            addExtension(URL_A, StringType("a"))
            addExtension(URL_A, StringType("b"))
        }
        val values = FhirExtensionHelper.getAllValuesAs<StringType>(patient, URL_A)
        assertThat(values, hasSize(2))
        assertEquals("a", values[0].value)
        assertEquals("b", values[1].value)
    }

    @Test
    fun `getAllValuesAs reified skips wrong-typed entries`() {
        val patient = Patient().apply {
            addExtension(URL_A, StringType("ok"))
            addExtension(URL_A, BooleanType(true))
        }
        val values = FhirExtensionHelper.getAllValuesAs<StringType>(patient, URL_A)
        assertThat(values, hasSize(1))
        assertEquals("ok", values[0].value)
    }

    @Test
    fun `getAllValuesAs reified returns empty list when none match`() {
        assertThat(FhirExtensionHelper.getAllValuesAs<StringType>(Patient(), URL_A), empty())
    }

    // ── getAllValuesAs (Class param) ───────────────────────────────────────

    @Test
    fun `getAllValuesAs Class param returns all typed values`() {
        val patient = Patient().apply {
            addExtension(URL_A, StringType("x"))
            addExtension(URL_A, StringType("y"))
        }
        val values = FhirExtensionHelper.getAllValuesAs(patient, URL_A, StringType::class.java)
        assertThat(values, hasSize(2))
    }

    @Test
    fun `getAllValuesAs Class param skips wrong-typed entries`() {
        val patient = Patient().apply {
            addExtension(URL_A, StringType("ok"))
            addExtension(URL_A, IntegerType(42))
        }
        val values = FhirExtensionHelper.getAllValuesAs(patient, URL_A, StringType::class.java)
        assertThat(values, hasSize(1))
        assertEquals("ok", values[0].value)
    }

    @Test
    fun `getAllValuesAs Class param returns empty list when none match`() {
        assertThat(FhirExtensionHelper.getAllValuesAs(Patient(), URL_A, StringType::class.java), empty())
    }

    // ── getNested ─────────────────────────────────────────────────────────

    @Test
    fun `getNested returns nested sub-extension`() {
        val patient = Patient().apply {
            addExtension(Extension(URL_A).apply {
                addExtension(URL_NESTED, StringType("nestedValue"))
            })
        }
        val nested = FhirExtensionHelper.getNested(patient, URL_A, URL_NESTED)
        assertEquals(URL_NESTED, nested?.url)
        assertEquals("nestedValue", (nested?.value as? StringType)?.value)
    }

    @Test
    fun `getNested returns null when parent extension absent`() {
        assertNull(FhirExtensionHelper.getNested(Patient(), URL_A, URL_NESTED))
    }

    @Test
    fun `getNested returns null when nested extension absent`() {
        val patient = Patient().apply { addExtension(URL_A, StringType("v")) }
        assertNull(FhirExtensionHelper.getNested(patient, URL_A, URL_NESTED))
    }

    // ── getNestedOptional ─────────────────────────────────────────────────

    @Test
    fun `getNestedOptional returns non-empty Optional when nested present`() {
        val patient = Patient().apply {
            addExtension(Extension(URL_A).apply {
                addExtension(URL_NESTED, StringType("v"))
            })
        }
        val opt = FhirExtensionHelper.getNestedOptional(patient, URL_A, URL_NESTED)
        assertTrue(opt.isPresent)
        assertEquals(URL_NESTED, opt.get().url)
    }

    @Test
    fun `getNestedOptional returns empty Optional when absent`() {
        assertFalse(FhirExtensionHelper.getNestedOptional(Patient(), URL_A, URL_NESTED).isPresent)
    }

    // ── getAllNested ──────────────────────────────────────────────────────

    @Test
    fun `getAllNested returns all nested sub-extensions`() {
        val patient = Patient().apply {
            addExtension(Extension(URL_A).apply {
                addExtension(URL_NESTED, StringType("n1"))
                addExtension(URL_NESTED, StringType("n2"))
                addExtension(URL_B, StringType("other"))
            })
        }
        val nested = FhirExtensionHelper.getAllNested(patient, URL_A, URL_NESTED)
        assertThat(nested, hasSize(2))
        assertEquals("n1", (nested[0].value as StringType).value)
        assertEquals("n2", (nested[1].value as StringType).value)
    }

    @Test
    fun `getAllNested returns empty list when parent extension absent`() {
        assertThat(FhirExtensionHelper.getAllNested(Patient(), URL_A, URL_NESTED), empty())
    }

    // ── Deep traversal ────────────────────────────────────────────────────

    @Test
    fun `finds extension on HumanName child element of Patient`() {
        val patient = Patient().apply {
            addName().addExtension(URL_A, StringType("nameExt"))
        }
        val ext = FhirExtensionHelper.getByUrl(patient, URL_A)
        assertEquals(URL_A, ext?.url)
        assertEquals("nameExt", (ext?.value as? StringType)?.value)
    }

    @Test
    fun `finds extension on Coding inside a CodeableConcept child`() {
        val patient = Patient().apply {
            maritalStatus = CodeableConcept().apply {
                addCoding(Coding().apply {
                    system = "http://example.com"
                    code = "M"
                    addExtension(URL_A, StringType("codingExt"))
                })
            }
        }
        val ext = FhirExtensionHelper.getByUrl(patient, URL_A)
        assertEquals(URL_A, ext?.url)
    }

    @Test
    fun `getAllByUrl collects extensions from multiple depths in the same traversal`() {
        val patient = Patient().apply {
            addExtension(URL_A, StringType("root"))
            addName().addExtension(URL_A, StringType("name"))
            maritalStatus = CodeableConcept().apply {
                addExtension(URL_A, StringType("concept"))
            }
        }
        assertThat(FhirExtensionHelper.getAllByUrl(patient, URL_A), hasSize(3))
    }

    // ── Source type coverage ──────────────────────────────────────────────

    @Test
    fun `works on DomainResource (Patient)`() {
        val patient = Patient().apply { addExtension(URL_A, StringType("v")) }
        assertTrue(FhirExtensionHelper.hasExtension(patient, URL_A))
    }

    @Test
    fun `works on Element primitive type (StringType)`() {
        val str = StringType("hello").apply { addExtension(URL_A, StringType("meta")) }
        assertTrue(FhirExtensionHelper.hasExtension(str, URL_A))
        assertEquals("meta", FhirExtensionHelper.getValueAs<StringType>(str, URL_A)?.value)
    }

    @Test
    fun `works on complex type (Coding)`() {
        val coding = Coding().apply {
            system = "http://example.com"
            addExtension(URL_A, StringType("v"))
        }
        assertTrue(FhirExtensionHelper.hasExtension(coding, URL_A))
    }

    @Test
    fun `works on HumanName directly`() {
        val name = HumanName().apply {
            family = "Smith"
            addExtension(URL_A, StringType("nameExt"))
        }
        assertEquals("nameExt", FhirExtensionHelper.getValueAs<StringType>(name, URL_A)?.value)
    }
}
