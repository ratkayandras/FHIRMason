package dev.ratkay.operation.dstu3

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.fhirpath.IFhirPath
import dev.ratkay.operation.FhirPath
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.`is`
import org.hl7.fhir.dstu3.model.BooleanType
import org.hl7.fhir.dstu3.model.ContactPoint
import org.hl7.fhir.dstu3.model.DecimalType
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
 *
 * Note: DSTU3 uses FHIRPath 1.0. Functions exclusive to FHIRPath 2.0 (lower, upper, trim,
 * indexOf, split, join, replaceMatches) are not tested here.
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

    // ── navigate ──────────────────────────────────────────────────────────────

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

    // ── union ─────────────────────────────────────────────────────────────────

    @Test
    fun `union combines two collections`() {
        val patient = Patient().apply {
            addName().apply { family = "Smith" }
            addIdentifier().apply { value = "ID-1" }
        }
        val results = evaluate(patient, FhirPath.from("name").union(FhirPath.from("identifier")), IBase::class.java)
        assertThat(results, hasSize(2))
    }

    // ── collection: first, last, tail, take, skip, count, empty ──────────────

    @Test
    fun `last returns the last element`() {
        val patient = Patient().apply {
            addName().apply { family = "First" }
            addName().apply { family = "Last" }
        }
        val result = evaluateFirst(patient, FhirPath.from("name").navigate("family").last(), StringType::class.java)
        assertThat(result?.value, `is`("Last"))
    }

    @Test
    fun `tail returns all but first element`() {
        val patient = Patient().apply {
            addName().apply { family = "First" }
            addName().apply { family = "Second" }
            addName().apply { family = "Third" }
        }
        val results = evaluate(patient, FhirPath.from("name").navigate("family").tail(), StringType::class.java)
        assertThat(results, hasSize(2))
        assertThat(results[0].value, `is`("Second"))
    }

    @Test
    fun `take returns the first n elements`() {
        val patient = Patient().apply {
            addName().apply { family = "A" }
            addName().apply { family = "B" }
            addName().apply { family = "C" }
        }
        val results = evaluate(patient, FhirPath.from("name").navigate("family").take(2), StringType::class.java)
        assertThat(results, hasSize(2))
    }

    @Test
    fun `skip omits the first n elements`() {
        val patient = Patient().apply {
            addName().apply { family = "A" }
            addName().apply { family = "B" }
            addName().apply { family = "C" }
        }
        val results = evaluate(patient, FhirPath.from("name").navigate("family").skip(1), StringType::class.java)
        assertThat(results, hasSize(2))
        assertThat(results[0].value, `is`("B"))
    }

    @Test
    fun `empty returns true when collection is empty`() {
        val patient = Patient()
        assertTrue(matches(patient, FhirPath.from("name").empty()))
    }

    @Test
    fun `empty returns false when collection has elements`() {
        val patient = Patient().apply { addName().apply { family = "Smith" } }
        assertFalse(matches(patient, FhirPath.from("name").empty()))
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

    // exists(criteria) is not supported by HAPI's DSTU3 FHIRPath 1.0 engine (0 parameters only).

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

    // allTrue/anyTrue/allFalse/anyFalse are not supported by HAPI's DSTU3 FHIRPath 1.0 engine.

    // ── collection: supersetOf ───────────────────────────────────────────────

    @Test
    fun `supersetOf returns true when collection contains the other`() {
        val patient = Patient().apply {
            addIdentifier().apply { system = "http://a.org"; value = "1" }
            addIdentifier().apply { system = "http://b.org"; value = "2" }
        }
        val path = FhirPath.from("identifier")
            .supersetOf("identifier.where(system = 'http://a.org')")
        assertTrue(matches(patient, path))
    }

    // ── collection: children, descendants ─────────────────────────────────────

    @Test
    fun `children returns immediate child elements`() {
        val patient = Patient().apply {
            addName().apply { use = HumanName.NameUse.OFFICIAL; family = "Smith"; addGiven("John") }
        }
        val results = evaluate(patient, FhirPath.from("name").first().children(), IBase::class.java)
        assertTrue(results.isNotEmpty())
    }

    @Test
    fun `descendants returns all nested child elements`() {
        val patient = Patient().apply {
            addName().apply { family = "Smith" }
        }
        val results = evaluate(patient, FhirPath.from("name").descendants(), IBase::class.java)
        assertTrue(results.isNotEmpty())
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

    @Test
    fun `xor returns true when exactly one condition holds`() {
        val patient = Patient().apply { active = true }
        val path = FhirPath.from("active").eq(true)
            .xor(FhirPath.from("name").exists())
        assertTrue(matches(patient, path))
    }

    @Test
    fun `implies returns true when antecedent is false`() {
        val patient = Patient().apply { active = false }
        val path = FhirPath.from("active").eq(true)
            .implies(FhirPath.from("name").exists())
        assertTrue(matches(patient, path))
    }

    // ── equality / comparison operators ──────────────────────────────────────

    @Test
    fun `ne filters out the matching name`() {
        val patient = Patient().apply {
            addName().apply { family = "Smith" }
            addName().apply { family = "Jones" }
        }
        val results = evaluate(patient,
            FhirPath.from("name").where(FhirPath.relative().navigate("family").ne("Smith")).navigate("family"),
            StringType::class.java)
        assertThat(results, hasSize(1))
        assertThat(results[0].value, `is`("Jones"))
    }

    @Test
    fun `lt filters names with family lexicographically before threshold`() {
        val patient = Patient().apply {
            addName().apply { family = "Adams" }
            addName().apply { family = "Smith" }
        }
        val results = evaluate(patient,
            FhirPath.from("name").where(FhirPath.relative().navigate("family").lt("M")).navigate("family"),
            StringType::class.java)
        assertThat(results, hasSize(1))
        assertThat(results[0].value, `is`("Adams"))
    }

    @Test
    fun `gt filters names with family lexicographically after threshold`() {
        val patient = Patient().apply {
            addName().apply { family = "Adams" }
            addName().apply { family = "Smith" }
        }
        val results = evaluate(patient,
            FhirPath.from("name").where(FhirPath.relative().navigate("family").gt("M")).navigate("family"),
            StringType::class.java)
        assertThat(results, hasSize(1))
        assertThat(results[0].value, `is`("Smith"))
    }

    @Test
    fun `le filters names up to and including threshold`() {
        val patient = Patient().apply {
            addName().apply { family = "Adams" }
            addName().apply { family = "Smith" }
        }
        val results = evaluate(patient,
            FhirPath.from("name").where(FhirPath.relative().navigate("family").le("Smith")).navigate("family"),
            StringType::class.java)
        assertThat(results, hasSize(2))
    }

    @Test
    fun `ge filters names from threshold upward`() {
        val patient = Patient().apply {
            addName().apply { family = "Adams" }
            addName().apply { family = "Smith" }
        }
        val results = evaluate(patient,
            FhirPath.from("name").where(FhirPath.relative().navigate("family").ge("Smith")).navigate("family"),
            StringType::class.java)
        assertThat(results, hasSize(1))
        assertThat(results[0].value, `is`("Smith"))
    }

    @Test
    fun `equiv matches strings case-insensitively`() {
        val patient = Patient().apply { addName().apply { family = "Smith" } }
        assertTrue(matches(patient, FhirPath.from("name").navigate("family").first().equiv("smith")))
    }

    @Test
    fun `notEquiv returns true for different values`() {
        val patient = Patient().apply { addName().apply { family = "Smith" } }
        assertTrue(matches(patient, FhirPath.from("name").navigate("family").first().notEquiv("jones")))
    }

    @Test
    fun `containsValue returns true when collection contains the item`() {
        val patient = Patient().apply {
            addName().apply { addGiven("John"); addGiven("William") }
        }
        assertTrue(matches(patient, FhirPath.from("name").navigate("given").containsValue("John")))
    }

    // ── string functions (DSTU3 FHIRPath 1.0 subset) ─────────────────────────
    // Note: lower, upper, trim, indexOf, split, join, replaceMatches are FHIRPath 2.0 only

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
    fun `substring with only start returns rest of string`() {
        val patient = Patient().apply { addName().apply { family = "Smith" } }
        val result = evaluateFirst(patient, FhirPath.from("name").navigate("family").substring(2), StringType::class.java)
        assertThat(result?.value, `is`("ith"))
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

    @Test
    fun `contains detects substring in family name`() {
        val patient = Patient().apply {
            addName().apply { family = "Smithfield" }
            addName().apply { family = "Jones" }
        }
        val results = evaluate(patient,
            FhirPath.from("name").where(FhirPath.relative().navigate("family").contains("mith")).navigate("family"),
            StringType::class.java)
        assertThat(results, hasSize(1))
        assertThat(results[0].value, `is`("Smithfield"))
    }

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

    @Test
    fun `replace substitutes a pattern in a string`() {
        val patient = Patient().apply { addName().apply { family = "Smith-Jones" } }
        val result = evaluateFirst(patient,
            FhirPath.from("name").navigate("family").replace("-", " "),
            StringType::class.java)
        assertThat(result?.value, `is`("Smith Jones"))
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

    // Math functions (abs, ceiling, floor, round, sqrt, truncate, power) are not supported
    // by HAPI's DSTU3 FHIRPath 1.0 engine. Use R4 for expressions requiring math functions.

    // ── arithmetic operators ──────────────────────────────────────────────────

    @Test
    fun `plus adds integer to count`() {
        val patient = Patient().apply { addName(); addName() }
        val result = evaluateFirst(patient, FhirPath.from("name").count().plus("1"), IntegerType::class.java)
        assertThat(result?.value, `is`(3))
    }

    @Test
    fun `minus subtracts integer from count`() {
        val patient = Patient().apply { addName(); addName(); addName() }
        val result = evaluateFirst(patient, FhirPath.from("name").count().minus("1"), IntegerType::class.java)
        assertThat(result?.value, `is`(2))
    }

    @Test
    fun `times multiplies count by integer`() {
        val patient = Patient().apply { addName(); addName() }
        val result = evaluateFirst(patient, FhirPath.from("name").count().times("3"), IntegerType::class.java)
        assertThat(result?.value, `is`(6))
    }

    @Test
    fun `dividedBy divides count`() {
        val patient = Patient().apply { addName(); addName(); addName(); addName() }
        val result = evaluateFirst(patient, FhirPath.from("name").count().dividedBy("2"), DecimalType::class.java)
        assertThat(result?.value?.toDouble(), `is`(2.0))
    }

    @Test
    fun `div performs integer division`() {
        val patient = Patient().apply { addName(); addName(); addName() }
        val result = evaluateFirst(patient, FhirPath.from("name").count().div("2"), IntegerType::class.java)
        assertThat(result?.value, `is`(1))
    }

    @Test
    fun `mod returns remainder`() {
        val patient = Patient().apply { addName(); addName(); addName() }
        val result = evaluateFirst(patient, FhirPath.from("name").count().mod("2"), IntegerType::class.java)
        assertThat(result?.value, `is`(1))
    }

    @Test
    fun `concat joins strings with ampersand`() {
        val patient = Patient().apply { addName().apply { family = "Smith" } }
        val result = evaluateFirst(patient,
            FhirPath.from("name").navigate("family").first().concat("' Jr.'"),
            StringType::class.java)
        assertThat(result?.value, `is`("Smith Jr."))
    }

    // ── type conversion ───────────────────────────────────────────────────────
    // toBoolean, toInteger, toQuantity are not supported by HAPI's DSTU3 FHIRPath 1.0 engine.

    @Test
    fun `toDecimal converts an integer to decimal`() {
        val patient = Patient().apply { addName(); addName() }
        val result = evaluateFirst(patient, FhirPath.from("name").count().toDecimal(), DecimalType::class.java)
        assertThat(result?.value?.toDouble(), `is`(2.0))
    }

    // ── FHIR-specific ─────────────────────────────────────────────────────────

    @Test
    fun `extension navigates to a named extension value`() {
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

    @Test
    fun `resolve returns empty collection without server resolver`() {
        val patient = Patient().apply {
            addGeneralPractitioner().reference = "Practitioner/123"
        }
        val results = evaluate(patient,
            FhirPath.from("generalPractitioner").resolve(),
            IBase::class.java)
        assertTrue(results.isEmpty())
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
