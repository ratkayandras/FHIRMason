package dev.ratkay.operation.dstu3;

import org.hl7.fhir.dstu3.model.Base;
import org.hl7.fhir.dstu3.model.Coverage;
import org.hl7.fhir.dstu3.model.Encounter;
import org.hl7.fhir.dstu3.model.Patient;
import org.hl7.fhir.dstu3.model.StringType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link AsyncOperationResult} is fully usable from Java using the
 * {@link java.util.concurrent.Callable}, {@link java.util.function.Function}, and
 * {@link java.util.function.Predicate} sibling overloads.
 *
 * <p>These tests do NOT use any Kotlin-specific types ({@code suspend}, {@code KClass}, etc.)
 * and compile with the standard {@code javac} compiler.
 */
class AsyncOperationResultJavaTest {

    // ── Independent tasks (Callable) ─────────────────────────────────────────

    @Test
    void add_callable_storesResult() {
        Patient patient = new Patient();
        patient.setId("p1");

        OperationResult<Base> result = new AsyncOperationResult()
                .add("patient", () -> patient)
                .executeBlocking();

        assertTrue(result.containsKey("patient"));
        assertFalse(result.hasErrors());
    }

    @Test
    void addList_callable_storesAllElements() {
        Patient p1 = new Patient();
        p1.setId("p1");
        Patient p2 = new Patient();
        p2.setId("p2");

        OperationResult<Base> result = new AsyncOperationResult()
                .addList("patients", () -> List.of(p1, p2))
                .executeBlocking();

        assertTrue(result.containsKey("patients"));
        assertThat(result.getAll("patients"), hasSize(2));
    }

    @Test
    void add_callable_failing_recordsError() {
        OperationResult<Base> result = new AsyncOperationResult()
                .add("patient", () -> { throw new RuntimeException("fetch failed"); })
                .executeBlocking();

        assertFalse(result.containsKey("patient"));
        assertTrue(result.hasErrors());
    }

    // ── Dependent tasks (Function over Map) ──────────────────────────────────

    @Test
    void addAfter_function_receivesDepResultsAndStoresResult() {
        Patient patient = new Patient();
        patient.setId("p1");
        Coverage coverage = new Coverage();
        coverage.setId("cov1");

        OperationResult<Base> result = new AsyncOperationResult()
                .add("patient", () -> patient)
                .add("coverage", () -> coverage)
                .addAfter("encounter", new String[]{"patient", "coverage"}, deps -> {
                    Patient p = (Patient) deps.get("patient").get(0);
                    Encounter enc = new Encounter();
                    enc.setId("enc-" + p.getIdPart());
                    return enc;
                })
                .executeBlocking();

        assertTrue(result.containsKey("patient"));
        assertTrue(result.containsKey("coverage"));
        assertTrue(result.containsKey("encounter"));
        assertFalse(result.hasErrors());
    }

    @Test
    void addAfter_function_skippedWhenDepFails() {
        OperationResult<Base> result = new AsyncOperationResult()
                .add("patient", () -> { throw new RuntimeException("upstream failure"); })
                .addAfter("encounter", new String[]{"patient"}, deps -> new Encounter())
                .executeBlocking();

        assertFalse(result.containsKey("patient"));
        assertFalse(result.containsKey("encounter"));
        assertTrue(result.hasErrors());
    }

    // ── Typed single-dep (Class + Function) ──────────────────────────────────

    @Test
    void addAfter_typedClassFunction_receivesTypedDep() {
        Patient patient = new Patient();
        patient.setId("p1");

        OperationResult<Base> result = new AsyncOperationResult()
                .add("patient", () -> patient)
                .addAfter("note", "patient", Patient.class, p -> new StringType("hello-" + p.getIdPart()))
                .executeBlocking();

        assertTrue(result.containsKey("note"));
        StringType note = (StringType) result.getAll("note").get(0);
        assertEquals("hello-p1", note.getValue());
    }

    @Test
    void addListAfter_typedClassFunction_storesList() {
        Patient p1 = new Patient();
        p1.setId("a");
        Patient p2 = new Patient();
        p2.setId("b");

        OperationResult<Base> result = new AsyncOperationResult()
                .addList("patients", () -> List.of(p1, p2))
                .addListAfterAll("ids", "patients", Patient.class, patients ->
                        patients.stream()
                                .map(p -> (Base) new StringType(p.getIdPart()))
                                .collect(Collectors.toList()))
                .executeBlocking();

        assertThat(result.getAll("ids"), hasSize(2));
    }

