package dev.ratkay.operation.dstu3

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.fhirpath.IFhirPath
import dev.ratkay.operation.FhirPath
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.`is`
import org.hl7.fhir.dstu3.model.BooleanType
import org.hl7.fhir.dstu3.model.ContactPoint
import org.hl7.fhir.dstu3.model.HumanName
import org.hl7.fhir.dstu3.model.IntegerType
import org.hl7.fhir.dstu3.model.Patient
import org.hl7.fhir.dstu3.model.StringType
import org.hl7.fhir.instance.model.api.IBase
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Validates that expressions produced by [FhirPath] are accepted and evaluated correctly by
 * HAPI's DSTU3 FHIRPath engine. A test failure here means the builder produced an invalid or
 * incorrectly structured expression string — not just a wrong expected value.
 */
class FhirPathEvaluationTest {

    companion object {
        private val engine: IFhirPath = FhirContext.forDstu3Cached().newFhirPath()
    }

    private fun <T : IBase> evaluateFirst(resource: IBase, path: FhirPath, type: Class<T>): T? =
        engine.evaluateFirst(resource, path.build(), type).orElse(null)

    private fun <T : IBase> evaluate(resource: IBase, path: FhirPath, type: Class<T>): List<T> =
        engine.evaluate(resource, path.build(), type)

    private fun matches(resource: IBase, path: FhirPath): Boolean {
        val results = engine.evaluate(resource, path.build(), IBase::class.java)
        if (results.isEmpty()) return false
        val first = results.first()
        return if (first is BooleanType) first.booleanValue() else true
    }

    // ── where + navigate ──────────────────────────────────────────────────────

    @Test
    fun `where with FhirPath condition extracts official family name`() {
        val patient = Patient().apply {
            addName().apply { use = HumanName.NameUse.OFFICIAL; family = "Smith" }
            addName().apply { use = HumanName.NameUse.NICKNAME; family = "Smitty" }
        }
        val path = FhirPath.from("name")
            .where(FhirPath.relative().navigate("use").eq("official"))
            .navigate("family")

        val results = evaluate(patient, path, StringType::class.java)
        assertThat(results, hasSize(1))
        assertThat(results[0].value, `is`("Smith"))
    }

    @Test
    fun `chained where and first extracts first given name from official name`() {
        val patient = Patient().apply {
            addName().apply {
                use = HumanName.NameUse.OFFICIAL
                family = "Smith"
                addGiven("John")
                addGiven("William")
            }
            addName().apply { use = HumanName.NameUse.NICKNAME; addGiven("Johnny") }
        }
        val path = FhirPath.from("name")
            .where(FhirPath.relative().navigate("use").eq("official"))
            .navigate("given")
            .first()

        val result = evaluateFirst(patient, path, StringType::class.java)
        assertThat(result?.value, `is`("John"))
    }

    @Test
    fun `and condition filters names that have both official use and a family`() {
        val patient = Patient().apply {
            addName().apply { use = HumanName.NameUse.OFFICIAL; family = "Smith" }
            addName().apply { use = HumanName.NameUse.OFFICIAL }     // no family
            addName().apply { use = HumanName.NameUse.NICKNAME; family = "Smitty" }
        }
        val condition = FhirPath.relative().navigate("use").eq("official")
            .and(FhirPath.relative().navigate("family").exists())
        val path = FhirPath.from("name").where(condition).navigate("family")

        val results = evaluate(patient, path, StringType::class.java)
        assertThat(results, hasSize(1))
        assertThat(results[0].value, `is`("Smith"))
    }

    @Test
    fun `or condition finds both phone and email telecom values`() {
        val patient = Patient().apply {
            addTelecom().apply { system = ContactPoint.ContactPointSystem.PHONE; value = "555-1234" }
            addTelecom().apply { system = ContactPoint.ContactPointSystem.EMAIL; value = "j@example.org" }
            addTelecom().apply { system = ContactPoint.ContactPointSystem.FAX; value = "555-0000" }
        }
        val phoneOrEmail = FhirPath.relative().navigate("system").eq("phone")
            .or(FhirPath.relative().navigate("system").eq("email"))
        val path = FhirPath.from("telecom").where(phoneOrEmail).navigate("value")

        val results = evaluate(patient, path, StringType::class.java)
        assertThat(results, hasSize(2))
    }

    @Test
    fun `identifier where system extracts MRN value`() {
        val patient = Patient().apply {
            addIdentifier().apply { system = "http://example.org/mrn"; value = "MRN-001" }
            addIdentifier().apply { system = "http://example.org/ssn"; value = "123-45-6789" }
        }
        val path = FhirPath.from("identifier")
            .where(FhirPath.relative().navigate("system").eq("http://example.org/mrn"))
            .navigate("value")

        val result = evaluateFirst(patient, path, StringType::class.java)
        assertThat(result?.value, `is`("MRN-001"))
    }

