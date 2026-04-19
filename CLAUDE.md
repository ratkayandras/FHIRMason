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
- If no explicit key name is given, the storage key is always the **output** value's `fhirType().lowercase()` — never the input type's name. For example, a builder that receives `Patient` resources and returns an `OperationOutcome` stores under `"operationoutcome"`, not `"patient"`. This applies to every method with an optional `name` parameter: `add`, `addAll`, `addFrom`, `addAllFrom`, `addFromHavingAllExtensions`, `addFromHavingAnyExtension`, `addAllFrom*`, `addFromHavingExtensionWithValueType`, `addFromHavingExtensionValueMatching`, `addAllFromHavingExtensionValueMatching`, `addFromMatching`, `addAllFromMatching`, and `selectByPath`. Pass `null` (or omit the name) to `storeAndCopy`/`storeListAndCopy` and let them derive the key from the result; never pass a derived input-type name.
- Test method names use Kotlin backtick syntax: `` `descriptive test name` ``
- Validate with `require()` and `error()` — no checked exceptions
- `AsyncOperationResult` uses sealed classes for DAG task nodes with cycle detection at registration time

## Java Interop Rule

FHIRMason must be fully usable from Java with an unbroken fluent chain. Kotlin extension functions compile to **static** JVM methods and cannot be chained in Java — `result.flatMap(...)` becomes `OperationResultKt.flatMap(result, ...)`, which destroys the API.

**Rule:** Never use Kotlin extension functions for any public API method on `OperationResult<T>` (or any other public API class). All public methods must be declared as class members so that Java callers can use them as instance methods.

Extension functions are only acceptable for:
- Private/internal helper utilities that Java callers never invoke directly (e.g. top-level `internal fun` helpers in the same package).
- The existing `getResultList()` top-level extension, which is a special case: it only applies to `OperationResult<List<R>>` and is documented as a Kotlin convenience alias.

When splitting or reorganising source files, keep all public instance methods inside the class body. Only extract **private helpers** (pure functions that accept their inputs as parameters rather than accessing `this`) to separate internal files.

### @JvmStatic and @JvmOverloads requirements

Every **public** `companion object` function must be annotated with `@JvmStatic` so Java callers can write `OperationResult.of(...)` instead of `OperationResult.Companion.of(...)`.

Every **public** function (instance method or companion function) that has one or more default parameter values must also be annotated with `@JvmOverloads` so the Kotlin compiler generates the full set of Java overloads for each trailing-default combination.

Exceptions — do **not** add these annotations to:
- `internal` functions (not part of the public API).
- `inline fun` with `reified` type parameters — these have no JVM bytecode representation and cannot be annotated.

## Import Rules

- **No wildcard imports** (`import foo.*`) anywhere in source or test files. Import every symbol individually.
- If two overloads would be identical after JVM type erasure (e.g. lambdas differing only in return type both erase to `Function1`), Kotlin's overload resolution cannot pick between them at call sites. Do **not** use `@JvmName` as a workaround — instead, give each overload a distinct, descriptive name that encodes the input type. Update README when adding such methods.

## Java Interop: No Kotlin-specific types in the public API

Java callers must never be required to reference Kotlin-specific types such as `KClass`, `KFunction`, or anything from `kotlin.reflect.*`. If a public method accepts a `KClass<T>` parameter, a `Class<T>` overload **must** exist alongside it. The `Class<T>` overload delegates to the `KClass<T>` variant via `.kotlin`:

```kotlin
@JvmStatic
fun <V : Type> hasExtensionValueMatching(
    url: String,
    valueType: Class<V>,
    predicate: (V) -> Boolean
): (Base) -> Boolean = hasExtensionValueMatchingInternal(url, valueType.kotlin, predicate)
```

The same rule applies to any helper or factory object (e.g. `FhirFilter`) whose methods are part of the public API. When adding a new method that takes `KClass`, always add the `Class` sibling in the same commit.

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

## Pre-Commit and Pre-Push Requirement

**This is a hard requirement — no exceptions.**

Run the full test suite (`mvn test`) before every commit **and** before every push. All tests across all modules must be green with zero failures and zero errors.

- Do **not** commit if any test is failing.
- Do **not** push if any test is failing.
- Fix every failure, even if it appears unrelated to your changes, before committing or pushing.
- A commit that introduces or leaves a failing test is a policy violation regardless of intent.

## DSTU3 Porting Policy

FHIRMason supports both FHIR R4 (the primary version) and FHIR DSTU3.

**R4 has priority.** All new features are designed and implemented for R4 first.

**New features must be ported to DSTU3 unless impossible.** After a feature is complete and tested in R4, port it to the DSTU3 module with equivalent source and test files. A feature may be skipped for DSTU3 only if it relies on R4-exclusive FHIR constructs (e.g., a resource type or element that does not exist in DSTU3). Document any such exception in the PR or commit message.

Port checklist:
- Mirror the DSTU3 source file (change package, swap `org.hl7.fhir.r4.model.*` → `org.hl7.fhir.dstu3.model.*`, adjust any DSTU3-specific API differences)
- Mirror all R4 test files for the feature in the DSTU3 test directory, adapting resource types and imports
- Ensure all DSTU3 tests pass before committing

## README Maintenance

Keep `README.md` up to date on every branch. When a branch adds or changes a feature, update the relevant section(s) of the README before committing:
- Add new methods to the appropriate table or code block
- Add or update usage examples that demonstrate the new behaviour
- **Update the Project Structure section whenever any file is added, removed, or renamed** — this includes test files (`src/test/…`), not just production source files. The tree in the README must exactly match the files on disk.
- **Never describe previous behaviour or change history in the README.** The README documents the current state only; design rationale and migration notes belong in commit messages or PR descriptions.
