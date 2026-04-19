package dev.ratkay.operation.r4

import dev.ratkay.operation.DateTimeInput
import dev.ratkay.operation.ErrorStrategy
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.both
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.empty
import org.hamcrest.Matchers.endsWith
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.instanceOf
import org.hamcrest.Matchers.`is`
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.notNullValue
import org.hamcrest.Matchers.sameInstance
import org.hl7.fhir.r4.model.Appointment
import org.hl7.fhir.r4.model.Base
import org.hl7.fhir.r4.model.BooleanType
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.CanonicalType
import org.hl7.fhir.r4.model.CodeType
import org.hl7.fhir.r4.model.CodeableConcept
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.Coverage
import org.hl7.fhir.r4.model.DateTimeType
import org.hl7.fhir.r4.model.DateType
import org.hl7.fhir.r4.model.DecimalType
import org.hl7.fhir.r4.model.Extension
import org.hl7.fhir.r4.model.Identifier
import org.hl7.fhir.r4.model.InstantType
import org.hl7.fhir.r4.model.IntegerType
import org.hl7.fhir.r4.model.OperationOutcome
import org.hl7.fhir.r4.model.Parameters
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.Period
import org.hl7.fhir.r4.model.Quantity
import org.hl7.fhir.r4.model.Reference
import org.hl7.fhir.r4.model.Resource
import org.hl7.fhir.r4.model.StringType
import org.hl7.fhir.r4.model.TimeType
import org.hl7.fhir.r4.model.UriType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.Year
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Calendar
import java.util.Date
import java.util.TimeZone
import dev.ratkay.operation.r4.FhirFilter

class OperationResultTest {

    // ── Factory: of(value) ────────────────────────────────────────────────────

    @Test
    fun `of single value uses fhirType as key`() {
        val patient = patient()
        val result = OperationResult.of(patient)

        assertTrue(result.containsKey("patient"))
        assertEquals(1, result.count("patient"))
        assertThat(result.getResult(), sameInstance(patient))
    }

    @Test
    fun `of single value with explicit name uses that name`() {
        val result = OperationResult.of(patient(), "myPatient")

        assertTrue(result.containsKey("myPatient"))
        assertFalse(result.containsKey("patient"))
    }

    // ── Factory: of(list) ─────────────────────────────────────────────────────

    @Test
    fun `of list without name uses fhirType per item as key`() {
        val result = OperationResult.of(listOf(patient(), patient()))

        assertEquals(2, result.count("patient"))
    }

    @Test
    fun `of list with mixed types without name uses each item fhirType`() {
        val result = OperationResult.of(listOf(patient(), appointment()))

        assertEquals(1, result.count("patient"))
        assertEquals(1, result.count("appointment"))
    }

    @Test
    fun `of list with name groups all items under that name`() {
        val result = OperationResult.of(listOf(patient(), appointment()), "items")

        assertEquals(2, result.count("items"))
        assertFalse(result.containsKey("patient"))
        assertFalse(result.containsKey("appointment"))
    }

    // ── add { } ───────────────────────────────────────────────────────────────

    @Test
    fun `add lambda without name uses fhirType as key`() {
        val appt = appointment()
        val result = OperationResult.of(patient())
            .add { appt }

        assertTrue(result.containsKey("appointment"))
        assertThat(result.getResult(), sameInstance(appt))
    }

    @Test
    fun `add lambda with explicit name uses that name`() {
        val result = OperationResult.of(patient())
            .add("myAppt") { appointment() }

        assertTrue(result.containsKey("myAppt"))
        assertFalse(result.containsKey("appointment"))
    }

    @Test
    fun `add accumulates previous resources alongside new one`() {
        val result = OperationResult.of(patient())
            .add { appointment() }

        assertTrue(result.containsKey("patient"))
        assertTrue(result.containsKey("appointment"))
    }

    @Test
    fun `add multiple items under same key accumulates them`() {
        val result = OperationResult.of(patient())
            .add { appointment() }
            .add { appointment() }

        assertEquals(2, result.count("appointment"))
        assertEquals(3, result.totalCount())
    }

    // ── addUsing(builder: (T) -> R) ───────────────────────────────────────────

    @Test
    fun `addUsing passes current result to builder`() {
        val patient = patient()
        var received: Patient? = null

        OperationResult.of(patient)
            .addUsing { p -> received = p; appointment() }

        assertThat(received, sameInstance(patient))
    }

    @Test
    fun `addUsing with explicit name`() {
        val result = OperationResult.of(patient(), "patient")
            .addUsing("appt") { _ -> appointment() }

        assertTrue(result.containsKey("patient"))
        assertTrue(result.containsKey("appt"))
    }

    // ── addAll { } ────────────────────────────────────────────────────────────

    @Test
    fun `addAll without name uses fhirType per item`() {
        val result = OperationResult.of(patient())
            .addAll { listOf(patient(), appointment()) }

        assertEquals(2, result.count("patient"))
        assertEquals(1, result.count("appointment"))
    }

    @Test
    fun `addAll with name groups all items under that name`() {
        val result = OperationResult.of(patient())
            .addAll("resources") { listOf(patient(), appointment()) }

        assertEquals(2, result.count("resources"))
        assertFalse(result.containsKey("appointment"))
    }

    // ── addAllUsing(builder: (T) -> List<R>) ─────────────────────────────────

    @Test
    fun `addAllUsing passes current result to builder`() {
        val patient = patient()
        val result = OperationResult.of(patient)
            .addAllUsing { p -> listOf(p, appointment()) }

        assertEquals(2, result.count("patient"))
        assertEquals(1, result.count("appointment"))
    }

    @Test
    fun `addAllUsing with explicit name`() {
        val result = OperationResult.of(patient())
            .addAllUsing("items") { _ -> listOf(appointment(), appointment()) }

        assertEquals(2, result.count("items"))
    }

    // ── addFrom ───────────────────────────────────────────────────────────────

    @Test
    fun `addFrom filters existing values by type and name then passes to builder`() {
        val p1 = patient()
        val p2 = patient()
        val result = OperationResult.of(listOf(p1, p2), "people")
            .addFrom("people", Patient::class) { patients ->
                OperationOutcome().apply {
                    issue = patients.map { OperationOutcome.OperationOutcomeIssueComponent() }
                }
            }

        // 2 original patients + 1 new OperationOutcome, all under "people"
        assertEquals(3, result.count("people"))
        assertThat(result.getByType<OperationOutcome>(), hasSize(1))
    }

    @Test
    fun `addFrom returns empty list when key does not exist`() {
        val result = OperationResult.of(patient())
            .addFrom("missing", Patient::class) { patients ->
                Appointment().apply { addParticipant().actor = Reference().apply { display = "count=${patients.size}" } }
            }

        val appt: Appointment = result.getResult()
        assertEquals("count=0", appt.participantFirstRep.actor.display)
    }

    // ── addAllFrom ────────────────────────────────────────────────────────────

    @Test
    fun `addAllFrom filters by type and maps to list added under same name`() {
        val result = OperationResult.of(listOf(patient(), appointment()), "items")
            .addAllFrom("items", Patient::class) { patients ->
                patients.map { OperationOutcome() }
            }

        // 1 patient + 1 appointment + 1 outcome (mapped from patient)
        assertEquals(3, result.count("items"))
        assertThat(result.getByType<OperationOutcome>(), hasSize(1))
    }

    // ── addFromFiltered (extension filters) ──────────────────────────────────

    @Test
    fun `addFromHavingAllExtensions returns only resources that have the matching extension URL`() {
        val enrolled = patient().apply { addExtension("http://example.org/enrolled", StringType("true")) }
        val notEnrolled = patient()
        val result = OperationResult.of(listOf(enrolled, notEnrolled), "patients")
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasAllExtensions("http://example.org/enrolled")) { filtered ->
                OperationOutcome().apply {
                    addIssue().diagnostics = "count=${filtered.size}"
                }
            }

        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingAllExtensions with multiple URLs requires resource to carry every URL`() {
        val both = patient().apply {
            addExtension("http://example.org/url1", StringType("a"))
            addExtension("http://example.org/url2", StringType("b"))
        }
        val onlyFirst = patient().apply { addExtension("http://example.org/url1", StringType("a")) }
        val result = OperationResult.of(listOf(both, onlyFirst), "patients")
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasAllExtensions("http://example.org/url1", "http://example.org/url2")) { filtered ->
                OperationOutcome().apply {
                    addIssue().diagnostics = "count=${filtered.size}"
                }
            }

        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingAnyExtension returns resources carrying at least one of the URLs`() {
        val hasFirst = patient().apply { addExtension("http://example.org/url1", StringType("a")) }
        val hasSecond = patient().apply { addExtension("http://example.org/url2", StringType("b")) }
        val hasBoth = patient().apply {
            addExtension("http://example.org/url1", StringType("a"))
            addExtension("http://example.org/url2", StringType("b"))
        }
        val hasNeither = patient()
        val result = OperationResult.of(listOf(hasFirst, hasSecond, hasBoth, hasNeither), "patients")
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasAnyExtension("http://example.org/url1", "http://example.org/url2")) { filtered ->
                OperationOutcome().apply {
                    addIssue().diagnostics = "count=${filtered.size}"
                }
            }

