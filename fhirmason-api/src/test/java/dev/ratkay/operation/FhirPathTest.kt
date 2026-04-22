package dev.ratkay.operation

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.`is`
import org.junit.jupiter.api.Test

class FhirPathTest {

    // ── Factory ───────────────────────────────────────────────────────────────

    @Test
    fun `from produces root segment`() {
        assertThat(FhirPath.from("Patient").build(), `is`("Patient"))
    }

    @Test
    fun `relative produces empty base`() {
        assertThat(FhirPath.relative().build(), `is`(""))
    }

    @Test
    fun `toString delegates to build`() {
        val path = FhirPath.from("Patient").navigate("name")
        assertThat(path.toString(), `is`(path.build()))
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    @Test
    fun `navigate appends dot-segment`() {
        assertThat(FhirPath.from("Patient").navigate("name").build(), `is`("Patient.name"))
    }

    @Test
    fun `navigate on relative base omits leading dot`() {
        assertThat(FhirPath.relative().navigate("use").build(), `is`("use"))
    }

    @Test
    fun `navigate chains multiple segments`() {
        assertThat(
            FhirPath.from("Patient").navigate("name").navigate("given").build(),
            `is`("Patient.name.given")
        )
    }

    @Test
    fun `union with FhirPath produces pipe expression`() {
        val a = FhirPath.from("name")
        val b = FhirPath.from("identifier")
        assertThat(a.union(b).build(), `is`("name | identifier"))
    }

    @Test
    fun `union with string produces pipe expression`() {
        assertThat(FhirPath.from("name").union("identifier").build(), `is`("name | identifier"))
    }

    // ── Subsetting / filtering ────────────────────────────────────────────────

    @Test
    fun `where with string condition`() {
        assertThat(
            FhirPath.from("name").where("use = 'official'").build(),
            `is`("name.where(use = 'official')")
        )
    }

    @Test
    fun `where with FhirPath condition`() {
        val condition = FhirPath.relative().navigate("use").eq("official")
        assertThat(
            FhirPath.from("name").where(condition).build(),
            `is`("name.where(use = 'official')")
        )
    }

    @Test
    fun `select appends select projection`() {
        assertThat(FhirPath.from("name").select("given").build(), `is`("name.select(given)"))
    }

    @Test
    fun `ofType appends ofType call`() {
        assertThat(FhirPath.from("value").ofType("Quantity").build(), `is`("value.ofType(Quantity)"))
    }

    // ── Collection functions ──────────────────────────────────────────────────

    @Test
    fun `first appends first()`() {
        assertThat(FhirPath.from("name").first().build(), `is`("name.first()"))
    }

    @Test
    fun `last appends last()`() {
        assertThat(FhirPath.from("name").last().build(), `is`("name.last()"))
    }

    @Test
    fun `tail appends tail()`() {
        assertThat(FhirPath.from("name").tail().build(), `is`("name.tail()"))
    }

    @Test
    fun `take appends take with count`() {
        assertThat(FhirPath.from("name").take(3).build(), `is`("name.take(3)"))
    }

    @Test
    fun `skip appends skip with count`() {
        assertThat(FhirPath.from("name").skip(2).build(), `is`("name.skip(2)"))
    }

    @Test
    fun `count appends count()`() {
        assertThat(FhirPath.from("name").count().build(), `is`("name.count()"))
    }

    @Test
    fun `empty appends empty()`() {
        assertThat(FhirPath.from("name").empty().build(), `is`("name.empty()"))
    }

    @Test
    fun `exists no-arg appends exists()`() {
        assertThat(FhirPath.from("name").exists().build(), `is`("name.exists()"))
    }

    @Test
    fun `exists with string criteria`() {
        assertThat(FhirPath.from("name").exists("use = 'official'").build(), `is`("name.exists(use = 'official')"))
    }

    @Test
    fun `exists with FhirPath criteria`() {
        val criteria = FhirPath.relative().navigate("use").eq("official")
        assertThat(FhirPath.from("name").exists(criteria).build(), `is`("name.exists(use = 'official')"))
    }

    @Test
    fun `all with string criteria`() {
        assertThat(FhirPath.from("name").all("use = 'official'").build(), `is`("name.all(use = 'official')"))
    }

    @Test
    fun `all with FhirPath criteria`() {
        val criteria = FhirPath.relative().navigate("use").eq("official")
        assertThat(FhirPath.from("name").all(criteria).build(), `is`("name.all(use = 'official')"))
    }

    @Test
    fun `allTrue appends allTrue()`() {
        assertThat(FhirPath.from("active").allTrue().build(), `is`("active.allTrue()"))
    }

    @Test
    fun `anyTrue appends anyTrue()`() {
        assertThat(FhirPath.from("active").anyTrue().build(), `is`("active.anyTrue()"))
    }

    @Test
    fun `allFalse appends allFalse()`() {
        assertThat(FhirPath.from("active").allFalse().build(), `is`("active.allFalse()"))
    }

    @Test
    fun `anyFalse appends anyFalse()`() {
        assertThat(FhirPath.from("active").anyFalse().build(), `is`("active.anyFalse()"))
    }

    @Test
    fun `distinct appends distinct()`() {
        assertThat(FhirPath.from("name").distinct().build(), `is`("name.distinct()"))
    }

    @Test
    fun `isDistinct appends isDistinct()`() {
        assertThat(FhirPath.from("name").isDistinct().build(), `is`("name.isDistinct()"))
    }

    @Test
    fun `subsetOf appends subsetOf call`() {
        assertThat(FhirPath.from("a").subsetOf("b").build(), `is`("a.subsetOf(b)"))
    }

    @Test
    fun `supersetOf appends supersetOf call`() {
        assertThat(FhirPath.from("a").supersetOf("b").build(), `is`("a.supersetOf(b)"))
    }

    @Test
    fun `children appends children()`() {
        assertThat(FhirPath.from("Patient").children().build(), `is`("Patient.children()"))
    }

    @Test
    fun `descendants appends descendants()`() {
        assertThat(FhirPath.from("Patient").descendants().build(), `is`("Patient.descendants()"))
    }

    // ── Boolean operators ─────────────────────────────────────────────────────

    @Test
    fun `not appends not()`() {
        assertThat(FhirPath.from("active").not().build(), `is`("active.not()"))
    }

    @Test
    fun `and with FhirPath wraps both sides`() {
        val left = FhirPath.from("active").eq(true)
        val right = FhirPath.from("name").exists()
        assertThat(left.and(right).build(), `is`("(active = true) and (name.exists())"))
    }

    @Test
    fun `and with string wraps both sides`() {
        assertThat(FhirPath.from("active").eq(true).and("name.exists()").build(), `is`("(active = true) and (name.exists())"))
    }

    @Test
    fun `or with FhirPath wraps both sides`() {
        val left = FhirPath.from("active").eq(true)
        val right = FhirPath.from("name").exists()
        assertThat(left.or(right).build(), `is`("(active = true) or (name.exists())"))
    }

    @Test
    fun `or with string wraps both sides`() {
        assertThat(FhirPath.from("active").eq(true).or("name.exists()").build(), `is`("(active = true) or (name.exists())"))
    }

    @Test
    fun `xor with FhirPath wraps both sides`() {
        val left = FhirPath.from("a").eq(true)
        val right = FhirPath.from("b").eq(true)
        assertThat(left.xor(right).build(), `is`("(a = true) xor (b = true)"))
    }

    @Test
    fun `xor with string wraps both sides`() {
        assertThat(FhirPath.from("a").eq(true).xor("b = true").build(), `is`("(a = true) xor (b = true)"))
    }

    @Test
    fun `implies with FhirPath wraps both sides`() {
        val left = FhirPath.from("active").eq(true)
        val right = FhirPath.from("name").exists()
        assertThat(left.implies(right).build(), `is`("(active = true) implies (name.exists())"))
    }

    @Test
    fun `implies with string wraps both sides`() {
        assertThat(FhirPath.from("active").eq(true).implies("name.exists()").build(), `is`("(active = true) implies (name.exists())"))
    }

    // ── Equality / comparison operators ──────────────────────────────────────

    @Test
    fun `eq with String auto-quotes the value`() {
        assertThat(FhirPath.from("use").eq("official").build(), `is`("use = 'official'"))
    }

    @Test
    fun `eq with Boolean emits literal`() {
        assertThat(FhirPath.from("active").eq(true).build(), `is`("active = true"))
    }

    @Test
    fun `eq with Int emits literal`() {
        assertThat(FhirPath.from("count").eq(5).build(), `is`("count = 5"))
    }

    @Test
    fun `eq with Double emits literal`() {
        assertThat(FhirPath.from("score").eq(3.14).build(), `is`("score = 3.14"))
    }

    @Test
    fun `ne with String auto-quotes the value`() {
        assertThat(FhirPath.from("use").ne("official").build(), `is`("use != 'official'"))
    }

    @Test
    fun `ne with Boolean emits literal`() {
        assertThat(FhirPath.from("active").ne(false).build(), `is`("active != false"))
    }

    @Test
    fun `ne with Int emits literal`() {
        assertThat(FhirPath.from("age").ne(0).build(), `is`("age != 0"))
    }

    @Test
    fun `lt with Int emits literal`() {
        assertThat(FhirPath.from("age").lt(18).build(), `is`("age < 18"))
    }

    @Test
    fun `lt with Double emits literal`() {
        assertThat(FhirPath.from("score").lt(9.5).build(), `is`("score < 9.5"))
    }

    @Test
    fun `lt with String auto-quotes`() {
        assertThat(FhirPath.from("name").lt("M").build(), `is`("name < 'M'"))
    }

    @Test
    fun `gt with Int emits literal`() {
        assertThat(FhirPath.from("age").gt(65).build(), `is`("age > 65"))
    }

    @Test
    fun `gt with Double emits literal`() {
        assertThat(FhirPath.from("score").gt(0.5).build(), `is`("score > 0.5"))
    }

    @Test
    fun `gt with String auto-quotes`() {
        assertThat(FhirPath.from("name").gt("M").build(), `is`("name > 'M'"))
    }

    @Test
    fun `le with Int emits literal`() {
        assertThat(FhirPath.from("age").le(17).build(), `is`("age <= 17"))
    }

    @Test
    fun `le with Double emits literal`() {
        assertThat(FhirPath.from("score").le(1.0).build(), `is`("score <= 1.0"))
    }

    @Test
    fun `ge with Int emits literal`() {
        assertThat(FhirPath.from("age").ge(18).build(), `is`("age >= 18"))
    }

    @Test
    fun `ge with Double emits literal`() {
        assertThat(FhirPath.from("score").ge(0.0).build(), `is`("score >= 0.0"))
    }

    @Test
    fun `equiv produces tilde expression with auto-quoted string`() {
        assertThat(FhirPath.from("name").equiv("Smith").build(), `is`("name ~ 'Smith'"))
    }

    @Test
    fun `equiv with Int emits literal`() {
        assertThat(FhirPath.from("value").equiv(42).build(), `is`("value ~ 42"))
    }

    @Test
    fun `notEquiv produces bang-tilde expression with auto-quoted string`() {
        assertThat(FhirPath.from("name").notEquiv("Smith").build(), `is`("name !~ 'Smith'"))
    }

    @Test
    fun `notEquiv with Int emits literal`() {
        assertThat(FhirPath.from("value").notEquiv(0).build(), `is`("value !~ 0"))
    }

    @Test
    fun `memberOf produces in expression — collection is not quoted`() {
        assertThat(FhirPath.from("code").memberOf("vs").build(), `is`("code in vs"))
    }

    @Test
    fun `containsValue auto-quotes the string value`() {
        assertThat(FhirPath.from("codes").containsValue("abc").build(), `is`("codes contains 'abc'"))
    }

    // ── Type functions ────────────────────────────────────────────────────────

    @Test
    fun `isType produces is call`() {
        assertThat(FhirPath.from("value").isType("Quantity").build(), `is`("value.is(Quantity)"))
    }

    @Test
    fun `asType produces as call`() {
        assertThat(FhirPath.from("value").asType("Quantity").build(), `is`("value.as(Quantity)"))
    }

    // ── String functions ──────────────────────────────────────────────────────

    @Test
    fun `length appends length()`() {
        assertThat(FhirPath.from("family").length().build(), `is`("family.length()"))
    }

    @Test
    fun `upper appends upper()`() {
        assertThat(FhirPath.from("family").upper().build(), `is`("family.upper()"))
    }

    @Test
    fun `lower appends lower()`() {
        assertThat(FhirPath.from("family").lower().build(), `is`("family.lower()"))
    }

    @Test
    fun `trim appends trim()`() {
        assertThat(FhirPath.from("family").trim().build(), `is`("family.trim()"))
    }

    @Test
    fun `startsWith auto-quotes prefix`() {
        assertThat(FhirPath.from("family").startsWith("A").build(), `is`("family.startsWith('A')"))
    }

    @Test
    fun `endsWith auto-quotes suffix`() {
        assertThat(FhirPath.from("family").endsWith("son").build(), `is`("family.endsWith('son')"))
    }

    @Test
    fun `contains auto-quotes substring`() {
        assertThat(FhirPath.from("family").contains("an").build(), `is`("family.contains('an')"))
    }

    @Test
    fun `matches auto-quotes regex`() {
        assertThat(FhirPath.from("family").matches("[A-Z].*").build(), `is`("family.matches('[A-Z].*')"))
    }

    @Test
    fun `indexOf auto-quotes substring`() {
        assertThat(FhirPath.from("family").indexOf("a").build(), `is`("family.indexOf('a')"))
    }

    @Test
    fun `substring with start only`() {
        assertThat(FhirPath.from("family").substring(2).build(), `is`("family.substring(2)"))
    }

    @Test
    fun `substring with start and length`() {
        assertThat(FhirPath.from("family").substring(2, 4).build(), `is`("family.substring(2, 4)"))
    }

    @Test
    fun `replace auto-quotes pattern and substitution`() {
        assertThat(FhirPath.from("family").replace("a", "b").build(), `is`("family.replace('a', 'b')"))
    }

    @Test
    fun `replaceMatches auto-quotes regex and substitution`() {
        assertThat(FhirPath.from("family").replaceMatches("[aeiou]", "*").build(), `is`("family.replaceMatches('[aeiou]', '*')"))
    }

    @Test
    fun `split auto-quotes separator`() {
        assertThat(FhirPath.from("csv").split(",").build(), `is`("csv.split(',')"))
    }

    @Test
    fun `join auto-quotes separator`() {
        assertThat(FhirPath.from("parts").join(",").build(), `is`("parts.join(',')"))
    }

    // ── Math functions ────────────────────────────────────────────────────────

    @Test
    fun `abs appends abs()`() {
        assertThat(FhirPath.from("value").abs().build(), `is`("value.abs()"))
    }

    @Test
    fun `ceiling appends ceiling()`() {
        assertThat(FhirPath.from("value").ceiling().build(), `is`("value.ceiling()"))
    }

    @Test
    fun `floor appends floor()`() {
        assertThat(FhirPath.from("value").floor().build(), `is`("value.floor()"))
    }

    @Test
    fun `round no-arg appends round()`() {
        assertThat(FhirPath.from("value").round().build(), `is`("value.round()"))
    }

    @Test
    fun `round with precision appends round with arg`() {
        assertThat(FhirPath.from("value").round(2).build(), `is`("value.round(2)"))
    }

    @Test
    fun `sqrt appends sqrt()`() {
        assertThat(FhirPath.from("value").sqrt().build(), `is`("value.sqrt()"))
    }

    @Test
    fun `power appends power call`() {
        assertThat(FhirPath.from("value").power("2").build(), `is`("value.power(2)"))
    }

    @Test
    fun `truncate appends truncate()`() {
        assertThat(FhirPath.from("value").truncate().build(), `is`("value.truncate()"))
    }

    // ── Arithmetic operators ──────────────────────────────────────────────────

    @Test
    fun `plus produces addition expression`() {
        assertThat(FhirPath.from("a").plus("b").build(), `is`("a + b"))
    }

    @Test
    fun `minus produces subtraction expression`() {
        assertThat(FhirPath.from("a").minus("b").build(), `is`("a - b"))
    }

    @Test
    fun `times produces multiplication expression`() {
        assertThat(FhirPath.from("a").times("b").build(), `is`("a * b"))
    }

    @Test
    fun `dividedBy produces division expression`() {
        assertThat(FhirPath.from("a").dividedBy("b").build(), `is`("a / b"))
    }

    @Test
    fun `div produces integer division expression`() {
        assertThat(FhirPath.from("a").div("b").build(), `is`("a div b"))
    }

    @Test
    fun `mod produces modulo expression`() {
        assertThat(FhirPath.from("a").mod("b").build(), `is`("a mod b"))
    }

    @Test
    fun `concat produces ampersand expression`() {
        assertThat(FhirPath.from("a").concat("b").build(), `is`("a & b"))
    }

    // ── Type conversion ───────────────────────────────────────────────────────

    @Test
    fun `toBoolean appends toBoolean()`() {
        assertThat(FhirPath.from("value").toBoolean().build(), `is`("value.toBoolean()"))
    }

    @Test
    fun `toInteger appends toInteger()`() {
        assertThat(FhirPath.from("value").toInteger().build(), `is`("value.toInteger()"))
    }

    @Test
    fun `toDecimal appends toDecimal()`() {
        assertThat(FhirPath.from("value").toDecimal().build(), `is`("value.toDecimal()"))
    }

    @Test
    fun `toDate appends toDate()`() {
        assertThat(FhirPath.from("value").toDate().build(), `is`("value.toDate()"))
    }

    @Test
    fun `toDateTime appends toDateTime()`() {
        assertThat(FhirPath.from("value").toDateTime().build(), `is`("value.toDateTime()"))
    }

    @Test
    fun `toTime appends toTime()`() {
        assertThat(FhirPath.from("value").toTime().build(), `is`("value.toTime()"))
    }

    @Test
    fun `toQuantity appends toQuantity()`() {
        assertThat(FhirPath.from("value").toQuantity().build(), `is`("value.toQuantity()"))
    }

    // ── FHIR-specific ─────────────────────────────────────────────────────────

    @Test
    fun `extension wraps url in single quotes`() {
        assertThat(
            FhirPath.from("Patient").extension("http://hl7.org/fhir/StructureDefinition/patient-birthPlace").build(),
            `is`("Patient.extension('http://hl7.org/fhir/StructureDefinition/patient-birthPlace')")
        )
    }

    @Test
    fun `hasExtension wraps url in single quotes`() {
        assertThat(
            FhirPath.from("Patient").hasExtension("http://example.org/ext").build(),
            `is`("Patient.hasExtension('http://example.org/ext')")
        )
    }

    @Test
    fun `resolve appends resolve()`() {
        assertThat(FhirPath.from("subject").resolve().build(), `is`("subject.resolve()"))
    }

    // ── Compound chains ───────────────────────────────────────────────────────

    @Test
    fun `full official-name path`() {
        val path = FhirPath.from("Patient")
            .navigate("name")
            .where(FhirPath.relative().navigate("use").eq("official"))
            .navigate("given")
            .first()
            .build()
        assertThat(path, `is`("Patient.name.where(use = 'official').given.first()"))
    }

    @Test
    fun `compound and condition`() {
        val active = FhirPath.from("active").eq(true)
        val hasName = FhirPath.from("name").exists()
        assertThat(active.and(hasName).build(), `is`("(active = true) and (name.exists())"))
    }

    @Test
    fun `extension value path`() {
        val path = FhirPath.from("Patient")
            .extension("http://example.org/ext")
            .navigate("value")
            .build()
        assertThat(path, `is`("Patient.extension('http://example.org/ext').value"))
    }

    @Test
    fun `reference resolve chain`() {
        val path = FhirPath.from("subject")
            .resolve()
            .navigate("birthDate")
            .build()
        assertThat(path, `is`("subject.resolve().birthDate"))
    }

    @Test
    fun `immutability — reusing base produces independent paths`() {
        val base = FhirPath.from("name")
        val first = base.first()
        val last = base.last()
        assertThat(first.build(), `is`("name.first()"))
        assertThat(last.build(), `is`("name.last()"))
        assertThat(base.build(), `is`("name"))
    }
}
