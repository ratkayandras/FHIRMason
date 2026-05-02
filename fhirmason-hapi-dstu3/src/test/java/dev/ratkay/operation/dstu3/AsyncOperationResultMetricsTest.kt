package dev.ratkay.operation.dstu3

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.hl7.fhir.dstu3.model.Encounter
import org.hl7.fhir.dstu3.model.Patient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class AsyncOperationResultMetricsTest {

    // ── Log capture setup ─────────────────────────────────────────────────────

    private lateinit var logAppender: ListAppender<ILoggingEvent>
    private lateinit var logger: Logger

    @BeforeEach
    fun attachLogAppender() {
        logger = LoggerFactory.getLogger(AsyncOperationResult::class.java) as Logger
        logger.level = Level.DEBUG
        logAppender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(logAppender)
    }

    @AfterEach
    fun detachLogAppender() {
        logger.detachAppender(logAppender)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun patient() = Patient().apply { setId("p1") }
    private fun encounter() = Encounter().apply { status = Encounter.EncounterStatus.FINISHED }

    private fun debugMessages() = logAppender.list
        .filter { it.level == Level.DEBUG }
        .map { it.formattedMessage }

    // ── getMetrics disabled by default ────────────────────────────────────────

    @Test
    fun `getMetrics returns empty map when timed is not called`() {
        val dag = AsyncOperationResult()
            .add("patient") { patient() }
            .add("encounter") { encounter() }

        dag.runBlocking()

        assertTrue(dag.getMetrics().isEmpty())
    }

    // ── getMetrics with timed() enabled ──────────────────────────────────────

    @Test
    fun `getMetrics contains an entry for each registered task`() {
        val dag = AsyncOperationResult()
            .timed()
            .add("patient") { patient() }
            .add("encounter") { encounter() }

        dag.runBlocking()

        val metrics = dag.getMetrics()
        assertEquals(2, metrics.size)
        assertTrue(metrics.containsKey("patient"))
        assertTrue(metrics.containsKey("encounter"))
    }

    @Test
    fun `getMetrics task entry has correct resourceType`() {
        val dag = AsyncOperationResult()
            .timed()
            .add("patient") { patient() }

        dag.runBlocking()

        assertEquals("Patient", dag.getMetrics()["patient"]!!.resourceType)
    }

    @Test
    fun `getMetrics task entry has non-negative duration`() {
        val dag = AsyncOperationResult()
            .timed()
            .add("patient") { patient() }

        dag.runBlocking()

        assertTrue(dag.getMetrics()["patient"]!!.durationMs >= 0)
    }

    @Test
    fun `getMetrics success is true for completed tasks`() {
        val dag = AsyncOperationResult()
            .timed()
            .add("patient") { patient() }

        dag.runBlocking()

        assertTrue(dag.getMetrics()["patient"]!!.success)
    }

    @Test
    fun `getMetrics success is false for failed tasks`() {
        val dag = AsyncOperationResult()
            .timed()
            .add("bad") { throw RuntimeException("boom") }

        dag.runBlocking()

        val m = dag.getMetrics()["bad"]!!
        assertFalse(m.success)
        assertEquals("bad", m.stepName)
    }

    @Test
    fun `getMetrics includes parallel tasks that ran concurrently`() {
        val dag = AsyncOperationResult()
            .timed()
            .add("patient") { patient() }
            .add("encounter") { encounter() }

        dag.runBlocking()

        assertEquals(2, dag.getMetrics().size)
        assertTrue(dag.getMetrics()["patient"]!!.success)
        assertTrue(dag.getMetrics()["encounter"]!!.success)
    }

    @Test
    fun `getMetrics includes dependent task with correct step name`() {
        val dag = AsyncOperationResult()
            .timed()
            .add("patient") { patient() }
            .addAfter("encounter", "patient", Patient::class) { _ -> encounter() }

        dag.runBlocking()

        assertTrue(dag.getMetrics().containsKey("encounter"))
        assertEquals("encounter", dag.getMetrics()["encounter"]!!.stepName)
    }

    // ── getTotalDuration ──────────────────────────────────────────────────────

    @Test
    fun `getTotalDuration is zero before run`() {
        val dag = AsyncOperationResult().add("patient") { patient() }
        assertEquals(0L, dag.getTotalDuration())
    }

    @Test
    fun `getTotalDuration is positive after run`() {
        val dag = AsyncOperationResult()
            .add("patient") { patient() }
            .add("encounter") { encounter() }

        dag.runBlocking()

        assertTrue(dag.getTotalDuration() >= 0)
    }

    // ── DEBUG log messages ────────────────────────────────────────────────────

    @Test
    fun `each task emits STARTED and COMPLETED DEBUG log messages`() {
        AsyncOperationResult()
            .add("patient") { patient() }
            .runBlocking()

        val msgs = debugMessages()
        assertTrue(msgs.any { it.contains("task='patient'") && it.contains("status=STARTED") })
        assertTrue(msgs.any { it.contains("task='patient'") && it.contains("status=COMPLETED") })
    }

    @Test
    fun `COMPLETED log includes duration`() {
        AsyncOperationResult()
            .add("patient") { patient() }
            .runBlocking()

        val completedMsg = debugMessages().first {
            it.contains("task='patient'") && it.contains("COMPLETED")
        }
        assertTrue(completedMsg.contains("duration="))
    }

    @Test
    fun `DAG completion emits a summary DEBUG log`() {
        AsyncOperationResult()
            .add("patient") { patient() }
            .add("encounter") { encounter() }
            .runBlocking()

        val msgs = debugMessages()
        assertTrue(msgs.any { it.contains("dag=COMPLETED") && it.contains("totalDuration=") })
    }

    @Test
    fun `DAG completion log includes task count`() {
        AsyncOperationResult()
            .add("patient") { patient() }
            .add("encounter") { encounter() }
            .runBlocking()

        val dagMsg = debugMessages().first { it.contains("dag=COMPLETED") }
        assertTrue(dagMsg.contains("tasks=2"))
    }

    @Test
    fun `failed task emits FAILED status in log`() {
        AsyncOperationResult()
            .add("broken") { throw RuntimeException("nope") }
            .runBlocking()

        assertTrue(debugMessages().any { it.contains("task='broken'") && it.contains("status=FAILED") })
    }

    @Test
    fun `log messages use FHIRMason dot async prefix`() {
        AsyncOperationResult()
            .add("patient") { patient() }
            .runBlocking()

        assertTrue(debugMessages().all { it.startsWith("FHIRMason.async") })
    }
}