        assertEquals("count=3", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingAnyExtension with single URL behaves like addFromHavingAllExtensions`() {
        val hasIt = patient().apply { addExtension("http://example.org/url1", StringType("a")) }
        val hasNot = patient()
        val result = OperationResult.of(listOf(hasIt, hasNot), "patients")
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasAnyExtension("http://example.org/url1")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingAllExtensions with no extUrls returns all resources of that type`() {
        val result = OperationResult.of(listOf(patient(), patient(), appointment()), "items")
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasAllExtensions()) { filtered ->
                OperationOutcome().apply {
                    addIssue().diagnostics = "count=${filtered.size}"
                }
            }

        assertEquals("count=2", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingAllExtensions produces empty list when no resources match`() {
        val result = OperationResult.of(listOf(patient(), patient()), "patients")
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasAllExtensions("http://example.org/nonexistent")) { filtered ->
                OperationOutcome().apply {
                    addIssue().diagnostics = "count=${filtered.size}"
                }
            }

        assertEquals("count=0", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingAnyExtension produces empty list when no resources match any URL`() {
        val result = OperationResult.of(listOf(patient(), patient()), "patients")
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasAnyExtension("http://example.org/nonexistent")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=0", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingAllExtensions searches across all parameter keys not just one`() {
        val enrolled1 = patient().apply { addExtension("http://example.org/enrolled", StringType("true")) }
        val enrolled2 = patient().apply { addExtension("http://example.org/enrolled", StringType("true")) }
        val result = OperationResult.of(enrolled1, "group-a")
            .add("group-b") { enrolled2 }
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasAllExtensions("http://example.org/enrolled")) { filtered ->
                OperationOutcome().apply {
                    addIssue().diagnostics = "count=${filtered.size}"
                }
            }

        assertEquals("count=2", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addAllFromHavingAllExtensions returns list result built from AND-filtered input`() {
        val enrolled = patient().apply { addExtension("http://example.org/enrolled", StringType("true")) }
        val result = OperationResult.of(listOf(enrolled, patient()), "patients")
            .addAllFromFiltered(type = Patient::class, predicate = FhirFilter.hasAllExtensions("http://example.org/enrolled")) { filtered ->
                filtered.map { OperationOutcome() }
            }

        assertThat(result.getResult(), hasSize(1))
        assertThat(result.getByType<OperationOutcome>(), hasSize(1))
    }

    @Test
    fun `addAllFromHavingAnyExtension returns list result built from OR-filtered input`() {
        val hasFirst = patient().apply { addExtension("http://example.org/url1", StringType("a")) }
        val hasSecond = patient().apply { addExtension("http://example.org/url2", StringType("b")) }
        val result = OperationResult.of(listOf(hasFirst, hasSecond, patient()), "patients")
            .addAllFromFiltered(type = Patient::class, predicate = FhirFilter.hasAnyExtension("http://example.org/url1", "http://example.org/url2")) { filtered ->
                filtered.map { OperationOutcome() }
            }

        assertThat(result.getResult(), hasSize(2))
    }

    @Test
    fun `addFromHavingAllExtensions reified overload works correctly`() {
        val enrolled = patient().apply { addExtension("http://example.org/enrolled", StringType("true")) }
        val result = OperationResult.of(listOf(enrolled, patient()), "patients")
            .addFromFiltered<Patient, OperationOutcome>(predicate = FhirFilter.hasAllExtensions("http://example.org/enrolled")) { filtered ->
                OperationOutcome().apply {
                    addIssue().diagnostics = "count=${filtered.size}"
                }
            }

        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingAnyExtension reified overload works correctly`() {
        val hasFirst = patient().apply { addExtension("http://example.org/url1", StringType("a")) }
        val hasSecond = patient().apply { addExtension("http://example.org/url2", StringType("b")) }
        val result = OperationResult.of(listOf(hasFirst, hasSecond, patient()), "patients")
            .addFromFiltered<Patient, OperationOutcome>(predicate = FhirFilter.hasAnyExtension("http://example.org/url1", "http://example.org/url2")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=2", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingAllExtensions records error outcome when builder throws`() {
        val result = OperationResult.of(patient(), "patients")
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasAllExtensions()) { _ ->
                throw RuntimeException("builder failed")
            }

        assertTrue(result.hasErrors())
        assertThat(result.getOutcomes(), hasSize(1))
    }

    @Test
    fun `addFromHavingAllExtensions unnamed overload stores result under output fhirType not input type`() {
        val enrolled = patient().apply { addExtension("http://example.org/enrolled", StringType("true")) }
        val result = OperationResult.of(listOf(enrolled, patient()), "patients")
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasAllExtensions("http://example.org/enrolled")) { _ ->
                OperationOutcome().apply { addIssue().diagnostics = "found" }
            }

        // Key must be "operationoutcome" (output fhirType), NOT "patient" (input type)
        assertThat(result.getAll("operationoutcome"), hasSize(1))
        assertFalse(result.containsKey("patient"))
    }

    @Test
    fun `addFromHavingAnyExtension unnamed overload stores result under output fhirType not input type`() {
        val hasExt = patient().apply { addExtension("http://example.org/url1", StringType("a")) }
        val result = OperationResult.of(listOf(hasExt, patient()), "patients")
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasAnyExtension("http://example.org/url1")) { _ ->
                OperationOutcome().apply { addIssue().diagnostics = "found" }
            }

        assertThat(result.getAll("operationoutcome"), hasSize(1))
        assertFalse(result.containsKey("patient"))
    }

    @Test
    fun `addFromHavingAllExtensions named overload stores result under explicit name`() {
        val enrolled = patient().apply { addExtension("http://example.org/enrolled", StringType("true")) }
        val result = OperationResult.of(listOf(enrolled, patient()), "patients")
            .addFromFiltered("enrolled-summary", Patient::class, FhirFilter.hasAllExtensions("http://example.org/enrolled")) { filtered ->
                OperationOutcome().apply {
                    addIssue().diagnostics = "count=${filtered.size}"
                }
            }

        assertTrue(result.containsKey("enrolled-summary"))
        val summary = result.takeFirstTyped("enrolled-summary", OperationOutcome::class)
        assertEquals("count=1", summary!!.issueFirstRep.diagnostics)
    }

    @Test
    fun `addAllFromHavingAllExtensions reified overload returns list result`() {
        val enrolled = patient().apply { addExtension("http://example.org/enrolled", StringType("true")) }
        val result = OperationResult.of(listOf(enrolled, patient(), patient()), "patients")
            .addAllFromFiltered<Patient, OperationOutcome>(predicate = FhirFilter.hasAllExtensions("http://example.org/enrolled")) { filtered ->
                filtered.map { OperationOutcome() }
            }

        assertThat(result.getResult(), hasSize(1))
    }

    @Test
    fun `addAllFromHavingAllExtensions unnamed overload stores results under output fhirType not input type`() {
        val enrolled = patient().apply { addExtension("http://example.org/enrolled", StringType("true")) }
        val result = OperationResult.of(listOf(enrolled, patient()), "patients")
            .addAllFromFiltered(type = Patient::class, predicate = FhirFilter.hasAllExtensions("http://example.org/enrolled")) { filtered ->
                filtered.map { OperationOutcome() }
            }

        assertThat(result.getAll("operationoutcome"), hasSize(1))
        assertFalse(result.containsKey("patient"))
    }

    @Test
    fun `addAllFromHavingAnyExtension unnamed overload stores results under output fhirType not input type`() {
        val hasFirst = patient().apply { addExtension("http://example.org/url1", StringType("a")) }
        val hasSecond = patient().apply { addExtension("http://example.org/url2", StringType("b")) }
        val result = OperationResult.of(listOf(hasFirst, hasSecond, patient()), "patients")
            .addAllFromFiltered(type = Patient::class, predicate = FhirFilter.hasAnyExtension("http://example.org/url1", "http://example.org/url2")) { filtered ->
                filtered.map { OperationOutcome() }
            }

        assertThat(result.getAll("operationoutcome"), hasSize(2))
        assertFalse(result.containsKey("patient"))
    }

    // ── Extension URL + value-type / value-predicate filters ─────────────────

    @Test
    fun `addFromHavingExtensionWithValueType returns resources where extension at URL has matching value type`() {
        val withString = patient().apply { addExtension("http://example.org/flag", StringType("active")) }
        val withBoolean = patient().apply { addExtension("http://example.org/flag", BooleanType(true)) }
        val noExtension = patient()
        val result = OperationResult.of(listOf(withString, withBoolean, noExtension), "patients")
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasExtensionWithValueType("http://example.org/flag", StringType::class)) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingExtensionWithValueType returns empty list when no resources have that value type at URL`() {
        val withBoolean = patient().apply { addExtension("http://example.org/flag", BooleanType(false)) }
        val result = OperationResult.of(listOf(withBoolean, patient()), "patients")
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasExtensionWithValueType("http://example.org/flag", StringType::class)) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=0", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingExtensionWithValueType named variant stores result under explicit name`() {
        val withString = patient().apply { addExtension("http://example.org/flag", StringType("active")) }
        val result = OperationResult.of(listOf(withString, patient()), "patients")
            .addFromFiltered("flag-summary", Patient::class, FhirFilter.hasExtensionWithValueType("http://example.org/flag", StringType::class)) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertTrue(result.containsKey("flag-summary"))
        val summary = result.takeFirstTyped("flag-summary", OperationOutcome::class)
        assertEquals("count=1", summary!!.issueFirstRep.diagnostics)
    }

    @Test
    fun `addAllFromHavingExtensionWithValueType returns list result`() {
        val withString1 = patient().apply { addExtension("http://example.org/flag", StringType("a")) }
        val withString2 = patient().apply { addExtension("http://example.org/flag", StringType("b")) }
        val withBoolean = patient().apply { addExtension("http://example.org/flag", BooleanType(true)) }
        val result = OperationResult.of(listOf(withString1, withString2, withBoolean), "patients")
            .addAllFromFiltered(type = Patient::class, predicate = FhirFilter.hasExtensionWithValueType("http://example.org/flag", StringType::class)) { filtered ->
                filtered.map { OperationOutcome() }
            }

        assertThat(result.getResult(), hasSize(2))
    }

    @Test
    fun `addFromHavingExtensionWithValueType reified overload works correctly`() {
        val withString = patient().apply { addExtension("http://example.org/flag", StringType("x")) }
        val result = OperationResult.of(listOf(withString, patient()), "patients")
            .addFromFiltered<Patient, OperationOutcome>(predicate = FhirFilter.hasExtensionWithValueType<StringType>("http://example.org/flag")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingExtensionValueMatching returns resources where extension value satisfies predicate`() {
        val enrolled = patient().apply { addExtension("http://example.org/enrolled", StringType("true")) }
        val notEnrolled = patient().apply { addExtension("http://example.org/enrolled", StringType("false")) }
        val noExt = patient()
        val result = OperationResult.of(listOf(enrolled, notEnrolled, noExt), "patients")
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasExtensionValueMatching<StringType>("http://example.org/enrolled") { it.value == "true" }) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingExtensionValueMatching returns empty list when predicate never satisfied`() {
        val patient1 = patient().apply { addExtension("http://example.org/enrolled", StringType("false")) }
        val result = OperationResult.of(listOf(patient1, patient()), "patients")
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasExtensionValueMatching<StringType>("http://example.org/enrolled") { it.value == "true" }) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=0", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingExtensionValueMatching named variant stores result under explicit name`() {
        val enrolled = patient().apply { addExtension("http://example.org/enrolled", StringType("true")) }
        val result = OperationResult.of(listOf(enrolled, patient()), "patients")
            .addFromFiltered("enrolled-patients", Patient::class, FhirFilter.hasExtensionValueMatching<StringType>("http://example.org/enrolled") { it.value == "true" }) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertTrue(result.containsKey("enrolled-patients"))
        val summary = result.takeFirstTyped("enrolled-patients", OperationOutcome::class)
        assertEquals("count=1", summary!!.issueFirstRep.diagnostics)
    }

    @Test
    fun `addAllFromHavingExtensionValueMatching returns list result`() {
        val high = patient().apply { addExtension("http://example.org/priority", IntegerType(10)) }
        val low = patient().apply { addExtension("http://example.org/priority", IntegerType(1)) }
        val none = patient()
        val result = OperationResult.of(listOf(high, low, none), "patients")
            .addAllFromFiltered(type = Patient::class, predicate = FhirFilter.hasExtensionValueMatching<IntegerType>("http://example.org/priority") { it.value > 5 }) { filtered ->
                filtered.map { OperationOutcome() }
            }

        assertThat(result.getResult(), hasSize(1))
    }

    @Test
    fun `addFromHavingExtensionValueMatching reified overload works correctly`() {
        val enrolled = patient().apply { addExtension("http://example.org/enrolled", BooleanType(true)) }
        val notEnrolled = patient().apply { addExtension("http://example.org/enrolled", BooleanType(false)) }
        val result = OperationResult.of(listOf(enrolled, notEnrolled), "patients")
            .addFromFiltered<Patient, OperationOutcome>(predicate = FhirFilter.hasExtensionValueMatching<BooleanType>("http://example.org/enrolled") { it.booleanValue() }) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingExtensionValueMatching with multiple extensions at same URL — any satisfying match passes`() {
        val multiExt = patient().apply {
            addExtension("http://example.org/role", StringType("admin"))
            addExtension("http://example.org/role", StringType("user"))
        }
        val userOnly = patient().apply { addExtension("http://example.org/role", StringType("user")) }
        val result = OperationResult.of(listOf(multiExt, userOnly, patient()), "patients")
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasExtensionValueMatching<StringType>("http://example.org/role") { it.value == "admin" }) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingExtensionValueMatching unnamed overload stores result under output fhirType not input type`() {
        val enrolled = patient().apply { addExtension("http://example.org/enrolled", StringType("true")) }
        val result = OperationResult.of(listOf(enrolled, patient()), "patients")
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasExtensionValueMatching<StringType>("http://example.org/enrolled") { it.value == "true" }) { _ ->
                OperationOutcome().apply { addIssue().diagnostics = "found" }
            }

        assertThat(result.getAll("operationoutcome"), hasSize(1))
        assertFalse(result.containsKey("patient"))
    }

    @Test
    fun `addAllFromHavingExtensionValueMatching unnamed overload stores results under output fhirType not input type`() {
        val high = patient().apply { addExtension("http://example.org/priority", IntegerType(10)) }
        val result = OperationResult.of(listOf(high, patient()), "patients")
            .addAllFromFiltered(type = Patient::class, predicate = FhirFilter.hasExtensionValueMatching<IntegerType>("http://example.org/priority") { it.value > 5 }) { filtered ->
                filtered.map { OperationOutcome() }
            }

        assertThat(result.getAll("operationoutcome"), hasSize(1))
        assertFalse(result.containsKey("patient"))
    }

    // ── Query methods ─────────────────────────────────────────────────────────

    @Test
    fun `getAllParameters returns immutable snapshot of all entries`() {
        val result = OperationResult.of(patient())
            .add { appointment() }

        val all = result.getAllParameters()
        assertThat(all.keys, containsInAnyOrder("patient", "appointment"))
        assertThat(all["patient"], hasSize(1))
    }

    @Test
    fun `getAll returns empty list for unknown key`() {
        assertThat(OperationResult.of(patient()).getAll("unknown"), empty())
    }

    @Test
    fun `getByType returns all instances of that type across all keys`() {
        val result = OperationResult.of(listOf(patient(), patient()), "people")
            .add("appt") { appointment() }

        assertThat(result.getByType<Patient>(), hasSize(2))
        assertThat(result.getByType<Appointment>(), hasSize(1))
        assertThat(result.getByType<OperationOutcome>(), empty())
    }

    @Test
    fun `containsKey returns false for missing key`() {
        assertFalse(OperationResult.of(patient()).containsKey("appointment"))
    }

    @Test
    fun `getKeys returns the set of all parameter names`() {
        val result = OperationResult.of(patient())
            .add { appointment() }

        assertThat(result.getKeys(), containsInAnyOrder("patient", "appointment"))
    }

    @Test
    fun `count returns zero for unknown key`() {
        assertEquals(0, OperationResult.of(patient()).count("unknown"))
    }

    @Test
    fun `totalCount sums all values across all keys`() {
        val result = OperationResult.of(patient())
            .add { appointment() }
            .add { appointment() }

        assertEquals(3, result.totalCount())
    }

    @Test
    fun `isEmpty returns false when parameters are present`() {
        assertFalse(OperationResult.of(patient()).isEmpty())
    }

    @Test
    fun `isNotEmpty returns true when parameters are present`() {
        assertTrue(OperationResult.of(patient()).isNotEmpty())
    }

    @Test
    fun `getResult returns the most recently added value`() {
        val patient = patient()
        val result = OperationResult.of(patient)
        // Typed assignment proves the compiler tracks T = Patient through of()
        val retrieved: Patient = result.getResult()
        assertThat(retrieved, sameInstance(patient))
    }

    // ── Functional transformations ────────────────────────────────────────────

    @Test
    fun `filterByType keeps only entries whose values match the given type`() {
        val result = OperationResult.of(patient())
            .add { appointment() }
            .filterByType<Patient>()

        assertTrue(result.containsKey("patient"))
        assertFalse(result.containsKey("appointment"))
    }

    @Test
    fun `filterByType removes key entirely when no values match`() {
        val result = OperationResult.of(patient())
            .filterByType<Appointment>()

        assertTrue(result.isEmpty())
    }

    @Test
    fun `filterByName keeps only the entry with the given name`() {
        val result = OperationResult.of(patient())
            .add { appointment() }
            .filterByName("appointment")

        assertTrue(result.containsKey("appointment"))
        assertFalse(result.containsKey("patient"))
    }

    @Test
    fun `filterByName returns empty result for unknown name`() {
        val result = OperationResult.of(patient())
            .filterByName("unknown")

        assertTrue(result.isEmpty())
    }

    @Test
    fun `mapValues transforms every value in all entries`() {
        val result = OperationResult.of(patient(), "p")
            .mapValues { _ -> appointment() }

        assertThat(result.getByType<Appointment>(), hasSize(1))
        assertThat(result.getByType<Patient>(), empty())
    }

    // ── toParameters ─────────────────────────────────────────────────────────

    @Test
    fun `toParameters produces a Parameters resource with correct entries`() {
        val patient = patient()
        val params = OperationResult.of(patient, "patient").toParameters()

        assertTrue(params.hasParameter("patient"))
        assertThat(params.getParameter("patient").resource, sameInstance(patient))
    }

    @Test
    fun `toParameters produces one parameter entry per stored value`() {
        // Use the (Patient) -> R overload explicitly to avoid ambiguity
        val params = OperationResult.of(patient(), "patient")
            .addUsing("appt") { _ -> appointment() }
            .toParameters()

        assertThat(params.parameter, hasSize(2))
        assertTrue(params.hasParameter("patient"))
        assertTrue(params.hasParameter("appt"))
    }

    @Test
    fun `toParameters produces multiple entries for the same name`() {
        val params = OperationResult.of(listOf(patient(), patient()), "patient")
            .toParameters()

        val patientParams = params.parameter.filter { it.name == "patient" }
        assertThat(patientParams, hasSize(2))
    }

    @Test
    fun `toParameters sets value for Type entries and resource for Resource entries`() {
        val stringVal = StringType("hello")
        val params = OperationResult.of(stringVal, "msg")
            .addUsing("p") { _ -> patient() }
            .toParameters()

        val msgParam = params.getParameter("msg")
        assertThat(msgParam.value, instanceOf(StringType::class.java))

        val pParam = params.getParameter("p")
        assertThat(pParam.resource, instanceOf(Patient::class.java))
    }

    // ── Error handling: FAIL_FAST ─────────────────────────────────────────────

    @Test
    fun `add - exception in FAIL_FAST records outcome and stops pipeline`() {
        val result = OperationResult.of(patient())
            .add { error("step failed") }
            .add { appointment() }

        assertTrue(result.hasErrors())
        assertFalse(result.isSuccessful())
        assertFalse(result.containsKey("appointment"))
        assertEquals(1, result.getOutcomes().size)
    }

    @Test
    fun `add - exception sets ERROR severity on outcome`() {
        val result = OperationResult.of(patient())
            .add { error("boom") }

        val issue = result.getOutcomes().first().issueFirstRep
        assertEquals(OperationOutcome.IssueSeverity.ERROR, issue.severity)
        assertEquals("boom", issue.diagnostics)
    }

    @Test
    fun `isSuccessful returns true when no errors`() {
        val result = OperationResult.of(patient())
        assertTrue(result.isSuccessful())
        assertFalse(result.hasErrors())
    }

    // ── Error handling: ACCUMULATE ────────────────────────────────────────────

    @Test
    fun `add - exception in ACCUMULATE continues pipeline and collects outcome`() {
        val result = OperationResult.of(patient(), errorStrategy = ErrorStrategy.ACCUMULATE)
            .add { error("step 1 failed") }
            .add { appointment() }

        assertTrue(result.hasErrors())
        assertTrue(result.containsKey("appointment"))
        assertEquals(1, result.getOutcomes().size)
    }

    @Test
    fun `ACCUMULATE - multiple failing steps collect all outcomes`() {
        val result = OperationResult.of(patient(), errorStrategy = ErrorStrategy.ACCUMULATE)
            .add { error("step 1 failed") }
            .add { error("step 2 failed") }
            .add { appointment() }

        assertEquals(2, result.getOutcomes().size)
        assertTrue(result.containsKey("appointment"))
    }

    // ── addOrSkip ─────────────────────────────────────────────────────────────

    @Test
    fun `addOrSkip - skips step on exception and logs WARNING outcome`() {
        val result = OperationResult.of(patient())
            .addOrSkip { error("optional step failed") }

        assertTrue(result.containsKey("patient"))
        assertTrue(result.hasErrors())
        assertEquals(1, result.getOutcomes().size)
        assertEquals(OperationOutcome.IssueSeverity.WARNING, result.getOutcomes().first().issueFirstRep.severity)
    }

    @Test
    fun `addOrSkip - preserves previous result type when step is skipped`() {
        val patient = patient()
        val result = OperationResult.of(patient)
            .addOrSkip { error("skip me") }

        // T is still Patient — typed assignment verifies the compiler preserves the type
        val retrieved: Patient = result.getResult()
        assertThat(retrieved, sameInstance(patient))
    }

    @Test
    fun `addOrSkip - adds resource normally when step succeeds`() {
        val result = OperationResult.of(patient())
            .addOrSkip { appointment() }

        assertTrue(result.containsKey("appointment"))
        assertFalse(result.hasErrors())
    }

    // ── addOrDefault ──────────────────────────────────────────────────────────

    @Test
    fun `addOrDefault - uses default value on exception and logs WARNING outcome`() {
        val defaultAppt = appointment()
        val result = OperationResult.of(patient())
            .addOrDefault(default = defaultAppt) { error("step failed") }

        assertTrue(result.containsKey("appointment"))
        assertThat(result.getAll("appointment").first(), sameInstance(defaultAppt))
        assertEquals(1, result.getOutcomes().size)
        assertEquals(OperationOutcome.IssueSeverity.WARNING, result.getOutcomes().first().issueFirstRep.severity)
    }

    @Test
    fun `addOrDefault - uses builder result when step succeeds`() {
        val successAppt = appointment()
        val defaultAppt = appointment()
        val result = OperationResult.of(patient())
            .addOrDefault(default = defaultAppt) { successAppt }

        // T = Appointment — typed assignment proves no cast is needed
        val appt: Appointment = result.getResult()
        assertThat(appt, sameInstance(successAppt))
        assertFalse(result.hasErrors())
    }

    @Test
    fun `addOrDefault - explicit name used for both success and default paths`() {
        val defaultAppt = appointment()
        val result = OperationResult.of(patient())
            .addOrDefault("myAppt", defaultAppt) { error("boom") }

        assertTrue(result.containsKey("myAppt"))
        assertFalse(result.containsKey("appointment"))
    }

    // ── toOperationOutcome (merging) ──────────────────────────────────────────

    @Test
    fun `toOperationOutcome - merges all collected issues into single OperationOutcome`() {
        val result = OperationResult.of(patient(), errorStrategy = ErrorStrategy.ACCUMULATE)
            .add { error("step 1 failed") }
            .add { error("step 2 failed") }

        val merged = result.toOperationOutcome()
        assertEquals(2, merged.issue.size)
    }

    @Test
    fun `toOperationOutcome - returns empty OperationOutcome when pipeline is successful`() {
        val result = OperationResult.of(patient())
            .add { appointment() }

        val outcome = result.toOperationOutcome()
        assertTrue(outcome.issue.isEmpty())
    }

    // ── toBundle ──────────────────────────────────────────────────────────────

    @Test
    fun `toBundle COLLECTION - produces bundle with all resources preserving insertion order`() {
        val result = OperationResult.of(patient(), "patient")
            .add("appt") { appointment() }

        val bundle = result.toBundle(Bundle.BundleType.COLLECTION)

        assertEquals(Bundle.BundleType.COLLECTION, bundle.type)
        assertThat(bundle.entry, hasSize(2))
        assertThat(bundle.entry[0].resource, instanceOf(Patient::class.java))
        assertThat(bundle.entry[1].resource, instanceOf(Appointment::class.java))
    }

    @Test
    fun `toBundle TRANSACTION - infers PUT with resourceType slash id when resource has id`() {
        val result = OperationResult.of(Patient().apply { id = "p1" }, "patient")

        val bundle = result.toBundle(Bundle.BundleType.TRANSACTION)

        val entry = bundle.entryFirstRep
        assertEquals(Bundle.HTTPVerb.PUT, entry.request.method)
        assertEquals("Patient/p1", entry.request.url)
    }

    @Test
    fun `toBundle TRANSACTION - infers POST with resourceType when resource has no id`() {
        val result = OperationResult.of(patient(), "patient")

        val bundle = result.toBundle(Bundle.BundleType.TRANSACTION)

        val entry = bundle.entryFirstRep
        assertEquals(Bundle.HTTPVerb.POST, entry.request.method)
        assertEquals("Patient", entry.request.url)
    }

    @Test
    fun `toBundle BATCH - infers HTTP method same as TRANSACTION`() {
        val result = OperationResult.of(Patient().apply { id = "p2" }, "patient")

        val bundle = result.toBundle(Bundle.BundleType.BATCH)

        val entry = bundle.entryFirstRep
        assertEquals(Bundle.HTTPVerb.PUT, entry.request.method)
        assertEquals("Patient/p2", entry.request.url)
    }

    @Test
    fun `toBundle SEARCHSET - adds search mode MATCH and sets total`() {
        val result = OperationResult.of(listOf(patient(), patient()), "patients")

        val bundle = result.toBundle(Bundle.BundleType.SEARCHSET)

        assertEquals(Bundle.BundleType.SEARCHSET, bundle.type)
        assertEquals(2, bundle.total)
        bundle.entry.forEach { entry ->
            assertEquals(Bundle.SearchEntryMode.MATCH, entry.search.mode)
        }
    }

    @Test
    fun `toBundle with configBlock - applies custom configuration to each entry`() {
        val result = OperationResult.of(patient(), "patient")

        val bundle = result.toBundle(Bundle.BundleType.COLLECTION) { entry ->
            entry.fullUrl = "http://example.com/fhir/Patient/custom"
        }

        assertEquals("http://example.com/fhir/Patient/custom", bundle.entryFirstRep.fullUrl)
    }

    @Test
    fun `toBundleEntry - produces entry with resource set`() {
        val p = patient()
        val result = OperationResult.of(p)

        val entry = result.toBundleEntry(p)

        assertThat(entry.resource, sameInstance(p))
    }

    @Test
    fun `toTransactionBundle - convenience alias produces TRANSACTION bundle`() {
        val result = OperationResult.of(Patient().apply { id = "p1" }, "patient")

        val bundle = result.toTransactionBundle()

        assertEquals(Bundle.BundleType.TRANSACTION, bundle.type)
        assertEquals(Bundle.HTTPVerb.PUT, bundle.entryFirstRep.request.method)
    }

    @Test
    fun `toBatchBundle - convenience alias produces BATCH bundle`() {
        val result = OperationResult.of(patient(), "patient")

        val bundle = result.toBatchBundle()

        assertEquals(Bundle.BundleType.BATCH, bundle.type)
        assertEquals(Bundle.HTTPVerb.POST, bundle.entryFirstRep.request.method)
    }

    // ── Type safety ───────────────────────────────────────────────────────────
    // These tests prove compile-time type enforcement: if the generic machinery
    // were broken the typed assignments below would fail to compile.

    @Test
    fun `of infers generic type - getResult requires no cast`() {
        val patient = patient()
        // Explicit type annotation proves of() returns OperationResult<Patient>
        val result: OperationResult<Patient> = OperationResult.of(patient)
        // If getResult() returned Base this line would not compile:
        val retrieved: Patient = result.getResult()
        assertThat(retrieved, sameInstance(patient))
    }

    @Test
    fun `add changes generic type - chained getResult returns new type without cast`() {
        val appt = appointment()
        // After .add { Appointment } the type becomes OperationResult<Appointment>
        val result: OperationResult<Appointment> = OperationResult.of(patient()).add { appt }
        val retrieved: Appointment = result.getResult()
        assertThat(retrieved, sameInstance(appt))
    }

    @Test
    fun `addUsing lambda parameter is typed to current pipeline type`() {
        var receivedAsPatient: Patient? = null
        // The explicit Patient annotation in the lambda verifies the compiler
        // resolves T = Patient from the OperationResult<Patient> receiver.
        val result: OperationResult<Coverage> = OperationResult.of(patient())
            .addUsing { p: Patient ->
                receivedAsPatient = p
                Coverage().apply { id = "cov-${p.idElement}" }
            }
        val coverage: Coverage = result.getResult()
        assertThat(receivedAsPatient, notNullValue())
        assertThat(coverage, instanceOf(Coverage::class.java))
    }

    @Test
    fun `addAll result is typed as List - getResultList returns typed list without cast`() {
        val appts = listOf(appointment(), appointment())
        val result = OperationResult.of(patient())
            .addAll("appts") { appts }

        // getResultList() extension only exists on OperationResult<List<R>>
        val retrieved: List<Appointment> = result.getResultList()
        assertEquals(2, retrieved.size)
        assertThat(retrieved, sameInstance(appts))
    }

    @Test
    fun `addAllUsing result is typed as List - getResultList returns typed list`() {
        val result = OperationResult.of(patient())
            .addAllUsing { p: Patient ->
                listOf(
                    Coverage().apply { id = "c1-${p.idElement}" },
                    Coverage().apply { id = "c2-${p.idElement}" }
                )
            }

        val coverages: List<Coverage> = result.getResultList()
        assertEquals(2, coverages.size)
    }

    @Test
    fun `getByType reified overload requires no KClass argument`() {
        val result = OperationResult.of(listOf(patient(), patient()), "people")
            .add("appt") { appointment() }

        // Reified call — no ::class argument needed
        val patients: List<Patient> = result.getByType<Patient>()
        val appts: List<Appointment> = result.getByType<Appointment>()
        assertThat(patients, hasSize(2))
        assertThat(appts, hasSize(1))
    }

    @Test
    fun `filterByType reified overload requires no KClass argument`() {
        val result = OperationResult.of(patient())
            .add { appointment() }
            .filterByType<Patient>()

        assertTrue(result.containsKey("patient"))
        assertFalse(result.containsKey("appointment"))
    }

    @Test
    fun `multi-step chain carries correct type at every stage`() {
        val patient = patient()
        // Each step is typed; assigning to a wrong type would cause a compile error.
        val step1: OperationResult<Patient> = OperationResult.of(patient)
        val step2: OperationResult<Appointment> = step1.add { appointment() }
        val step3: OperationResult<Coverage> = step2.addUsing { _: Appointment -> Coverage() }

        val coverage: Coverage = step3.getResult()
        assertThat(coverage, instanceOf(Coverage::class.java))
        // All earlier resources are still in the map
        assertTrue(step3.containsKey("patient"))
        assertTrue(step3.containsKey("appointment"))
    }

    @Test
    fun `OperationResult of Base is valid for backward compatibility`() {
        // Code that explicitly uses OperationResult<Base> must still compile and run.
        val result: OperationResult<Base> = OperationResult.of(patient() as Base)
        assertThat(result.getResult(), instanceOf(Patient::class.java))
    }

    // ── mapValues (type-safe) ─────────────────────────────────────────────────

    @Test
    fun `mapValues type-safe transforms every value and changes pipeline type`() {
        val result: OperationResult<Appointment> = OperationResult.of(patient(), "p")
            .mapValues { _ -> appointment() }

        assertThat(result.getByType<Appointment>(), hasSize(1))
        assertThat(result.getByType<Patient>(), empty())
    }

    @Test
    fun `mapValues transforms values across multiple keys`() {
        val result = OperationResult.of(patient(), "patient")
            .add("appt") { appointment() }
            .mapValues { _ -> Patient() }

        // Both entries should now be Patients
        assertThat(result.getByType<Patient>(), hasSize(2))
        assertThat(result.getByType<Appointment>(), empty())
    }

    // ── flatMap ───────────────────────────────────────────────────────────────

    @Test
    fun `flatMap merges inner pipeline parameter entries into outer map`() {
        val inner = OperationResult.of(appointment(), "inner-appt")

        val result = OperationResult.of(patient(), "outer-patient")
            .flatMap { _ -> inner }

        assertTrue(result.containsKey("outer-patient"))
        assertTrue(result.containsKey("inner-appt"))
    }

    @Test
    fun `flatMap result type and getResult reflect inner pipeline head`() {
        val appt = appointment()
        val result: OperationResult<Appointment> = OperationResult.of(patient())
            .flatMap { _ -> OperationResult.of(appt, "appt") }

        assertThat(result.getResult(), sameInstance(appt))
    }

    @Test
    fun `flatMap on key collision accumulates values under same key`() {
        val inner = OperationResult.of(patient(), "patient")

        val result = OperationResult.of(patient(), "patient")
            .flatMap { _ -> inner }

        assertEquals(2, result.count("patient"))
    }

    @Test
    fun `flatMap chains sequentially — all inner keys visible in each step`() {
        val result = OperationResult.of(patient(), "patient")
            .flatMap { _ ->
                OperationResult.of(appointment(), "appt")
                    .add("coverage") { Coverage() }
            }

        assertTrue(result.containsKey("patient"))
        assertTrue(result.containsKey("appt"))
        assertTrue(result.containsKey("coverage"))
    }

    @Test
    fun `flatMap skips transform when pipeline has errors in FAIL_FAST`() {
        val result = OperationResult.of(patient())
            .add { error("boom") }
            .flatMap { _ -> OperationResult.of(appointment(), "appt") }

        assertFalse(result.containsKey("appt"))
        assertTrue(result.hasErrors())
    }

    // ── merge ─────────────────────────────────────────────────────────────────

    @Test
    fun `merge combines two non-overlapping parameter maps`() {
        val a = OperationResult.of(patient(), "patient")
        val b = OperationResult.of(appointment(), "appt")

        val merged = a.merge(b)

        assertTrue(merged.containsKey("patient"))
        assertTrue(merged.containsKey("appt"))
    }

    @Test
    fun `merge accumulates values under the same key on collision`() {
        val a = OperationResult.of(patient(), "resource")
        val b = OperationResult.of(appointment(), "resource")

        val merged = a.merge(b)

        assertEquals(2, merged.count("resource"))
    }

    @Test
    fun `merge preserves outer result type and current result`() {
        val patient = patient()
        val a: OperationResult<Patient> = OperationResult.of(patient, "patient")
        val b = OperationResult.of(appointment(), "appt")

        val merged: OperationResult<Patient> = a.merge(b)

        assertThat(merged.getResult(), sameInstance(patient))
    }

    @Test
    fun `merge does not modify original OperationResult`() {
        val a = OperationResult.of(patient(), "patient")
        val b = OperationResult.of(appointment(), "appt")

        a.merge(b)

        assertFalse(a.containsKey("appt"))
    }

    // ── remove ────────────────────────────────────────────────────────────────

    @Test
    fun `remove eliminates the specified key`() {
        val result = OperationResult.of(patient(), "patient")
            .add("appt") { appointment() }
            .remove("appt")

        assertFalse(result.containsKey("appt"))
        assertTrue(result.containsKey("patient"))
    }

    @Test
    fun `remove nonexistent key is a no-op`() {
        val result = OperationResult.of(patient(), "patient")
            .remove("missing")

        assertTrue(result.containsKey("patient"))
        assertEquals(1, result.totalCount())
    }

    @Test
    fun `remove preserves the current result`() {
        val patient = patient()
        val result: OperationResult<Patient> = OperationResult.of(patient, "patient")
            .addOrSkip("appt") { appointment() }
            .remove("appt")

        assertThat(result.getResult(), sameInstance(patient))
    }

    // ── rename ────────────────────────────────────────────────────────────────

    @Test
    fun `rename moves values from old key to new key`() {
        val patient = patient()
        val result = OperationResult.of(patient, "old")
            .rename("old", "new")

        assertFalse(result.containsKey("old"))
        assertTrue(result.containsKey("new"))
        assertThat(result.getAll("new").first(), sameInstance(patient))
    }

    @Test
    fun `rename on nonexistent key is a no-op`() {
        val result = OperationResult.of(patient(), "patient")
            .rename("missing", "other")

        assertTrue(result.containsKey("patient"))
        assertFalse(result.containsKey("other"))
    }

    @Test
    fun `rename to an existing key accumulates values`() {
        val p1 = patient()
        val p2 = patient()
        val result = OperationResult.of(p1, "source")
            .add("target") { p2 }
            .rename("source", "target")

        assertFalse(result.containsKey("source"))
        assertEquals(2, result.count("target"))
    }

    // ── peek ─────────────────────────────────────────────────────────────────

    @Test
    fun `peek invokes block with current parameter snapshot`() {
        var capturedKeys: Set<String>? = null
        OperationResult.of(patient(), "patient")
            .add("appt") { appointment() }
            .peek { map -> capturedKeys = map.keys }

        assertThat(capturedKeys, containsInAnyOrder("patient", "appt"))
    }

    @Test
    fun `peek does not modify the parameter map`() {
        val base = OperationResult.of(patient(), "patient")
            .add("appt") { appointment() }

        val after = base.peek { map ->
            // Attempt to cast and mutate — this is a snapshot so it won't affect state,
            // but even if it were mutable this peek returns the same instance
            @Suppress("UNUSED_VARIABLE")
            val ignored = map
        }

        assertEquals(base.getAllParameters(), after.getAllParameters())
    }

    @Test
    fun `peek returns the same OperationResult instance`() {
        val before = OperationResult.of(patient(), "patient")
        val after = before.peek { }

        assertThat(after, sameInstance(before))
    }

    @Test
    fun `peek preserves result type and getResult`() {
        val patient = patient()
        val result: OperationResult<Patient> = OperationResult.of(patient, "patient")
            .peek { }

        assertThat(result.getResult(), sameInstance(patient))
    }

    // ── takeFirst / takeFirstTyped ────────────────────────────────────────────

    @Test
    fun `takeFirst returns first value for the given key`() {
        val p = patient()
        val result = OperationResult.of(p, "patient")

        assertThat(result.takeFirst("patient"), sameInstance(p))
    }

    @Test
    fun `takeFirst returns null for missing key`() {
        val result = OperationResult.of(patient(), "patient")

        assertEquals(null, result.takeFirst("missing"))
    }

    @Test
    fun `takeFirst returns first among multiple values`() {
        val first = patient()
        val result = OperationResult.of(first, "patient")
            .add("patient") { patient() }

        assertThat(result.takeFirst("patient"), sameInstance(first))
    }

    @Test
    fun `takeFirstTyped returns first value matching the given type`() {
        val appt = appointment()
        val result = OperationResult.of(patient(), "entry")
            .add("entry") { appt }

        val found: Appointment? = result.takeFirstTyped("entry", Appointment::class)
        assertThat(found, sameInstance(appt))
    }

    @Test
    fun `takeFirstTyped returns null when no value matches the given type`() {
        val result = OperationResult.of(patient(), "patient")

        assertEquals(null, result.takeFirstTyped("patient", Appointment::class))
    }

    @Test
    fun `takeFirstTyped reified overload requires no KClass argument`() {
        val p = patient()
        val result = OperationResult.of(p, "patient")

        val found: Patient? = result.takeFirstTyped<Patient>("patient")
        assertThat(found, sameInstance(p))
    }

    @Test
    fun `takeFirstTyped reified returns null for missing key`() {
        val result = OperationResult.of(patient(), "patient")

        assertEquals(null, result.takeFirstTyped<Appointment>("missing"))
    }

    // ── Primitive value convenience methods ──────────────────────────────────

    @Test
    fun `addString stores StringType under given name`() {
        val result = OperationResult.of(patient())
            .addString("label", "hello")

        assertTrue(result.containsKey("label"))
        val stored = result.getAll("label").first()
        assertThat(stored, instanceOf(StringType::class.java))
        assertEquals("hello", (stored as StringType).value)
    }

    @Test
    fun `addBoolean stores BooleanType under given name`() {
        val result = OperationResult.of(patient())
            .addBoolean("active", true)

        assertTrue(result.containsKey("active"))
        val stored = result.getAll("active").first()
        assertThat(stored, instanceOf(BooleanType::class.java))
        assertEquals(true, (stored as BooleanType).value)
    }

    @Test
    fun `addInteger stores IntegerType under given name`() {
        val result = OperationResult.of(patient())
            .addInteger("count", 42)

        assertTrue(result.containsKey("count"))
        val stored = result.getAll("count").first()
        assertThat(stored, instanceOf(IntegerType::class.java))
        assertEquals(42, (stored as IntegerType).value)
    }

    @Test
    fun `addDecimal stores DecimalType under given name`() {
        val result = OperationResult.of(patient())
            .addDecimal("score", BigDecimal("3.14"))

        assertTrue(result.containsKey("score"))
        val stored = result.getAll("score").first()
        assertThat(stored, instanceOf(DecimalType::class.java))
        assertEquals(BigDecimal("3.14"), (stored as DecimalType).value)
    }

    @Test
    fun `addCode stores CodeType under given name`() {
        val result = OperationResult.of(patient())
            .addCode("status", "active")

        assertTrue(result.containsKey("status"))
        val stored = result.getAll("status").first()
        assertThat(stored, instanceOf(CodeType::class.java))
        assertEquals("active", (stored as CodeType).value)
    }

    @Test
    fun `addUri stores UriType under given name`() {
        val result = OperationResult.of(patient())
            .addUri("profile", "http://hl7.org/fhir/StructureDefinition/Patient")

        assertTrue(result.containsKey("profile"))
        val stored = result.getAll("profile").first()
        assertThat(stored, instanceOf(UriType::class.java))
        assertEquals("http://hl7.org/fhir/StructureDefinition/Patient", (stored as UriType).value)
    }

    @Test
    fun `addDate stores DateType under given name`() {
        val result = OperationResult.of(patient())
            .addDate("dob", DateTimeInput.of("2024-01-15"))

        assertTrue(result.containsKey("dob"))
        val stored = result.getAll("dob").first()
        assertThat(stored, instanceOf(DateType::class.java))
        assertEquals("2024-01-15", (stored as DateType).valueAsString)
    }

    @Test
    fun `addDateTime stores DateTimeType under given name`() {
        val result = OperationResult.of(patient())
            .addDateTime("recorded", DateTimeInput.of("2024-01-15T10:30:00"))

        assertTrue(result.containsKey("recorded"))
        val stored = result.getAll("recorded").first()
        assertThat(stored, instanceOf(DateTimeType::class.java))
    }

    @Test
    fun `addDate with LocalDate stores DateType with correct ISO value`() {
        val result = OperationResult.of(patient())
            .addDate("dob", DateTimeInput.of(LocalDate.of(1990, 6, 15)))

        assertTrue(result.containsKey("dob"))
        val stored = result.getAll("dob").first() as DateType
        assertEquals("1990-06-15", stored.valueAsString)
    }

    @Test
    fun `addDate with YearMonth stores DateType with month precision`() {
        val result = OperationResult.of(patient())
            .addDate("period", DateTimeInput.of(YearMonth.of(2024, 3)))

        val stored = result.getAll("period").first() as DateType
        assertEquals("2024-03", stored.valueAsString)
    }

    @Test
    fun `addDate with Year stores DateType with year precision`() {
        val result = OperationResult.of(patient())
            .addDate("year", DateTimeInput.of(Year.of(2024)))

        val stored = result.getAll("year").first() as DateType
        assertEquals("2024", stored.valueAsString)
    }

    @Test
    fun `addDate with java util Date stores DateType`() {
        val result = OperationResult.of(patient())
            .addDate("dob", DateTimeInput.of(Date(0)))

        assertTrue(result.containsKey("dob"))
        assertThat(result.getAll("dob").first(), instanceOf(DateType::class.java))
    }

    @Test
    fun `addDateTime with LocalDateTime stores zone-less DateTimeType with seconds`() {
        val ldt = LocalDateTime.of(2024, 3, 15, 10, 30, 0)
        val result = OperationResult.of(patient())
            .addDateTime("recorded", DateTimeInput.of(ldt))

        val stored = result.getAll("recorded").first() as DateTimeType
        assertEquals("2024-03-15T10:30:00", stored.valueAsString)
    }

    @Test
    fun `addDateTime with ZonedDateTime stores offset-aware DateTimeType`() {
        val zdt = ZonedDateTime.of(2024, 3, 15, 10, 30, 15, 0, ZoneOffset.UTC)
        val result = OperationResult.of(patient())
            .addDateTime("recorded", DateTimeInput.of(zdt))

        val stored = result.getAll("recorded").first() as DateTimeType
        assertTrue(stored.valueAsString.contains("+00:00") || stored.valueAsString.endsWith("Z"),
            "Should contain UTC offset: ${stored.valueAsString}")
    }

    @Test
    fun `addDateTime with OffsetDateTime stores correct DateTimeType`() {
        val odt = OffsetDateTime.of(2024, 3, 15, 10, 30, 30, 0, ZoneOffset.ofHours(2))
        val result = OperationResult.of(patient())
            .addDateTime("recorded", DateTimeInput.of(odt))

        val stored = result.getAll("recorded").first() as DateTimeType
        assertTrue(stored.valueAsString.contains("+02:00"),
            "Should contain +02:00 offset: ${stored.valueAsString}")
    }

    @Test
    fun `addDateTime with java util Date stores DateTimeType`() {
        val result = OperationResult.of(patient())
            .addDateTime("recorded", DateTimeInput.of(Date()))

        assertThat(result.getAll("recorded").first(), instanceOf(DateTimeType::class.java))
    }

    @Test
    fun `addDateTime with Calendar stores DateTimeType`() {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        val result = OperationResult.of(patient())
            .addDateTime("recorded", DateTimeInput.of(cal))

        assertThat(result.getAll("recorded").first(), instanceOf(DateTimeType::class.java))
    }

    @Test
    fun `addInstant with String stores InstantType`() {
        val result = OperationResult.of(patient())
            .addInstant("ts", DateTimeInput.of("2024-03-15T10:30:00.000Z"))

        assertTrue(result.containsKey("ts"))
        assertThat(result.getAll("ts").first(), instanceOf(InstantType::class.java))
    }

    @Test
    fun `addInstant with java time Instant stores InstantType`() {
        val instant = Instant.parse("2024-03-15T10:30:00.000Z")
        val result = OperationResult.of(patient())
            .addInstant("ts", DateTimeInput.of(instant))

        assertThat(result.getAll("ts").first(), instanceOf(InstantType::class.java))
    }

    @Test
    fun `addInstant with ZonedDateTime stores InstantType`() {
        val zdt = ZonedDateTime.of(2024, 3, 15, 10, 30, 0, 0, ZoneOffset.UTC)
        val result = OperationResult.of(patient())
            .addInstant("ts", DateTimeInput.of(zdt))

        assertThat(result.getAll("ts").first(), instanceOf(InstantType::class.java))
    }

    @Test
    fun `addInstant with OffsetDateTime stores InstantType`() {
        val odt = OffsetDateTime.of(2024, 3, 15, 10, 30, 0, 0, ZoneOffset.UTC)
        val result = OperationResult.of(patient())
            .addInstant("ts", DateTimeInput.of(odt))

        assertThat(result.getAll("ts").first(), instanceOf(InstantType::class.java))
    }

    @Test
    fun `addInstant with java util Date stores InstantType`() {
        val result = OperationResult.of(patient())
            .addInstant("ts", DateTimeInput.of(Date()))

        assertThat(result.getAll("ts").first(), instanceOf(InstantType::class.java))
    }

    @Test
    fun `addTime with String stores TimeType`() {
        val result = OperationResult.of(patient())
            .addTime("appt", DateTimeInput.of("10:30:00"))

        assertTrue(result.containsKey("appt"))
        val stored = result.getAll("appt").first() as TimeType
        assertEquals("10:30:00", stored.valueAsString)
    }

    @Test
    fun `addTime with LocalTime stores TimeType`() {
        val result = OperationResult.of(patient())
            .addTime("appt", DateTimeInput.of(LocalTime.of(14, 45, 30)))

        val stored = result.getAll("appt").first() as TimeType
        assertEquals("14:45:30", stored.valueAsString)
    }

    @Test
    fun `addInstantUsing with DateTimeInput builder stores InstantType`() {
        val result = OperationResult.of(patient())
            .addInstantUsing("ts") { DateTimeInput.of("2024-03-15T10:30:00.000Z") }

        assertThat(result.getAll("ts").first(), instanceOf(InstantType::class.java))
    }

    @Test
    fun `addTimeUsing with DateTimeInput builder stores TimeType`() {
        val result = OperationResult.of(patient())
            .addTimeUsing("appt") { DateTimeInput.of("09:00:00") }

        val stored = result.getAll("appt").first() as TimeType
        assertEquals("09:00:00", stored.valueAsString)
    }

    @Test
    fun `addDateUsing with LocalDate builder stores DateType with correct value`() {
        val result = OperationResult.of(patient())
            .addDateUsing("dob") { DateTimeInput.of(LocalDate.of(1990, 6, 15)) }

        val stored = result.getAll("dob").first() as DateType
        assertEquals("1990-06-15", stored.valueAsString)
    }

    @Test
    fun `addDateUsing with YearMonth builder stores DateType with month precision`() {
        val result = OperationResult.of(patient())
            .addDateUsing("period") { DateTimeInput.of(YearMonth.of(2024, 3)) }

        val stored = result.getAll("period").first() as DateType
        assertEquals("2024-03", stored.valueAsString)
    }

    @Test
    fun `addDateUsing with Year builder stores DateType with year precision`() {
        val result = OperationResult.of(patient())
            .addDateUsing("year") { DateTimeInput.of(Year.of(2024)) }

        val stored = result.getAll("year").first() as DateType
        assertEquals("2024", stored.valueAsString)
    }

    @Test
    fun `addDateTimeUsing with LocalDateTime builder stores zone-less DateTimeType`() {
        val result = OperationResult.of(patient())
            .addDateTimeUsing("recorded") { DateTimeInput.of(LocalDateTime.of(2024, 3, 15, 10, 30, 0)) }

        val stored = result.getAll("recorded").first() as DateTimeType
        assertEquals("2024-03-15T10:30:00", stored.valueAsString)
    }

    @Test
    fun `addDateTimeUsing with ZonedDateTime builder stores offset-aware DateTimeType`() {
        val result = OperationResult.of(patient())
            .addDateTimeUsing("recorded") { DateTimeInput.of(ZonedDateTime.of(2024, 3, 15, 10, 30, 15, 0, ZoneOffset.UTC)) }

        assertThat(result.getAll("recorded").first(), instanceOf(DateTimeType::class.java))
    }

    @Test
    fun `addDateTimeUsing with OffsetDateTime builder stores offset-aware DateTimeType`() {
        val result = OperationResult.of(patient())
            .addDateTimeUsing("recorded") { DateTimeInput.of(OffsetDateTime.of(2024, 3, 15, 10, 30, 30, 0, ZoneOffset.ofHours(2))) }

        val stored = result.getAll("recorded").first() as DateTimeType
        assertTrue(stored.valueAsString.contains("+02:00"))
    }

    @Test
    fun `addInstantUsing with Instant builder stores InstantType`() {
        val result = OperationResult.of(patient())
            .addInstantUsing("ts") { DateTimeInput.of(Instant.parse("2024-03-15T10:30:00.000Z")) }

        assertThat(result.getAll("ts").first(), instanceOf(InstantType::class.java))
    }

    @Test
    fun `addInstantUsing with ZonedDateTime builder stores InstantType`() {
        val result = OperationResult.of(patient())
            .addInstantUsing("ts") { DateTimeInput.of(ZonedDateTime.of(2024, 3, 15, 10, 30, 0, 0, ZoneOffset.UTC)) }

        assertThat(result.getAll("ts").first(), instanceOf(InstantType::class.java))
    }

    @Test
    fun `addInstantUsing with OffsetDateTime builder stores InstantType`() {
        val result = OperationResult.of(patient())
            .addInstantUsing("ts") { DateTimeInput.of(OffsetDateTime.of(2024, 3, 15, 10, 30, 0, 0, ZoneOffset.UTC)) }

        assertThat(result.getAll("ts").first(), instanceOf(InstantType::class.java))
    }

    @Test
    fun `addTimeUsing with LocalTime builder stores TimeType`() {
        val result = OperationResult.of(patient())
            .addTimeUsing("appt") { DateTimeInput.of(LocalTime.of(9, 0)) }

        val stored = result.getAll("appt").first() as TimeType
        assertEquals("09:00", stored.valueAsString)
    }

    @Test
    fun `addPeriod with ZonedDateTime start and end stores Period with correct offsets`() {
        val start = ZonedDateTime.of(2024, 1, 1, 8, 0, 0, 0, ZoneOffset.UTC)
        val end = ZonedDateTime.of(2024, 12, 31, 23, 59, 59, 0, ZoneOffset.UTC)
        val result = OperationResult.of(patient())
            .addPeriod("coverage", start, end)

        assertTrue(result.containsKey("coverage"))
        val period = result.getAll("coverage").first() as Period
        assertTrue(period.hasStart())
        assertTrue(period.hasEnd())
    }

    @Test
    fun `addPeriod with OffsetDateTime stores Period`() {
        val start = OffsetDateTime.of(2024, 1, 1, 8, 0, 0, 0, ZoneOffset.UTC)
        val end = OffsetDateTime.of(2024, 12, 31, 23, 59, 59, 0, ZoneOffset.UTC)
        val result = OperationResult.of(patient())
            .addPeriod("coverage", start, end)

        val period = result.getAll("coverage").first() as Period
        assertTrue(period.hasStart() && period.hasEnd())
    }

    @Test
    fun `addPeriod with LocalDateTime stores Period`() {
        val start = LocalDateTime.of(2024, 1, 1, 8, 0, 0)
        val end = LocalDateTime.of(2024, 12, 31, 23, 59, 59)
        val result = OperationResult.of(patient())
            .addPeriod("coverage", start, end)

        val period = result.getAll("coverage").first() as Period
        assertTrue(period.hasStart() && period.hasEnd())
    }

    @Test
    fun `addDate with LocalDate preserves pipeline head type`() {
        val patient = patient()
        val result: OperationResult<Patient> = OperationResult.of(patient)
            .addDate("dob", DateTimeInput.of(LocalDate.of(1990, 1, 1)))

        assertThat(result.getResult(), sameInstance(patient))
    }

    @Test
    fun `addInstant with Instant preserves pipeline head type`() {
        val patient = patient()
        val result: OperationResult<Patient> = OperationResult.of(patient)
            .addInstant("ts", DateTimeInput.of(Instant.now()))

        assertThat(result.getResult(), sameInstance(patient))
    }

    @Test
    fun `addCanonical stores CanonicalType under given name`() {
        val result = OperationResult.of(patient())
            .addCanonical("questionnaire", "http://example.org/Questionnaire/q1")

        assertTrue(result.containsKey("questionnaire"))
        val stored = result.getAll("questionnaire").first()
        assertThat(stored, instanceOf(CanonicalType::class.java))
        assertEquals("http://example.org/Questionnaire/q1", (stored as CanonicalType).value)
    }

    @Test
    fun `addString preserves pipeline head type T`() {
        val patient = patient()
        val result: OperationResult<Patient> = OperationResult.of(patient)
            .addString("label", "test")

        assertThat(result.getResult(), sameInstance(patient))
    }

    @Test
    fun `addString accumulates alongside existing parameters`() {
        val result = OperationResult.of(patient())
            .addString("label", "test")

        assertTrue(result.containsKey("patient"))
        assertTrue(result.containsKey("label"))
    }

    @Test
    fun `addStringUsing receives current result`() {
        val result = OperationResult.of(Patient().apply { id = "p1" })
            .addStringUsing("id") { p -> p.idElement.idPart }

        assertTrue(result.containsKey("id"))
        val stored = result.getAll("id").first()
        assertEquals("p1", (stored as StringType).value)
    }

    @Test
    fun `addString is skipped when pipeline has errors in FAIL_FAST`() {
        val result = OperationResult.of(patient())
            .add { error("boom") }
            .addString("label", "test")

        assertFalse(result.containsKey("label"))
    }

    @Test
    fun `addStringUsing - exception in lambda records WARNING outcome and preserves head`() {
        val patient = patient()
        val result = OperationResult.of(patient)
            .addStringUsing("label") { error("bad label") }

        assertTrue(result.hasErrors())
        assertEquals(OperationOutcome.IssueSeverity.WARNING, result.getOutcomes().first().issueFirstRep.severity)
        assertFalse(result.containsKey("label"))
        assertThat(result.getResult(), sameInstance(patient))
    }

    @Test
    fun `addString - pipeline continues in ACCUMULATE after exception and head is preserved`() {
        val patient = patient()
        val result = OperationResult.of(patient, errorStrategy = ErrorStrategy.ACCUMULATE)
            .addStringUsing("label") { error("boom") }
            .addString("other", "ok")

        assertTrue(result.hasErrors())
        assertTrue(result.containsKey("other"))
        assertThat(result.getResult(), sameInstance(patient))
    }

    @Test
    fun `primitive values round-trip through toParameters and fromParameters`() {
        val original = OperationResult.of(patient())
            .addString("label", "hello")
            .addBoolean("active", true)

        val params = original.toParameters()
        val restored = OperationResult.fromParameters(params)

        val label = restored.getAll("label").first()
        assertThat(label, instanceOf(StringType::class.java))
        assertEquals("hello", (label as StringType).value)

        val active = restored.getAll("active").first()
        assertThat(active, instanceOf(BooleanType::class.java))
        assertEquals(true, (active as BooleanType).value)
    }

    // ── Complex data type convenience methods ────────────────────────────────

    @Test
    fun `addCoding stores Coding with system, code, and display`() {
        val result = OperationResult.of(patient())
            .addCoding("status", "http://loinc.org", "8867-4", "Heart rate")

        assertTrue(result.containsKey("status"))
        val stored = result.getAll("status").first() as Coding
        assertEquals("http://loinc.org", stored.system)
        assertEquals("8867-4", stored.code)
        assertEquals("Heart rate", stored.display)
    }

    @Test
    fun `addCoding without display omits display`() {
        val result = OperationResult.of(patient())
            .addCoding("status", "http://loinc.org", "8867-4")

        val stored = result.getAll("status").first() as Coding
        assertEquals("http://loinc.org", stored.system)
        assertEquals("8867-4", stored.code)
        assertEquals(null, if (stored.hasDisplay()) stored.display else null)
    }

    @Test
    fun `addReference stores Reference with reference string`() {
        val result = OperationResult.of(patient())
            .addReference("subject", "Patient/123")

        assertTrue(result.containsKey("subject"))
        val stored = result.getAll("subject").first() as Reference
        assertEquals("Patient/123", stored.reference)
    }

    @Test
    fun `addIdentifier stores Identifier with system and value`() {
        val result = OperationResult.of(patient())
            .addIdentifier("mrn", "http://hospital.org/mrn", "MRN-001")

        assertTrue(result.containsKey("mrn"))
        val stored = result.getAll("mrn").first() as Identifier
        assertEquals("http://hospital.org/mrn", stored.system)
        assertEquals("MRN-001", stored.value)
    }

    @Test
    fun `addPeriod stores Period with start and end`() {
        val result = OperationResult.of(patient())
            .addPeriod("window", "2024-01-01", "2024-12-31")

        assertTrue(result.containsKey("window"))
        val stored = result.getAll("window").first() as Period
        assertEquals("2024-01-01", stored.startElement.valueAsString)
        assertEquals("2024-12-31", stored.endElement.valueAsString)
    }

    @Test
    fun `addPeriod with null start stores Period with only end`() {
        val result = OperationResult.of(patient())
            .addPeriod("window", null, "2024-12-31")

        val stored = result.getAll("window").first() as Period
        assertFalse(stored.hasStart())
        assertEquals("2024-12-31", stored.endElement.valueAsString)
    }

    @Test
    fun `addQuantity stores Quantity with all fields`() {
        val result = OperationResult.of(patient())
            .addQuantity("weight", BigDecimal("70.5"), "kg", "http://unitsofmeasure.org", "kg")

        assertTrue(result.containsKey("weight"))
        val stored = result.getAll("weight").first() as Quantity
        assertEquals(BigDecimal("70.5"), stored.value)
        assertEquals("kg", stored.unit)
        assertEquals("http://unitsofmeasure.org", stored.system)
        assertEquals("kg", stored.code)
    }

    @Test
    fun `addQuantity without system and code omits them`() {
        val result = OperationResult.of(patient())
            .addQuantity("weight", BigDecimal("70.5"), "kg")

        val stored = result.getAll("weight").first() as Quantity
        assertEquals(BigDecimal("70.5"), stored.value)
        assertEquals("kg", stored.unit)
        assertEquals(null, if (stored.hasSystem()) stored.system else null)
        assertEquals(null, if (stored.hasCode()) stored.code else null)
    }

    @Test
    fun `addCodeableConcept stores CodeableConcept with coding and text`() {
        val result = OperationResult.of(patient())
            .addCodeableConcept("category", "http://snomed.info/sct", "413839001", "Chronic lung disease", "Chronic lung disease (disorder)")

        assertTrue(result.containsKey("category"))
        val stored = result.getAll("category").first() as CodeableConcept
        val coding = stored.codingFirstRep
        assertEquals("http://snomed.info/sct", coding.system)
        assertEquals("413839001", coding.code)
        assertEquals("Chronic lung disease", coding.display)
        assertEquals("Chronic lung disease (disorder)", stored.text)
    }

    @Test
    fun `addCoding preserves pipeline head type T`() {
        val patient = patient()
        val result: OperationResult<Patient> = OperationResult.of(patient)
            .addCoding("status", "http://loinc.org", "8867-4", "Heart rate")

        assertThat(result.getResult(), sameInstance(patient))
    }

    @Test
    fun `complex types round-trip through toParameters and fromParameters`() {
        val original = OperationResult.of(patient())
            .addCoding("obs-code", "http://loinc.org", "8867-4", "Heart rate")

        val params = original.toParameters()
        val restored = OperationResult.fromParameters(params)

        val stored = restored.getAll("obs-code").first()
        assertThat(stored, instanceOf(Coding::class.java))
        assertEquals("http://loinc.org", (stored as Coding).system)
        assertEquals("8867-4", stored.code)
    }

    // ── addPart (nested parameter construction) ──────────────────────────────

    @Test
    fun `addPart creates dot-prefixed keys in parameter map`() {
        val result = OperationResult.of(patient())
            .addPart("address") { addString("city", "Springfield").addString("country", "US") }

        assertTrue(result.containsKey("address.city"))
        assertTrue(result.containsKey("address.country"))
        assertEquals("Springfield", (result.getAll("address.city").first() as StringType).value)
        assertEquals("US", (result.getAll("address.country").first() as StringType).value)
    }

    @Test
    fun `addPart produces nested parts in toParameters output`() {
        val result = OperationResult.of(patient())
            .addPart("address") { addString("city", "Springfield").addString("country", "US") }

        val params = result.toParameters()

        val addressParam = params.parameter.filter { it.name == "address" }
        assertEquals(1, addressParam.size, "Expected exactly one 'address' parameter")
        assertFalse(params.parameter.any { it.name == "address.city" }, "Flat dot-key should not appear at top level")

        val parts = addressParam.first().part
        assertEquals(2, parts.size)
        val cityPart = parts.first { it.name == "city" }
        val countryPart = parts.first { it.name == "country" }
        assertEquals("Springfield", (cityPart.value as StringType).value)
        assertEquals("US", (countryPart.value as StringType).value)
    }

    @Test
    fun `addPart preserves pipeline head type T`() {
        val result: OperationResult<Patient> = OperationResult.of(patient())
            .addPart("address") { addString("city", "Springfield") }

        assertThat(result.getResult(), instanceOf(Patient::class.java))
    }

    @Test
    fun `addPart with deeply nested sub-parts`() {
        val result = OperationResult.of(patient())
            .addPart("outer") { addPart("inner") { addString("leaf", "deep") } }

        assertTrue(result.containsKey("outer.inner.leaf"))
        assertEquals("deep", (result.getAll("outer.inner.leaf").first() as StringType).value)

        val params = result.toParameters()
        val outerParam = params.parameter.first { it.name == "outer" }
        val innerPart = outerParam.part.first { it.name == "inner" }
        val leafPart = innerPart.part.first { it.name == "leaf" }
        assertEquals("deep", (leafPart.value as StringType).value)
    }

    @Test
    fun `addPart round-trips through fromParameters toParameters`() {
        val original = OperationResult.of(patient())
            .addPart("address") { addString("city", "Springfield").addString("country", "US") }

        val params1 = original.toParameters()
        val restored = OperationResult.fromParameters(params1)
        val params2 = restored.toParameters()

        val addressParam1 = params1.parameter.first { it.name == "address" }
        val addressParam2 = params2.parameter.first { it.name == "address" }
        assertEquals(addressParam1.part.size, addressParam2.part.size)
        assertEquals(
            addressParam1.part.first { it.name == "city" }.value.primitiveValue(),
            addressParam2.part.first { it.name == "city" }.value.primitiveValue()
        )
        assertEquals(
            addressParam1.part.first { it.name == "country" }.value.primitiveValue(),
            addressParam2.part.first { it.name == "country" }.value.primitiveValue()
        )
    }

    @Test
    fun `addPart is skipped when pipeline has errors in FAIL_FAST`() {
        val result = OperationResult.of(patient(), errorStrategy = ErrorStrategy.FAIL_FAST)
            .add { throw RuntimeException("induced failure") }
            .addPart("address") { addString("city", "Springfield") }

        assertFalse(result.containsKey("address.city"))
    }

    @Test
    fun `addPart alongside flat parameters`() {
        val p = patient()
        val result = OperationResult.of(p)
            .addPart("meta") { addString("tag", "test") }

        assertTrue(result.containsKey("patient"))
        assertTrue(result.containsKey("meta.tag"))

        val params = result.toParameters()
        assertTrue(params.parameter.any { it.name == "patient" })
        val metaParam = params.parameter.first { it.name == "meta" }
        assertEquals(1, metaParam.part.size)
        assertEquals("test", (metaParam.part.first().value as StringType).value)
    }

    // ── Factory: empty() ─────────────────────────────────────────────────────

    @Test
    fun `empty creates result with no parameters`() {
        val result = OperationResult.empty()

        assertTrue(result.isEmpty())
        assertEquals(0, result.totalCount())
    }

    @Test
    fun `empty result is successful`() {
        val result = OperationResult.empty()

        assertTrue(result.isSuccessful())
        assertFalse(result.hasErrors())
    }

    @Test
    fun `empty toParameters returns valid empty Parameters`() {
        val params = OperationResult.empty().toParameters()

        assertTrue(params.parameter.isEmpty())
    }

    @Test
    fun `empty getResult throws IllegalStateException`() {
        val result = OperationResult.empty()

        assertThrows(IllegalStateException::class.java) { result.getResult() }
    }

    @Test
    fun `empty allows subsequent add calls`() {
        val result = OperationResult.empty()
            .add("patient") { patient() }

        assertTrue(result.containsKey("patient"))
    }

    @Test
    fun `empty with ACCUMULATE error strategy`() {
        val result = OperationResult.empty(ErrorStrategy.ACCUMULATE)
            .add { throw RuntimeException("induced failure") }
            .add("patient") { patient() }

        assertTrue(result.containsKey("patient"))
        assertTrue(result.hasErrors())
    }

    @Test
    fun `empty can be used as merge target`() {
        val result = OperationResult.empty()
            .merge(OperationResult.of(patient()))

        assertTrue(result.containsKey("patient"))
    }

    @Test
    fun `empty toTransactionBundle returns empty bundle`() {
        val bundle = OperationResult.empty().toTransactionBundle()

        assertTrue(bundle.entry.isEmpty())
    }

    @Test
    fun `empty with timed returns metrics`() {
        val result = OperationResult.empty()
            .timed()
            .add("p") { patient() }

        assertEquals(1, result.getMetrics().size)
    }

    // ── Extension support ──────────────────────────────────────────────

    @Test
    fun `addWithExtension stores value with extension`() {
        val value = StringType("hello")
        val ext = Extension("http://example.com/ext", StringType("extValue"))

        val result = OperationResult.of(patient())
            .addWithExtension("greeting", value, ext)

        assertTrue(result.containsKey("greeting"))
        assertEquals(1, result.count("greeting"))
        assertThat(result.getAll("greeting").first(), sameInstance(value))
    }

    @Test
    fun `addWithExtension extensions appear in toParameters output`() {
        val value = StringType("hello")
        val ext = Extension("http://example.com/ext", StringType("extValue"))

        val params = OperationResult.of(patient())
            .addWithExtension("greeting", value, ext)
            .toParameters()

        val param = params.parameter.first { it.name == "greeting" }
        assertEquals(1, param.extension.size)
        assertEquals("http://example.com/ext", param.extension.first().url)
        assertEquals("extValue", (param.extension.first().value as StringType).value)
    }

    @Test
    fun `addWithExtension with multiple extensions`() {
        val value = StringType("hello")
        val ext1 = Extension("http://example.com/ext1", StringType("v1"))
        val ext2 = Extension("http://example.com/ext2", StringType("v2"))

        val params = OperationResult.of(patient())
            .addWithExtension("greeting", value, ext1, ext2)
            .toParameters()

        val param = params.parameter.first { it.name == "greeting" }
        assertEquals(2, param.extension.size)
        assertEquals("http://example.com/ext1", param.extension[0].url)
        assertEquals("http://example.com/ext2", param.extension[1].url)
    }

    @Test
    fun `addWithExtension preserves pipeline head type T`() {
        val patient = patient()
        val result: OperationResult<Patient> = OperationResult.of(patient)
            .addWithExtension("greeting", StringType("hello"), Extension("http://example.com/ext", StringType("v")))

        assertThat(result.getResult(), sameInstance(patient))
    }

    @Test
    fun `addWithExtension without extensions behaves like addOrSkip`() {
        val value = StringType("hello")

        val params = OperationResult.of(patient())
            .addWithExtension("greeting", value)
            .toParameters()

        val param = params.parameter.first { it.name == "greeting" }
        assertTrue(param.extension.isEmpty())
        assertEquals("hello", (param.value as StringType).value)
    }

    @Test
    fun `extensions survive full round-trip via fromParameters`() {
        val value = StringType("hello")
        val ext = Extension("http://example.com/ext", StringType("extValue"))

        val params1 = OperationResult.of(patient())
            .addWithExtension("greeting", value, ext)
            .toParameters()

        val params2 = OperationResult.fromParameters(params1).toParameters()

        val param = params2.parameter.first { it.name == "greeting" }
        assertEquals(1, param.extension.size)
        assertEquals("http://example.com/ext", param.extension.first().url)
        assertEquals("extValue", (param.extension.first().value as StringType).value)
    }

    @Test
    fun `fromParameters preserves existing extensions and addWithExtension adds new ones`() {
        // Build an initial Parameters with one extension on "greeting"
        val original = Extension("http://example.com/original", StringType("orig"))
        val params1 = OperationResult.of(patient())
            .addWithExtension("greeting", StringType("hello"), original)
            .toParameters()

        // Round-trip through fromParameters, then attach an additional extension on a new key
        val added = Extension("http://example.com/added", StringType("new"))
        val params2 = OperationResult.fromParameters(params1)
            .addWithExtension("status", StringType("active"), added)
            .toParameters()

        // Original extension on "greeting" is preserved
        val greetingParam = params2.parameter.first { it.name == "greeting" }
        assertEquals(1, greetingParam.extension.size)
        assertEquals("http://example.com/original", greetingParam.extension.first().url)
        assertEquals("orig", (greetingParam.extension.first().value as StringType).value)

        // Newly added extension on "status" is present
        val statusParam = params2.parameter.first { it.name == "status" }
        assertEquals(1, statusParam.extension.size)
        assertEquals("http://example.com/added", statusParam.extension.first().url)
        assertEquals("new", (statusParam.extension.first().value as StringType).value)
    }

    // ── addFromFiltered / addAllFromFiltered ──────────────────────────────────

    @Test
    fun `addFromFiltered stores result under output fhirType when name is null`() {
        val enrolled = patient().apply { addExtension("http://example.org/enrolled", StringType("true")) }
        val result = OperationResult.of(listOf(enrolled, patient()), "patients")
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasAllExtensions("http://example.org/enrolled")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertTrue(result.containsKey("operationoutcome"))
        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromFiltered with explicit name stores under that name`() {
        val result = OperationResult.of(listOf(patient()), "patients")
            .addFromFiltered("my-key", Patient::class, { true }) { patients ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${patients.size}" }
            }

        assertTrue(result.containsKey("my-key"))
    }

    @Test
    fun `addFromFiltered reified overload works without explicit KClass`() {
        val enrolled = patient().apply { addExtension("http://example.org/enrolled", StringType("true")) }
        val result = OperationResult.of(listOf(enrolled, patient()), "patients")
            .addFromFiltered<Patient, OperationOutcome>(predicate = FhirFilter.hasAllExtensions("http://example.org/enrolled")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromFiltered with no matching resources passes empty list to builder`() {
        val result = OperationResult.of(listOf(patient()), "patients")
            .addFromFiltered(type = Patient::class, predicate = { false }) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertFalse(result.hasErrors())
        assertEquals("count=0", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromFiltered builder exception records ERROR outcome (Pattern A)`() {
        val result = OperationResult.of(listOf(patient()), "patients")
            .addFromFiltered(type = Patient::class, predicate = { true }) { _ -> throw RuntimeException("boom") }

        assertTrue(result.hasErrors())
        assertEquals(
            OperationOutcome.IssueSeverity.ERROR,
            result.getOutcomes().first().issueFirstRep.severity
        )
    }

    @Test
    fun `addFromFiltered stores result under output type not input type`() {
        val result = OperationResult.of(listOf(patient()), "patients")
            .addFromFiltered(type = Patient::class, predicate = { true }) { _ -> OperationOutcome() }

        assertTrue(result.containsKey("operationoutcome"))
        assertFalse(result.containsKey("patient"))
    }

    @Test
    fun `addAllFromFiltered returns list head and stores under output fhirType`() {
        val p1 = patient()
        val p2 = patient()
        val result = OperationResult.of(listOf(p1, p2, appointment()), "items")
            .addAllFromFiltered(type = Patient::class, predicate = { true }) { filtered -> filtered }

        assertThat(result.getResultList(), hasSize(2))
        assertTrue(result.containsKey("patient"))
    }

    @Test
    fun `addAllFromFiltered with explicit name groups all items under that name`() {
        val result = OperationResult.of(listOf(patient(), appointment()), "items")
            .addAllFromFiltered("mixed", Base::class, { true }) { items -> items }

        assertEquals(2, result.count("mixed"))
    }

    @Test
    fun `addFromFiltered predicate filters correctly across multiple accumulated keys`() {
        val enrolled = patient().apply { addExtension("http://example.org/enrolled", StringType("true")) }
        val result = OperationResult.of(enrolled, "p1")
            .add { patient() }
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasAllExtensions("http://example.org/enrolled")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromFiltered Class overload works identically to KClass overload`() {
        val enrolled = patient().apply { addExtension("http://example.org/enrolled", StringType("true")) }
        val result = OperationResult.of(listOf(enrolled, patient()), "patients")
            .addFromFiltered(type = Patient::class.java, predicate = FhirFilter.hasAllExtensions("http://example.org/enrolled")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertFalse(result.hasErrors())
        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addAllFromFiltered Class overload works identically to KClass overload`() {
        val p1 = patient()
        val p2 = patient()
        val result = OperationResult.of(listOf(p1, p2, appointment()), "items")
            .addAllFromFiltered(type = Patient::class.java, predicate = { true }) { it }

        assertThat(result.getResultList(), hasSize(2))
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun patient() = Patient().apply {
        addName().apply {
            family = "Doe"
            addGiven("John")
        }
    }

    private fun appointment() = Appointment().apply {
        status = Appointment.AppointmentStatus.BOOKED
    }
}