    // ── Retry with Predicate ──────────────────────────────────────────────────

    @Test
    void addWithRetry_callable_retriesAndSucceeds() {
        AtomicInteger attempts = new AtomicInteger(0);

        OperationResult<Base> result = new AsyncOperationResult()
                .addWithRetry("patient", 3, 0, e -> true, () -> {
                    if (attempts.incrementAndGet() < 3) throw new RuntimeException("transient");
                    Patient p = new Patient();
                    p.setId("p1");
                    return p;
                })
                .executeBlocking();

        assertTrue(result.containsKey("patient"));
        assertFalse(result.hasErrors());
        assertEquals(3, attempts.get());
    }

    @Test
    void addWithRetry_predicate_stopsRetryingWhenPredicateReturnsFalse() {
        AtomicInteger attempts = new AtomicInteger(0);

        OperationResult<Base> result = new AsyncOperationResult()
                .addWithRetry("patient", 5, 0,
                        e -> !(e instanceof IllegalArgumentException),
                        () -> {
                            attempts.incrementAndGet();
                            throw new IllegalArgumentException("do not retry");
                        })
                .executeBlocking();

        assertFalse(result.containsKey("patient"));
        assertTrue(result.hasErrors());
        assertEquals(1, attempts.get());
    }

    // ── Timeout (Callable) ────────────────────────────────────────────────────

    @Test
    void addWithTimeout_callable_storesResultWhenCompletesInTime() {
        OperationResult<Base> result = new AsyncOperationResult()
                .addWithTimeout("patient", 500, () -> new Patient())
                .executeBlocking();

        assertTrue(result.containsKey("patient"));
        assertFalse(result.hasErrors());
    }

    // Note: per-task timeout cancellation requires a coroutine suspension point (e.g. Kotlin's
    // delay()). Blocking Java code (Thread.sleep) runs to completion before cancellation fires.
    // Use the DAG-level timeout() instead for a hard wall-clock bound over the whole execution.

    // ── Conditional (addIf) ───────────────────────────────────────────────────

    @Test
    void addIf_trueCondition_taskRuns() {
        OperationResult<Base> result = new AsyncOperationResult()
                .addIf(true, "patient", () -> new Patient())
                .executeBlocking();

        assertTrue(result.containsKey("patient"));
    }

    @Test
    void addIf_falseCondition_taskSkipped() {
        OperationResult<Base> result = new AsyncOperationResult()
                .addIf(false, "patient", () -> new Patient())
                .executeBlocking();

        assertFalse(result.containsKey("patient"));
        assertFalse(result.hasErrors());
    }

    // ── Default (addWithDefault) ──────────────────────────────────────────────

    @Test
    void addWithDefault_callable_returnsDefaultOnFailure() {
        Patient fallback = new Patient();
        fallback.setId("fallback");

        OperationResult<Base> result = new AsyncOperationResult()
                .addWithDefault("patient", fallback,
                        () -> { throw new RuntimeException("unavailable"); })
                .executeBlocking();

        assertTrue(result.containsKey("patient"));
        assertFalse(result.hasErrors());
        assertEquals("fallback", ((Patient) result.getAll("patient").get(0)).getIdPart());
    }

    // ── Fluent chain from Java ────────────────────────────────────────────────

    @Test
    void fullPipeline_fluentChainFromJava() {
        Patient patient = new Patient();
        patient.setId("p1");
        Coverage coverage = new Coverage();
        coverage.setId("cov1");

        OperationResult<Base> result = new AsyncOperationResult()
                .add("patient", () -> patient)
                .add("coverage", () -> coverage)
                .addAfter("encounter", new String[]{"patient"}, deps -> {
                    Patient p = (Patient) deps.get("patient").get(0);
                    Encounter enc = new Encounter();
                    enc.setId("enc-" + p.getIdPart());
                    return enc;
                })
                .addAfter("summary", new String[]{"patient", "encounter"}, deps -> {
                    Map<String, List<Base>> d = deps;
                    return new StringType(
                            d.get("patient").get(0).fhirType() + "+" +
                            d.get("encounter").get(0).fhirType());
                })
                .executeBlocking();

        assertFalse(result.hasErrors());
        assertTrue(result.containsKey("patient"));
        assertTrue(result.containsKey("coverage"));
        assertTrue(result.containsKey("encounter"));
        assertTrue(result.containsKey("summary"));
    }
}
