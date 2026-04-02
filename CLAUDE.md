# FHIRMason

A Kotlin library providing a fluent, chainable API for accumulating and transforming FHIR R4 resources. Wraps FHIR `Base` objects in an `OperationResult` builder that collects named resources into a shared parameter map, serializable to a FHIR `Parameters` resource.

## Build & Test

```bash
mvn clean install   # full build
mvn test            # run tests only
```

## Project Structure

```
src/main/java/dev/ratkay/operation/
  OperationResult.kt        # Synchronous accumulator builder
  AsyncOperationResult.kt   # Async/coroutine DAG-based builder

src/test/java/dev/ratkay/operation/
  OperationResultTest.kt
  AsyncOperationResultTest.kt
```

## Tech Stack

- Kotlin 2.1.21, JVM target Java 11
- HAPI FHIR 7.6.1 (R4)
- Kotlin Coroutines 1.10.2
- JUnit Jupiter 5.14.3, Hamcrest 3.0, ApprovalCrest
- Maven

## Conventions

- `OperationResult` is **immutable** — all builder methods return new instances
- Factory entry points live in `companion object` as `of()` methods
- Use reified generics (`KClass<R>`) for type-safe resource filtering
- Receiver-lambda overloads are named `addUsing` / `addAllUsing` (not `add` / `addAll`)
- If no explicit key name is given, defaults to `value.fhirType().lowercase()`
- Test method names use Kotlin backtick syntax: `` `descriptive test name` ``
- Validate with `require()` and `error()` — no checked exceptions
- `AsyncOperationResult` uses sealed classes for DAG task nodes with cycle detection at registration time

## Exception Handling Rules

Every builder method that catches exceptions must follow one of two established patterns. Do not invent new patterns.

### Pattern A — head-changing steps (`runBuilderStep`)

Used by `add`, `addUsing`, `addAll`, `addAllUsing`, `addFrom`, `addAllFrom`, `flatMap`.
Records an **ERROR**-severity `OperationOutcome` and skips the head value (`skippedResult()`).

```kotlin
catch (e: Exception) {
    val durationMs = System.currentTimeMillis() - start
    recordMetric(name ?: "unknown", "", durationMs, false)
    outcomes.add(when (e) {
        is BaseServerResponseException -> e.toOperationOutcome()   // preserves embedded rich outcome
        else                           -> e.toOperationOutcome()   // creates generic ERROR outcome
    })
    skippedResult()
}
```

### Pattern B — head-preserving steps (`runPrimitiveStep`, `addOrSkip`, `addOrDefault`)

Used by `addOrSkip`, `addOrDefault`, and all primitive-value convenience methods (`addString`, `addBoolean`, …).
Records a **WARNING**-severity `OperationOutcome` and preserves the current head type `T`.

```kotlin
catch (e: Exception) {
    val durationMs = System.currentTimeMillis() - start
    logger.warn("FHIRMason | step='{}' | WARN: {}", name, e.message)
    recordMetric(name, "", durationMs, false)
    val outcome = when (e) {
        is BaseServerResponseException -> e.toOperationOutcome()   // preserves embedded rich outcome
        else                           -> e.toOperationOutcome()   // creates generic ERROR outcome
    }
    outcome.issue.forEach { it.severity = OperationOutcome.IssueSeverity.WARNING }
    outcomes.add(outcome)
    copyWith(result)
}
```

**Key rules:**
- Always dispatch on `BaseServerResponseException` first — this extracts the embedded `OperationOutcome` from the HAPI exception rather than discarding it.
- Head-changing steps use `skippedResult()` (result becomes `null`); head-preserving steps use `copyWith(result)`.
- Primitive convenience methods must delegate to `runPrimitiveStep` (Pattern B) — never duplicate the try/catch inline.
- Do not add new catch patterns without updating this section.

## Pre-Push Requirement

Run the full test suite (`mvn test`) before every push. All tests across all modules must be green — fix any failure, even if it appears unrelated to your changes, before committing and pushing.

## README Maintenance

Keep `README.md` up to date on every branch. When a branch adds or changes a feature, update the relevant section(s) of the README before committing:
- Add new methods to the appropriate table or code block
- Add or update usage examples that demonstrate the new behaviour
- Update the Project Structure section if new source files are added
