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

## README Maintenance

Keep `README.md` up to date on every branch. When a branch adds or changes a feature, update the relevant section(s) of the README before committing:
- Add new methods to the appropriate table or code block
- Add or update usage examples that demonstrate the new behaviour
- Update the Project Structure section if new source files are added