    @Test
    fun `and on identifier filters by system and value prefix`() {
        val patient = Patient().apply {
            addIdentifier().apply { system = "http://example.org/mrn"; value = "MRN-001" }
            addIdentifier().apply { system = "http://example.org/mrn"; value = "OLD-001" }
            addIdentifier().apply { system = "http://example.org/ssn"; value = "MRN-999" }
        }
        val condition = FhirPath.relative().navigate("system").eq("http://example.org/mrn")
            .and(FhirPath.relative().navigate("value").startsWith("MRN-"))
        val path = FhirPath.from("identifier").where(condition).navigate("value")

        val results = evaluate(patient, path, StringType::class.java)
        assertThat(results, hasSize(1))
        assertThat(results[0].value, `is`("MRN-001"))
    }

    // ── count / exists / all ──────────────────────────────────────────────────

    @Test
    fun `count of filtered list returns correct total`() {
        val patient = Patient().apply {
            addName().apply { use = HumanName.NameUse.OFFICIAL; family = "Smith" }
            addName().apply { use = HumanName.NameUse.OFFICIAL; family = "Jones" }
            addName().apply { use = HumanName.NameUse.NICKNAME; family = "Smitty" }
        }
        val path = FhirPath.from("name")
            .where(FhirPath.relative().navigate("use").eq("official"))
            .count()

        val result = evaluateFirst(patient, path, IntegerType::class.java)
        assertThat(result?.value, `is`(2))
    }

    @Test
    fun `where then exists detects presence of matching element`() {
        val patient = Patient().apply {
            addName().apply { use = HumanName.NameUse.OFFICIAL; family = "Smith" }
            addName().apply { use = HumanName.NameUse.NICKNAME; family = "Smitty" }
        }
        val path = FhirPath.from("name").where(FhirPath.relative().navigate("use").eq("official")).exists()
        assertTrue(matches(patient, path))
    }

    @Test
    fun `where then exists is false when no element matches`() {
        val patient = Patient().apply {
            addName().apply { use = HumanName.NameUse.NICKNAME; family = "Smitty" }
        }
        val path = FhirPath.from("name").where(FhirPath.relative().navigate("use").eq("official")).exists()
        assertFalse(matches(patient, path))
    }

    @Test
    fun `all with FhirPath criteria returns true when every identifier has a system`() {
        val patient = Patient().apply {
            addIdentifier().apply { system = "http://example.org/mrn"; value = "12345" }
            addIdentifier().apply { system = "http://example.org/ssn"; value = "999-99-9999" }
        }
        assertTrue(matches(patient, FhirPath.from("identifier").all(FhirPath.relative().navigate("system").exists())))
    }

    @Test
    fun `all with FhirPath criteria returns false when an identifier lacks a system`() {
        val patient = Patient().apply {
            addIdentifier().apply { system = "http://example.org/mrn"; value = "12345" }
            addIdentifier().apply { value = "orphan" }
        }
        assertFalse(matches(patient, FhirPath.from("identifier").all(FhirPath.relative().navigate("system").exists())))
    }

    // ── string functions (DSTU3 FHIRPath 1.0 subset) ─────────────────────────

    @Test
    fun `length returns character count of family name`() {
        val patient = Patient().apply { addName().apply { family = "Smith" } }
        val result = evaluateFirst(patient, FhirPath.from("name").navigate("family").length(), IntegerType::class.java)
        assertThat(result?.value, `is`(5))
    }

    @Test
    fun `substring extracts characters from family name`() {
        val patient = Patient().apply { addName().apply { family = "Smith" } }
        val result = evaluateFirst(patient, FhirPath.from("name").navigate("family").substring(1, 3), StringType::class.java)
        assertThat(result?.value, `is`("mit"))
    }

    @Test
    fun `startsWith in where condition filters names by prefix`() {
        val patient = Patient().apply {
            addName().apply { family = "Smith" }
            addName().apply { family = "Jones" }
            addName().apply { family = "Smithfield" }
        }
        val path = FhirPath.from("name")
            .where(FhirPath.relative().navigate("family").startsWith("Sm"))
            .navigate("family")

        val results = evaluate(patient, path, StringType::class.java)
        assertThat(results, hasSize(2))
    }

    @Test
    fun `endsWith in where condition filters names by suffix`() {
        val patient = Patient().apply {
            addName().apply { family = "Smith" }
            addName().apply { family = "Jones" }
            addName().apply { family = "Griffith" }
        }
        val path = FhirPath.from("name")
            .where(FhirPath.relative().navigate("family").endsWith("ith"))
            .navigate("family")

        val results = evaluate(patient, path, StringType::class.java)
        assertThat(results, hasSize(2))
    }

    // ── string function: matches regex ───────────────────────────────────────

    @Test
    fun `matches regex in where condition filters identifiers by value pattern`() {
        val patient = Patient().apply {
            addIdentifier().apply { system = "http://example.org/id"; value = "MRN-001" }
            addIdentifier().apply { system = "http://example.org/id"; value = "ABC-001" }
            addIdentifier().apply { system = "http://example.org/id"; value = "MRN-099" }
        }
        val path = FhirPath.from("identifier")
            .where(FhirPath.relative().navigate("value").matches("MRN-[0-9]+"))
            .navigate("value")

        val results = evaluate(patient, path, StringType::class.java)
        assertThat(results, hasSize(2))
    }

