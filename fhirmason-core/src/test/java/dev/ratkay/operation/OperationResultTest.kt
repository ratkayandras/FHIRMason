package dev.ratkay.operation

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.hl7.fhir.r4.model.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import java.math.BigDecimal

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
            .addDate("dob", "2024-01-15")

        assertTrue(result.containsKey("dob"))
        val stored = result.getAll("dob").first()
        assertThat(stored, instanceOf(DateType::class.java))
        assertEquals("2024-01-15", (stored as DateType).valueAsString)
    }

    @Test
    fun `addDateTime stores DateTimeType under given name`() {
        val result = OperationResult.of(patient())
            .addDateTime("recorded", "2024-01-15T10:30:00")

        assertTrue(result.containsKey("recorded"))
        val stored = result.getAll("recorded").first()
        assertThat(stored, instanceOf(DateTimeType::class.java))
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
