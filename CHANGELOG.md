# Changelog

## [0.0.1] — 2026-05-27

### Added

- **`OperationResult<T>`** — immutable, fluent, chainable synchronous builder for accumulating and transforming FHIR resources. Supports R4 (`fhirmason-hapi-r4`) and DSTU3 (`fhirmason-hapi-dstu3`) with an identical public API surface.
- **`AsyncOperationResult`** — coroutine-based DAG builder with per-task timeout, retry, fallback defaults, and conditional task registration.
- **`fhirmason-hapi-spring-r4`** — Spring Boot auto-configuration module; registers a `FhirMasonFactory` bean and binds `fhirmason.*` configuration properties automatically.
- **`FhirPath`** — fluent, version-agnostic FHIRPath expression builder.
- **`FhirFilter`** — predicate factory for type- and extension-based filtering, with Java-friendly `and()` / `or()` / `not()` combinators.
- **`ReferenceLinkRule`** — descriptor for explicit FHIR reference linking; automatic linking also available via `linkReferences()`.
- **`ErrorStrategy`** — `FAIL_FAST`, `ACCUMULATE`, and `PROPAGATE` modes controlling how pipeline errors are handled.
- **`StepMetrics`** — per-step timing and outcome data, collectible via `timed()`.
- **`DateTimeInput`** — sealed wrapper that collapses `LocalDateTime`, `ZonedDateTime`, and `OffsetDateTime` overloads for date/time builder methods.
- Full primitive convenience methods: `addString`, `addBoolean`, `addInteger`, `addDecimal`, `addCode`, `addUri`, `addDate`, `addDateTime`, `addInstant`, `addTime`, `addCanonical`, and `*Using` receiver-lambda variants for each.
- Complex-type convenience methods: `addCoding`, `addReference`, `addIdentifier`, `addPeriod`, `addQuantity`, `addCodeableConcept`.
- FHIRPath-driven builders: `addFromMatching`, `addAllFromMatching`, `selectByPath`, `whenPath`, `guardPath`.
- Retry support with configurable attempts and exponential backoff: `addWithRetry`, `addWithRetryUsing`.
- Conditional chaining: `whenTrue`, `ifPresent`, `guardFalse`.
- Transformation methods: `flatMap`, `mapHead`, `mapHeadUsing`, `mapStored`, `merge`, `filterByType`, `filterByName`, `remove`, `rename`.
- Two-pattern exception handling: **Pattern A** (ERROR severity, skip head) for head-changing steps; **Pattern B** (WARNING severity, preserve head) for head-preserving steps.
- Java interop: `@JvmStatic` and `@JvmOverloads` throughout, `Class<T>` overloads alongside `reified` Kotlin convenience forms.
- KDoc on every public class, method, and property.
- CI workflow with JaCoCo coverage gate (65 % minimum) and Maven Central publish workflow (GPG-signed, tag-triggered).