    // ── select projection ─────────────────────────────────────────────────────

    @Test
    fun `select projects a field from every element of a collection`() {
        val patient = Patient().apply {
            addName().apply { family = "Smith"; addGiven("John") }
            addName().apply { family = "Jones"; addGiven("Jane") }
        }
        val results = evaluate(patient, FhirPath.from("name").select("family"), StringType::class.java)
        assertThat(results, hasSize(2))
    }

    // ── multi-term boolean chain ──────────────────────────────────────────────

    @Test
    fun `or-then-and three-term condition filters with correct precedence`() {
        val patient = Patient().apply {
            addIdentifier().apply { system = "http://a.org"; value = "active-mrn" }
            addIdentifier().apply { system = "http://b.org"; value = "passive-mrn" }
            addIdentifier().apply { system = "http://c.org"; value = "something-else" }
        }
        val sysA = FhirPath.relative().navigate("system").eq("http://a.org")
        val sysB = FhirPath.relative().navigate("system").eq("http://b.org")
        val endsWithMrn = FhirPath.relative().navigate("value").endsWith("-mrn")
        val path = FhirPath.from("identifier")
            .where(sysA.or(sysB).and(endsWithMrn))
            .navigate("value")

        val results = evaluate(patient, path, StringType::class.java)
        assertThat(results, hasSize(2))
    }

    // ── FHIR-specific: extension ──────────────────────────────────────────────

    @Test
    fun `extension function navigates to named extension value`() {
        val patient = Patient().apply {
            addExtension("http://example.org/color", StringType("blue"))
        }
        val path = FhirPath.from("Patient")
            .extension("http://example.org/color")
            .navigate("value")

        val result = evaluateFirst(patient, path, StringType::class.java)
        assertThat(result?.value, `is`("blue"))
    }

    @Test
    fun `extension where url detects presence of a named extension`() {
        val patient = Patient().apply {
            addExtension("http://example.org/color", StringType("blue"))
        }
        val path = FhirPath.from("extension")
            .where(FhirPath.relative().navigate("url").eq("http://example.org/color"))
            .exists()
        assertTrue(matches(patient, path))
    }

    @Test
    fun `extension where url is false when extension is absent`() {
        val path = FhirPath.from("extension")
            .where(FhirPath.relative().navigate("url").eq("http://example.org/color"))
            .exists()
        assertFalse(matches(Patient(), path))
    }

    // ── boolean operators ─────────────────────────────────────────────────────

    @Test
    fun `not negates truthy active flag`() {
        val patient = Patient().apply { active = true }
        assertFalse(matches(patient, FhirPath.from("active").not()))
    }

    @Test
    fun `not negates falsy active flag`() {
        val patient = Patient().apply { active = false }
        assertTrue(matches(patient, FhirPath.from("active").not()))
    }

    @Test
    fun `and of two satisfied conditions is true`() {
        val patient = Patient().apply {
            active = true
            addName().apply { family = "Smith" }
        }
        val path = FhirPath.from("active").eq(true)
            .and(FhirPath.from("name").exists())
        assertTrue(matches(patient, path))
    }

    @Test
    fun `and short-circuits when second condition is false`() {
        val patient = Patient().apply { active = true }
        val path = FhirPath.from("active").eq(true)
            .and(FhirPath.from("name").exists())
        assertFalse(matches(patient, path))
    }

    // ── distinct / isDistinct ─────────────────────────────────────────────────

    @Test
    fun `distinct removes duplicate family names`() {
        val patient = Patient().apply {
            addName().apply { family = "Smith" }
            addName().apply { family = "Smith" }
            addName().apply { family = "Jones" }
        }
        val results = evaluate(patient, FhirPath.from("name").navigate("family").distinct(), StringType::class.java)
        assertThat(results, hasSize(2))
    }

    @Test
    fun `isDistinct returns true when all family names are unique`() {
        val patient = Patient().apply {
            addName().apply { family = "Smith" }
            addName().apply { family = "Jones" }
        }
        assertTrue(matches(patient, FhirPath.from("name").navigate("family").isDistinct()))
    }

    @Test
    fun `isDistinct returns false when duplicates exist`() {
        val patient = Patient().apply {
            addName().apply { family = "Smith" }
            addName().apply { family = "Smith" }
        }
        assertFalse(matches(patient, FhirPath.from("name").navigate("family").isDistinct()))
    }

    // ── immutability under evaluation ─────────────────────────────────────────

    @Test
    fun `reusing a base FhirPath produces independent evaluated results`() {
        val patient = Patient().apply {
            addName().apply { use = HumanName.NameUse.OFFICIAL; family = "Smith"; addGiven("John") }
        }
        val base = FhirPath.from("name").where(FhirPath.relative().navigate("use").eq("official"))

        val family = evaluateFirst(patient, base.navigate("family"), StringType::class.java)
        val given = evaluateFirst(patient, base.navigate("given").first(), StringType::class.java)

        assertThat(family?.value, `is`("Smith"))
        assertThat(given?.value, `is`("John"))
        assertThat(base.build(), `is`("name.where(use = 'official')"))
    }
}
