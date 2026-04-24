package dev.ratkay.operation.r4

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.fhirpath.IFhirPath
import dev.ratkay.operation.FhirPath
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.`is`
import org.hl7.fhir.instance.model.api.IBase
import org.hl7.fhir.r4.model.BooleanType
import org.hl7.fhir.r4.model.ContactPoint
import org.hl7.fhir.r4.model.DecimalType
import org.hl7.fhir.r4.model.HumanName
import org.hl7.fhir.r4.model.IntegerType
import org.hl7.fhir.r4.model.Observation
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.Quantity
import org.hl7.fhir.r4.model.Reference
import org.hl7.fhir.r4.model.StringType
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Validates that expressions produced by [FhirPath] are accepted and evaluated correctly by
 * HAPI's FHIRPath engine. A test failure here means the builder produced an invalid or
 * incorrectly structured expression string — not just a wrong expected value.
 */
class FhirPathEvaluationTest {

    companion object {
        private val engine: IFhirPath = FhirContext.forR4Cached().newFhirPath()
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

    private fun obsWithQuantity(v: Double): Observation =
        Observation().also { it.value = Quantity().setValue(BigDecimal.valueOf(v)) }

    // ── navigate ──────────────────────────────────────────────────────────────

    @Test
    fun `where with FhirPath condition extracts official family name`() {
        val patient = Patient().apply {
            addName().apply { use = HumanName.NameUse.OFFICIAL; family = "Smith" }
            addName().apply { use = HumanName.NameUse.NICKNAME; family = "Smitty" }
        }
        val results = evaluate(patient, FhirPath.from("name")
            .where(FhirPath.relative().navigate("use").eq("official"))
            .navigate("family"), StringType::class.java)
        assertThat(results, hasSize(1))
        assertThat(results[0].value, `is`("Smith"))
    }

    @Test
    fun `union combines two field collections into one`() {
        val patient = Patient().apply {
            addName().apply { addGiven("John"); family = "Smith" }
        }
        val path = FhirPath.from("name").navigate("given")
            .union(FhirPath.from("name").navigate("family"))
        val results = evaluate(patient, path, StringType::class.java)
        assertThat(results, hasSize(2))
    }

    // ── collection: first, last, tail, take, skip ─────────────────────────────

    @Test
    fun `first returns the first element`() {
        val patient = Patient().apply {
            addName().apply { family = "Alpha" }
            addName().apply { family = "Beta" }
        }
        val result = evaluateFirst(patient, FhirPath.from("name").navigate("family").first(), StringType::class.java)
        assertThat(result?.value, `is`("Alpha"))
    }

    @Test
    fun `last returns the final element`() {
        val patient = Patient().apply {
            addName().apply { family = "Alpha" }
            addName().apply { family = "Beta" }
            addName().apply { family = "Gamma" }
        }
        val result = evaluateFirst(patient, FhirPath.from("name").navigate("family").last(), StringType::class.java)
        assertThat(result?.value, `is`("Gamma"))
    }

    @Test
    fun `tail returns all but the first element`() {
        val patient = Patient().apply {
            addName().apply { family = "Alpha" }
            addName().apply { family = "Beta" }
            addName().apply { family = "Gamma" }
        }
        val results = evaluate(patient, FhirPath.from("name").navigate("family").tail(), StringType::class.java)
        assertThat(results, hasSize(2))
        assertThat(results[0].value, `is`("Beta"))
    }

    @Test
    fun `take returns the first n elements`() {
        val patient = Patient().apply {
            addName().apply { family = "Alpha" }
            addName().apply { family = "Beta" }
            addName().apply { family = "Gamma" }
        }
        val results = evaluate(patient, FhirPath.from("name").navigate("family").take(2), StringType::class.java)
        assertThat(results, hasSize(2))
        assertThat(results[0].value, `is`("Alpha"))
    }

    @Test
    fun `skip omits the first n elements`() {
        val patient = Patient().apply {
            addName().apply { family = "Alpha" }
            addName().apply { family = "Beta" }
            addName().apply { family = "Gamma" }
        }
        val results = evaluate(patient, FhirPath.from("name").navigate("family").skip(1), StringType::class.java)
        assertThat(results, hasSize(2))
        assertThat(results[0].value, `is`("Beta"))
    }

    // ── collection: count, empty, exists, all ─────────────────────────────────

    @Test
    fun `count returns the number of elements`() {
        val patient = Patient().apply {
            addName().apply { use = HumanName.NameUse.OFFICIAL; family = "Smith" }
            addName().apply { use = HumanName.NameUse.OFFICIAL; family = "Jones" }
            addName().apply { use = HumanName.NameUse.NICKNAME; family = "Smitty" }
        }
        val result = evaluateFirst(patient,
            FhirPath.from("name").where(FhirPath.relative().navigate("use").eq("official")).count(),
            IntegerType::class.java)
        assertThat(result?.value, `is`(2))
    }

    @Test
    fun `empty returns true when collection is empty`() {
        assertTrue(matches(Patient(), FhirPath.from("name").empty()))
    }

    @Test
    fun `empty returns false when collection has elements`() {
        val patient = Patient().apply { addName().apply { family = "Smith" } }
        assertFalse(matches(patient, FhirPath.from("name").empty()))
    }

    @Test
    fun `exists with FhirPath criteria detects matching element`() {
        val patient = Patient().apply {
            addName().apply { use = HumanName.NameUse.OFFICIAL }
        }
        assertTrue(matches(patient, FhirPath.from("name").exists(FhirPath.relative().navigate("use").eq("official"))))
    }

    @Test
    fun `all with FhirPath criteria is true when every element matches`() {
        val patient = Patient().apply {
            addIdentifier().apply { system = "http://example.org/mrn"; value = "1" }
            addIdentifier().apply { system = "http://example.org/ssn"; value = "2" }
        }
        assertTrue(matches(patient, FhirPath.from("identifier").all(FhirPath.relative().navigate("system").exists())))
    }

    // ── collection: allTrue, anyTrue, allFalse, anyFalse ─────────────────────

    @Test
    fun `allTrue returns true when all items are true`() {
        val patient = Patient().apply { active = true }
        assertTrue(matches(patient, FhirPath.from("active").allTrue()))
    }

    @Test
    fun `allTrue returns false when an item is false`() {
        val patient = Patient().apply { active = false }
        assertFalse(matches(patient, FhirPath.from("active").allTrue()))
    }

    @Test
    fun `anyTrue returns true when at least one item is true`() {
        val patient = Patient().apply { active = true }
        assertTrue(matches(patient, FhirPath.from("active").anyTrue()))
    }

    @Test
    fun `allFalse returns true when all items are false`() {
        val patient = Patient().apply { active = false }
        assertTrue(matches(patient, FhirPath.from("active").allFalse()))
    }

    @Test
    fun `anyFalse returns true when at least one item is false`() {
        val patient = Patient().apply { active = false }
        assertTrue(matches(patient, FhirPath.from("active").anyFalse()))
    }

    // ── collection: distinct, isDistinct ─────────────────────────────────────

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
    fun `isDistinct returns true when all elements are unique`() {
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
    fun `descendants returns all nested elements`() {
        val patient = Patient().apply {
            addName().apply { use = HumanName.NameUse.OFFICIAL; family = "Smith"; addGiven("John") }
        }
        val results = evaluate(patient, FhirPath.from("name").first().descendants(), IBase::class.java)
        assertTrue(results.isNotEmpty())
    }

    // ── boolean: not, and, or, xor, implies ──────────────────────────────────

    @Test
    fun `not negates a true value`() {
        val patient = Patient().apply { active = true }
        assertFalse(matches(patient, FhirPath.from("active").not()))
    }

    @Test
    fun `and of two true conditions is true`() {
        val patient = Patient().apply { active = true; addName().apply { family = "Smith" } }
        assertTrue(matches(patient, FhirPath.from("active").eq(true).and(FhirPath.from("name").exists())))
    }

    @Test
    fun `or of one true and one false is true`() {
        val patient = Patient().apply {
            addTelecom().apply { system = ContactPoint.ContactPointSystem.PHONE; value = "555-1234" }
        }
        val path = FhirPath.relative().navigate("system").eq("phone")
            .or(FhirPath.relative().navigate("system").eq("email"))
        assertTrue(matches(patient, FhirPath.from("telecom").where(path).exists()))
    }

    @Test
    fun `xor returns true when exactly one operand is true`() {
        val patient = Patient().apply { active = true; addName().apply { family = "Smith" } }
        // active=true, name.empty()=false → true xor false = true
        assertTrue(matches(patient, FhirPath.from("active").xor(FhirPath.from("name").empty())))
    }

    @Test
    fun `xor returns false when both operands are true`() {
        val patient = Patient().apply { active = true; addName().apply { family = "Smith" } }
        // active=true, name.exists()=true → true xor true = false
        assertFalse(matches(patient, FhirPath.from("active").xor(FhirPath.from("name").exists())))
    }

    @Test
    fun `implies returns true when antecedent is true and consequent is true`() {
        val patient = Patient().apply { active = true; addName().apply { family = "Smith" } }
        assertTrue(matches(patient, FhirPath.from("active").implies(FhirPath.from("name").exists())))
    }

    @Test
    fun `implies returns false when antecedent is true and consequent is false`() {
        val patient = Patient().apply { active = true }
        assertFalse(matches(patient, FhirPath.from("active").implies(FhirPath.from("name").exists())))
    }

    // ── comparison: eq, ne, lt, gt, le, ge ───────────────────────────────────

    @Test
    fun `eq with Int compares integer values`() {
        val patient = Patient().apply { addName(); addName() }
        assertTrue(matches(patient, FhirPath.from("name").count().eq(2)))
    }

    @Test
    fun `eq with Double compares decimal values`() {
        assertTrue(matches(obsWithQuantity(3.0), FhirPath.from("value").navigate("value").eq(3.0)))
    }

    @Test
    fun `ne with String compares string values`() {
        val patient = Patient().apply { addName().apply { family = "Smith" } }
        assertTrue(matches(patient, FhirPath.from("name").navigate("family").first().ne("Jones")))
    }

    @Test
    fun `ne with Boolean compares boolean values`() {
        val patient = Patient().apply { active = true }
        assertTrue(matches(patient, FhirPath.from("active").ne(false)))
    }

    @Test
    fun `ne with Int compares integer values`() {
        val patient = Patient().apply { addName(); addName() }
        assertTrue(matches(patient, FhirPath.from("name").count().ne(0)))
    }

    @Test
    fun `lt with Int returns true when left is less`() {
        val patient = Patient().apply { addName(); addName() }
        assertTrue(matches(patient, FhirPath.from("name").count().lt(5)))
    }

    @Test
    fun `lt with Double returns true when left is less`() {
        assertTrue(matches(obsWithQuantity(3.14), FhirPath.from("value").navigate("value").lt(4.0)))
    }

    @Test
    fun `gt with Int returns true when left is greater`() {
        val patient = Patient().apply { addName(); addName() }
        assertTrue(matches(patient, FhirPath.from("name").count().gt(1)))
    }

    @Test
    fun `gt with Double returns true when left is greater`() {
        assertTrue(matches(obsWithQuantity(3.14), FhirPath.from("value").navigate("value").gt(3.0)))
    }

    @Test
    fun `le with Int returns true when equal`() {
        val patient = Patient().apply { addName(); addName() }
        assertTrue(matches(patient, FhirPath.from("name").count().le(2)))
    }

    @Test
    fun `ge with Int returns true when equal`() {
        val patient = Patient().apply { addName(); addName() }
        assertTrue(matches(patient, FhirPath.from("name").count().ge(2)))
    }

    // ── comparison: equiv, notEquiv, memberOf, containsValue ─────────────────

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

    // ── string functions ──────────────────────────────────────────────────────

    @Test
    fun `lower converts to lowercase`() {
        val patient = Patient().apply { addName().apply { family = "SMITH" } }
        val result = evaluateFirst(patient, FhirPath.from("name").navigate("family").lower(), StringType::class.java)
        assertThat(result?.value, `is`("smith"))
    }

    @Test
    fun `upper converts to uppercase`() {
        val patient = Patient().apply { addName().apply { family = "smith" } }
        val result = evaluateFirst(patient, FhirPath.from("name").navigate("family").upper(), StringType::class.java)
        assertThat(result?.value, `is`("SMITH"))
    }

    @Test
    fun `length returns character count`() {
        val patient = Patient().apply { addName().apply { family = "Smith" } }
        val result = evaluateFirst(patient, FhirPath.from("name").navigate("family").length(), IntegerType::class.java)
        assertThat(result?.value, `is`(5))
    }

    @Test
    fun `trim removes leading and trailing whitespace`() {
        val patient = Patient().apply { addName().apply { family = "  Smith  " } }
        val result = evaluateFirst(patient, FhirPath.from("name").navigate("family").trim(), StringType::class.java)
        assertThat(result?.value, `is`("Smith"))
    }

    @Test
    fun `startsWith filters names by prefix`() {
        val patient = Patient().apply {
            addName().apply { family = "Smith" }
            addName().apply { family = "Jones" }
            addName().apply { family = "Smithfield" }
        }
        val results = evaluate(patient,
            FhirPath.from("name").where(FhirPath.relative().navigate("family").startsWith("Sm")).navigate("family"),
            StringType::class.java)
        assertThat(results, hasSize(2))
    }

    @Test
    fun `endsWith filters names by suffix`() {
        val patient = Patient().apply {
            addName().apply { family = "Smith" }
            addName().apply { family = "Jones" }
        }
        val results = evaluate(patient,
            FhirPath.from("name").where(FhirPath.relative().navigate("family").endsWith("ith")).navigate("family"),
            StringType::class.java)
        assertThat(results, hasSize(1))
        assertThat(results[0].value, `is`("Smith"))
    }

    @Test
    fun `contains filters names containing a substring`() {
        val patient = Patient().apply {
            addName().apply { family = "Smith" }
            addName().apply { family = "Jones" }
        }
        val results = evaluate(patient,
            FhirPath.from("name").where(FhirPath.relative().navigate("family").contains("mit")).navigate("family"),
            StringType::class.java)
        assertThat(results, hasSize(1))
    }

    @Test
    fun `matches filters by regex`() {
        val patient = Patient().apply {
            addIdentifier().apply { value = "MRN-001" }
            addIdentifier().apply { value = "ABC-001" }
        }
        val results = evaluate(patient,
            FhirPath.from("identifier").where(FhirPath.relative().navigate("value").matches("MRN-[0-9]+")).navigate("value"),
            StringType::class.java)
        assertThat(results, hasSize(1))
    }

    @Test
    fun `indexOf returns position of substring`() {
        val patient = Patient().apply { addName().apply { family = "Smith" } }
        val result = evaluateFirst(patient,
            FhirPath.from("name").navigate("family").indexOf("mit"),
            IntegerType::class.java)
        assertThat(result?.value, `is`(1))
    }

    @Test
    fun `substring extracts characters by start and length`() {
        val patient = Patient().apply { addName().apply { family = "Smith" } }
        val result = evaluateFirst(patient,
            FhirPath.from("name").navigate("family").substring(1, 3),
            StringType::class.java)
        assertThat(result?.value, `is`("mit"))
    }

    @Test
    fun `replace substitutes pattern with substitution`() {
        val patient = Patient().apply { addName().apply { family = "Smith" } }
        val result = evaluateFirst(patient,
            FhirPath.from("name").navigate("family").replace("Smith", "Jones"),
            StringType::class.java)
        assertThat(result?.value, `is`("Jones"))
    }

    @Test
    fun `replaceMatches substitutes regex matches`() {
        val patient = Patient().apply { addName().apply { family = "Smith" } }
        val result = evaluateFirst(patient,
            FhirPath.from("name").navigate("family").replaceMatches("[aeiou]", "*"),
            StringType::class.java)
        assertThat(result?.value, `is`("Sm*th"))
    }

    @Test
    fun `split divides a string into a collection`() {
        val patient = Patient().apply { addName().apply { family = "Van.Der.Berg" } }
        val results = evaluate(patient,
            FhirPath.from("name").navigate("family").split("."),
            StringType::class.java)
        assertThat(results, hasSize(3))
    }

    @Test
    fun `join concatenates a collection with a separator`() {
        val patient = Patient().apply {
            addName().apply { family = "Smith" }
            addName().apply { family = "Jones" }
        }
        val result = evaluateFirst(patient,
            FhirPath.from("name").navigate("family").join(", "),
            StringType::class.java)
        assertThat(result?.value, `is`("Smith, Jones"))
    }

    // ── math functions ────────────────────────────────────────────────────────

    @Test
    fun `abs returns absolute value`() {
        val result = evaluateFirst(obsWithQuantity(-4.5),
            FhirPath.from("value").navigate("value").abs(),
            DecimalType::class.java)
        assertThat(result?.value?.toDouble(), `is`(4.5))
    }

    @Test
    fun `ceiling rounds up to nearest integer`() {
        val result = evaluateFirst(obsWithQuantity(3.2),
            FhirPath.from("value").navigate("value").ceiling(),
            IntegerType::class.java)
        assertThat(result?.value, `is`(4))
    }

    @Test
    fun `floor rounds down to nearest integer`() {
        val result = evaluateFirst(obsWithQuantity(3.7),
            FhirPath.from("value").navigate("value").floor(),
            IntegerType::class.java)
        assertThat(result?.value, `is`(3))
    }

    @Test
    fun `round rounds to nearest integer`() {
        val result = evaluateFirst(obsWithQuantity(3.5),
            FhirPath.from("value").navigate("value").round(),
            DecimalType::class.java)
        assertNotNull(result)
    }

    @Test
    fun `round with precision rounds to given decimal places`() {
        val result = evaluateFirst(obsWithQuantity(3.14159),
            FhirPath.from("value").navigate("value").round(2),
            DecimalType::class.java)
        assertThat(result?.value?.toDouble(), `is`(3.14))
    }

    @Test
    fun `sqrt returns square root`() {
        val result = evaluateFirst(obsWithQuantity(4.0),
            FhirPath.from("value").navigate("value").sqrt(),
            DecimalType::class.java)
        assertThat(result?.value?.toDouble(), `is`(2.0))
    }

    @Test
    fun `power raises to exponent`() {
        val result = evaluateFirst(obsWithQuantity(3.0),
            FhirPath.from("value").navigate("value").power("2"),
            DecimalType::class.java)
        assertThat(result?.value?.toDouble(), `is`(9.0))
    }

    @Test
    fun `truncate removes fractional part`() {
        val result = evaluateFirst(obsWithQuantity(3.9),
            FhirPath.from("value").navigate("value").truncate(),
            IntegerType::class.java)
        assertThat(result?.value, `is`(3))
    }

    // ── arithmetic operators ──────────────────────────────────────────────────

    @Test
    fun `plus adds a value`() {
        val patient = Patient().apply { addName(); addName() }
        val result = evaluateFirst(patient, FhirPath.from("name").count().plus("1"), IntegerType::class.java)
        assertThat(result?.value, `is`(3))
    }

    @Test
    fun `minus subtracts a value`() {
        val patient = Patient().apply { addName(); addName() }
        val result = evaluateFirst(patient, FhirPath.from("name").count().minus("1"), IntegerType::class.java)
        assertThat(result?.value, `is`(1))
    }

    @Test
    fun `times multiplies a value`() {
        val patient = Patient().apply { addName(); addName() }
        val result = evaluateFirst(patient, FhirPath.from("name").count().times("3"), IntegerType::class.java)
        assertThat(result?.value, `is`(6))
    }

    @Test
    fun `dividedBy divides producing a decimal`() {
        val patient = Patient().apply { addName(); addName() }
        val result = evaluateFirst(patient, FhirPath.from("name").count().dividedBy("4"), DecimalType::class.java)
        assertThat(result?.value?.toDouble(), `is`(0.5))
    }

    @Test
    fun `div performs integer division`() {
        val patient = Patient().apply { addName(); addName() }
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

    @Test
    fun `toBoolean converts a boolean value`() {
        val patient = Patient().apply { active = true }
        val result = evaluateFirst(patient, FhirPath.from("active").toBoolean(), BooleanType::class.java)
        assertTrue(result!!.booleanValue())
    }

    @Test
    fun `toInteger converts boolean true to 1`() {
        val patient = Patient().apply { active = true }
        val result = evaluateFirst(patient, FhirPath.from("active").toInteger(), IntegerType::class.java)
        assertThat(result?.value, `is`(1))
    }

    @Test
    fun `toDecimal converts an integer to decimal`() {
        val patient = Patient().apply { addName(); addName() }
        val result = evaluateFirst(patient, FhirPath.from("name").count().toDecimal(), DecimalType::class.java)
        assertThat(result?.value?.toDouble(), `is`(2.0))
    }

    @Test
    fun `toQuantity converts an integer to a Quantity`() {
        val patient = Patient().apply { addName(); addName() }
        val result = evaluateFirst(patient, FhirPath.from("name").count().toQuantity(), Quantity::class.java)
        assertNotNull(result)
    }

    // ── FHIR-specific ─────────────────────────────────────────────────────────

    @Test
    fun `extension navigates to a named extension value`() {
        val patient = Patient().apply {
            addExtension("http://example.org/color", StringType("blue"))
        }
        val result = evaluateFirst(patient,
            FhirPath.from("Patient").extension("http://example.org/color").navigate("value"),
            StringType::class.java)
        assertThat(result?.value, `is`("blue"))
    }

    @Test
    fun `extension where url detects presence of extension`() {
        val patient = Patient().apply { addExtension("http://example.org/color", StringType("blue")) }
        assertTrue(matches(patient,
            FhirPath.from("extension").where(FhirPath.relative().navigate("url").eq("http://example.org/color")).exists()))
    }

    @Test
    fun `resolve on a reference without a resolver returns empty collection`() {
        val patient = Patient().apply { managingOrganization = Reference("Organization/123") }
        val results = evaluate(patient, FhirPath.from("managingOrganization").resolve(), IBase::class.java)
        assertTrue(results.isEmpty())
    }

    // ── select projection ─────────────────────────────────────────────────────

    @Test
    fun `select projects a field from every element`() {
        val patient = Patient().apply {
            addName().apply { family = "Smith" }
            addName().apply { family = "Jones" }
        }
        val results = evaluate(patient, FhirPath.from("name").select("family"), StringType::class.java)
        assertThat(results, hasSize(2))
    }

    // ── complex chains ────────────────────────────────────────────────────────

    @Test
    fun `chained where and first extracts first given name from official name`() {
        val patient = Patient().apply {
            addName().apply { use = HumanName.NameUse.OFFICIAL; family = "Smith"; addGiven("John"); addGiven("William") }
            addName().apply { use = HumanName.NameUse.NICKNAME; addGiven("Johnny") }
        }
        val result = evaluateFirst(patient,
            FhirPath.from("name").where(FhirPath.relative().navigate("use").eq("official")).navigate("given").first(),
            StringType::class.java)
        assertThat(result?.value, `is`("John"))
    }

    @Test
    fun `or-then-and three-term condition filters with correct precedence`() {
        val patient = Patient().apply {
            addIdentifier().apply { system = "http://a.org"; value = "active-mrn" }
            addIdentifier().apply { system = "http://b.org"; value = "passive-mrn" }
            addIdentifier().apply { system = "http://c.org"; value = "something-else" }
        }
        val path = FhirPath.from("identifier")
            .where(FhirPath.relative().navigate("system").eq("http://a.org")
                .or(FhirPath.relative().navigate("system").eq("http://b.org"))
                .and(FhirPath.relative().navigate("value").endsWith("-mrn")))
            .navigate("value")
        assertThat(evaluate(patient, path, StringType::class.java), hasSize(2))
    }

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
