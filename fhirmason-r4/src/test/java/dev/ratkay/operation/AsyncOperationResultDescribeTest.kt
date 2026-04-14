package dev.ratkay.operation

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.`is`
import org.hamcrest.Matchers.not
import org.hl7.fhir.r4.model.Appointment
import org.hl7.fhir.r4.model.Coverage
import org.hl7.fhir.r4.model.Patient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AsyncOperationResultDescribeTest {

    // ── Empty DAG ─────────────────────────────────────────────────────────────

    @Test
    fun `describe returns empty sentinel when no tasks registered`() {
        val output = AsyncOperationResult().describe()
        assertThat(output, `is`("AsyncOperationResult DAG: (empty)"))
    }

    // ── Header ────────────────────────────────────────────────────────────────

    @Test
    fun `describe always starts with AsyncOperationResult DAG header`() {
        val output = AsyncOperationResult()
            .add("patient") { Patient() }
            .describe()
        assertThat(output, containsString("AsyncOperationResult DAG:"))
    }

    // ── Single independent task ───────────────────────────────────────────────

    @Test
    fun `describe shows single independent task in Tier 1 without depends-on clause`() {
        val output = AsyncOperationResult()
            .add("patient") { Patient() }
            .describe()

        assertThat(output, containsString("Tier 1 (parallel): [patient]"))
        assertThat(output, not(containsString("depends on")))
    }

    // ── Multiple independent tasks ────────────────────────────────────────────

    @Test
    fun `describe groups all independent tasks into Tier 1`() {
        val output = AsyncOperationResult()
            .add("patient") { Patient() }
            .add("coverage") { Coverage() }
            .describe()

        assertThat(output, containsString("Tier 1 (parallel): [patient, coverage]"))
        assertThat(output, not(containsString("Tier 2")))
    }

    // ── Two-tier DAG ──────────────────────────────────────────────────────────

    @Test
    fun `describe shows correct two-tier structure`() {
        val output = AsyncOperationResult()
            .add("patient") { Patient() }
            .addAfter("appointment", "patient") { Patient() }
            .describe()

        val lines = output.lines()
        assertThat(lines[1], containsString("Tier 1 (parallel): [patient]"))
        assertThat(lines[2], containsString("Tier 2 (parallel): [appointment]"))
        assertThat(lines[2], containsString("depends on [patient]"))
    }

    // ── Three-tier DAG (matches spec example) ─────────────────────────────────

    @Test
    fun `describe matches the spec example exactly`() {
        val output = AsyncOperationResult()
            .add("patient") { Patient() }
            .add("coverage") { Coverage() }
            .addAfter("appointment", "patient") { Patient() }
            .addAfter("claim", "appointment", "coverage") { Patient() }
            .describe()

        val expected = """
            AsyncOperationResult DAG:
              Tier 1 (parallel): [patient, coverage]
              Tier 2 (parallel): [appointment] → depends on [patient]
              Tier 3 (parallel): [claim] → depends on [appointment, coverage]
        """.trimIndent()

        assertEquals(expected, output)
    }

    // ── Tier placement with mixed dependencies ────────────────────────────────

    @Test
    fun `describe places task in correct tier when deps span multiple tiers`() {
        // patient (T1) → appointment (T2) → claim (T3)
        // coverage (T1)
        // claim depends on appointment (T2) AND coverage (T1) → should be T3
        val output = AsyncOperationResult()
            .add("patient") { Patient() }
            .add("coverage") { Coverage() }
            .addAfter("appointment", "patient") { Patient() }
            .addAfter("claim", "appointment", "coverage") { Patient() }
            .describe()

        assertThat(output, containsString("Tier 3 (parallel): [claim]"))
        assertThat(output, containsString("depends on [appointment, coverage]"))
    }

    @Test
    fun `describe places task in tier after its deepest dependency`() {
        // a (T1) → b (T2) → c (T3) → d (T4)
        val output = AsyncOperationResult()
            .add("a") { Patient() }
            .addAfter("b", "a") { Patient() }
            .addAfter("c", "b") { Patient() }
            .addAfter("d", "c") { Patient() }
            .describe()

        assertThat(output, containsString("Tier 1 (parallel): [a]"))
        assertThat(output, containsString("Tier 2 (parallel): [b]"))
        assertThat(output, containsString("Tier 3 (parallel): [c]"))
        assertThat(output, containsString("Tier 4 (parallel): [d]"))
    }

    // ── Multiple tasks in a non-root tier ─────────────────────────────────────

    @Test
    fun `describe groups multiple tasks in the same tier on one line`() {
        val output = AsyncOperationResult()
            .add("patient") { Patient() }
            .addAfter("appointment", "patient") { Patient() }
            .addAfter("coverage", "patient") { Patient() }
            .describe()

        assertThat(output, containsString("Tier 1 (parallel): [patient]"))
        assertThat(output, containsString("Tier 2 (parallel): [appointment, coverage]"))
        assertThat(output, containsString("depends on [patient]"))
        assertThat(output, not(containsString("Tier 3")))
    }

    @Test
    fun `describe union-merges dependencies when two Tier-2 tasks have different deps`() {
        val output = AsyncOperationResult()
            .add("patient") { Patient() }
            .add("coverage") { Coverage() }
            .addAfter("appointment", "patient") { Patient() }
            .addAfter("claim-item", "coverage") { Patient() }
            .describe()

        // Both appointment and claim-item land in Tier 2; deps should be unioned
        assertThat(output, containsString("Tier 2 (parallel): [appointment, claim-item]"))
        assertThat(output, containsString("depends on [patient, coverage]"))
    }

    // ── Depends-on absent for Tier 1 ─────────────────────────────────────────

    @Test
    fun `describe never shows depends-on clause for Tier 1 tasks`() {
        val output = AsyncOperationResult()
            .add("patient") { Patient() }
            .add("coverage") { Coverage() }
            .addAfter("appointment", "patient") { Patient() }
            .describe()

        val tier1Line = output.lines().first { it.contains("Tier 1") }
        assertThat(tier1Line, not(containsString("depends on")))
    }

    // ── addList tasks appear in describe ─────────────────────────────────────

    @Test
    fun `describe includes tasks registered via addList`() {
        val output = AsyncOperationResult()
            .addList("items") { listOf(Patient(), Coverage()) }
            .describe()

        assertThat(output, containsString("Tier 1 (parallel): [items]"))
    }

    @Test
    fun `describe includes tasks registered via addListAfter`() {
        val output = AsyncOperationResult()
            .add("patient") { Patient() }
            .addListAfter("appointments", "patient") { _ -> listOf(Appointment()) }
            .describe()

        assertThat(output, containsString("Tier 2 (parallel): [appointments]"))
        assertThat(output, containsString("depends on [patient]"))
    }

    // ── Output format ─────────────────────────────────────────────────────────

    @Test
    fun `describe output does not end with trailing newline`() {
        val output = AsyncOperationResult()
            .add("patient") { Patient() }
            .describe()

        assertThat(output.last().toString(), not(`is`("\n")))
    }

    @Test
    fun `describe produces one line per tier`() {
        val output = AsyncOperationResult()
            .add("patient") { Patient() }
            .addAfter("appointment", "patient") { Patient() }
            .addAfter("claim", "appointment") { Patient() }
            .describe()

        val tierLines = output.lines().filter { it.contains("Tier") }
        assertEquals(3, tierLines.size)
    }
}
