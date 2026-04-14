package dev.ratkay.operation.r4

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.hl7.fhir.r4.model.Encounter
import org.hl7.fhir.r4.model.Observation
import org.hl7.fhir.r4.model.Patient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class OperationResultMetricsTest {

    // ── Log capture setup ─────────────────────────────────────────────────────

    private lateinit var logAppender: ListAppender<ILoggingEvent>
    private lateinit var logger: Logger

    @BeforeEach
    fun attachLogAppender() {
        logger = LoggerFactory.getLogger(OperationResult::class.java) as Logger
        logger.level = Level.TRACE
        logAppender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(logAppender)
    }

    @AfterEach
    fun detachLogAppender() {
        logger.detachAppender(logAppender)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun patient(id: String = "p1") = Patient().apply { setId(id) }
    private fun encounter() = Encounter().apply { status = Encounter.EncounterStatus.FINISHED }
    private fun observation() = Observation().apply { status = Observation.ObservationStatus.FINAL }

    private fun messages() = logAppender.list.map { it.formattedMessage }
    private fun debugMessages() = logAppender.list.filter { it.level == Level.DEBUG }.map { it.formattedMessage }
    private fun traceMessages() = logAppender.list.filter { it.level == Level.TRACE }.map { it.formattedMessage }
    private fun warnMessages() = logAppender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }

    // ── getMetrics() disabled by default ─────────────────────────────────────

    @Test
    fun `getMetrics returns empty list when timed is not called`() {
        val result = OperationResult.of(patient())
            .add { encounter() }
            .add { observation() }

        assertTrue(result.getMetrics().isEmpty())
    }

    // ── getMetrics() with timed() enabled ─────────────────────────────────────

    @Test
    fun `getMetrics returns one entry per add step when timed`() {
        val result = OperationResult.of(patient())
            .timed()
            .add("encounter") { encounter() }
            .add("observation") { observation() }

        val metrics = result.getMetrics()
        assertEquals(2, metrics.size)
    }

    @Test
    fun `getMetrics step names match the keys used`() {
        val result = OperationResult.of(patient())
            .timed()
            .add("enc") { encounter() }

        assertEquals("enc", result.getMetrics().single().stepName)
    }

    @Test
    fun `getMetrics resourceType reflects the FHIR type of the produced value`() {
        val result = OperationResult.of(patient())
            .timed()
            .add { encounter() }

        assertEquals("Encounter", result.getMetrics().single().resourceType)
    }

    @Test
    fun `getMetrics durationMs is non-negative`() {
        val result = OperationResult.of(patient())
            .timed()
            .add { encounter() }

        assertTrue(result.getMetrics().single().durationMs >= 0)
    }

    @Test
    fun `getMetrics success is true when step completes without error`() {
        val result = OperationResult.of(patient())
            .timed()
            .add { encounter() }

        assertTrue(result.getMetrics().single().success)
    }

    @Test
    fun `getMetrics success is false when step throws`() {
        val result = OperationResult.of(patient())
            .timed()
            .add("bad") { throw RuntimeException("boom") }

        val metric = result.getMetrics().single()
        assertFalse(metric.success)
        assertEquals("bad", metric.stepName)
        assertEquals("", metric.resourceType)
    }

    @Test
    fun `getMetrics accumulates across the pipeline chain`() {
        val result = OperationResult.of(patient())
            .timed()
            .add("a") { encounter() }
            .add("b") { observation() }
            .add("c") { patient("p2") }

        assertEquals(3, result.getMetrics().size)
        assertEquals(listOf("a", "b", "c"), result.getMetrics().map { it.stepName })
    }

    @Test
    fun `timed can be called anywhere in the chain and captures subsequent steps`() {
        val result = OperationResult.of(patient())
            .add("before") { encounter() }   // not timed yet
            .timed()
            .add("after") { observation() }  // timed

        val metrics = result.getMetrics()
        assertEquals(1, metrics.size)
        assertEquals("after", metrics.single().stepName)
    }

    @Test
    fun `getMetrics works with addUsing`() {
        val result = OperationResult.of(patient())
            .timed()
            .addUsing("encounter") { _ -> encounter() }

        assertEquals("encounter", result.getMetrics().single().stepName)
        assertTrue(result.getMetrics().single().success)
    }

    @Test
    fun `getMetrics works with addAll`() {
        val result = OperationResult.of(patient())
            .timed()
            .addAll("obs") { listOf(observation(), observation()) }

        val m = result.getMetrics().single()
        assertEquals("obs", m.stepName)
        assertTrue(m.resourceType.startsWith("Observation"))
        assertTrue(m.success)
    }

    @Test
    fun `getMetrics records failure for addOrSkip when builder throws`() {
        val result = OperationResult.of(patient())
            .timed()
            .addOrSkip("risky") { throw RuntimeException("oops") }

        val m = result.getMetrics().single()
        assertEquals("risky", m.stepName)
        assertFalse(m.success)
    }

    @Test
    fun `getMetrics records success for addOrDefault when builder succeeds`() {
        val result = OperationResult.of(patient())
            .timed()
            .addOrDefault("enc", encounter()) { encounter() }

        assertTrue(result.getMetrics().single().success)
    }

    @Test
    fun `getMetrics records failure for addOrDefault when builder throws`() {
        val result = OperationResult.of(patient())
            .timed()
            .addOrDefault("enc", encounter()) { throw RuntimeException("fail") }

        assertFalse(result.getMetrics().single().success)
    }

    // ── DEBUG log messages ────────────────────────────────────────────────────

    @Test
    fun `add emits a DEBUG log with step name, type, and duration`() {
        OperationResult.of(patient()).add("coverage") { encounter() }

        val msg = debugMessages().first { it.contains("step='coverage'") }
        assertTrue(msg.startsWith("FHIRMason |"))
        assertTrue(msg.contains("type=Encounter"))
        assertTrue(msg.contains("duration="))
    }

    @Test
    fun `add emits DEBUG log even when timed is not enabled`() {
        OperationResult.of(patient()).add("enc") { encounter() }

        assertTrue(debugMessages().any { it.contains("step='enc'") })
    }

    @Test
    fun `addUsing emits a DEBUG log`() {
        OperationResult.of(patient()).addUsing("enc") { _ -> encounter() }

        assertTrue(debugMessages().any { it.contains("step='enc'") })
    }

    @Test
    fun `addAll emits a DEBUG log showing list count`() {
        OperationResult.of(patient()).addAll("obs") { listOf(observation(), observation()) }

        val msg = debugMessages().first { it.contains("step='obs'") }
        assertTrue(msg.contains("[2]"))
    }

    // ── TRACE log messages ────────────────────────────────────────────────────

    @Test
    fun `each step emits a TRACE log with parameter state`() {
        OperationResult.of(patient()).add("enc") { encounter() }

        assertTrue(traceMessages().any { it.startsWith("FHIRMason | state:") })
    }

    // ── WARN log messages ─────────────────────────────────────────────────────

    @Test
    fun `addOrSkip emits WARN log when builder throws`() {
        OperationResult.of(patient())
            .addOrSkip("flaky") { throw RuntimeException("timeout") }

        val msg = warnMessages().first { it.contains("step='flaky'") }
        assertTrue(msg.contains("WARN:"))
        assertTrue(msg.contains("timeout"))
    }

    @Test
    fun `addOrDefault emits WARN log when builder throws`() {
        OperationResult.of(patient())
            .addOrDefault("enc", encounter()) { throw RuntimeException("gone") }

        assertTrue(warnMessages().any { it.contains("WARN:") && it.contains("gone") })
    }
}
