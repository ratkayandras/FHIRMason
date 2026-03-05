package dev.ratkay.operation

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.hl7.fhir.r4.model.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals

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
        assertThat(result.getByType(OperationOutcome::class), hasSize(1))
    }

    @Test
    fun `addFrom returns empty list when key does not exist`() {
        val result = OperationResult.of(patient())
            .addFrom("missing", Patient::class) { patients ->
                Appointment().apply { addParticipant().actor = Reference().apply { display = "count=${patients.size}" } }
            }

        val appt = result.getByType(Appointment::class).first()
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
        assertThat(result.getByType(OperationOutcome::class), hasSize(1))
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

        assertThat(result.getByType(Patient::class), hasSize(2))
        assertThat(result.getByType(Appointment::class), hasSize(1))
        assertThat(result.getByType(OperationOutcome::class), empty())
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
        assertThat(result.getResult(), instanceOf(Patient::class.java))
    }

    // ── Functional transformations ────────────────────────────────────────────

    @Test
    fun `filterByType keeps only entries whose values match the given type`() {
        val result = OperationResult.of(patient())
            .add { appointment() }
            .filterByType(Patient::class)

        assertTrue(result.containsKey("patient"))
        assertFalse(result.containsKey("appointment"))
    }

    @Test
    fun `filterByType removes key entirely when no values match`() {
        val result = OperationResult.of(patient())
            .filterByType(Appointment::class)

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

        assertThat(result.getByType(Appointment::class), hasSize(1))
        assertThat(result.getByType(Patient::class), empty())
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
