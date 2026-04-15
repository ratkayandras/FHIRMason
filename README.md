# FHIRMason

<img width="1371" height="380" alt="image" src="https://github.com/user-attachments/assets/5f1a7844-004c-4197-8f2d-fd263308fc64" />


A Kotlin library that provides a fluent, chainable API for accumulating and transforming FHIR resources. FHIRMason wraps FHIR `Base` objects in an `OperationResult` builder, enabling composable pipelines that collect named resources into a shared parameter map. Both [FHIR R4](https://hl7.org/fhir/R4/) and [DSTU3](https://hl7.org/fhir/STU3/) are supported.

---

## Getting Started

Add the module for your FHIR version to your Maven project:

**FHIR R4** (`dev.ratkay.operation.r4.OperationResult`):

```xml
<dependency>
    <groupId>dev.ratkay</groupId>
    <artifactId>fhirmason-r4</artifactId>
    <version>0.0.1</version>
</dependency>
```

**FHIR DSTU3** (`dev.ratkay.operation.dstu3.OperationResult`):

```xml
<dependency>
    <groupId>dev.ratkay</groupId>
    <artifactId>fhirmason-dstu3</artifactId>
    <version>0.0.1</version>
</dependency>
```

**Spring Boot auto-configuration** (wires a `FhirMasonFactory` bean automatically):

```xml
<dependency>
    <groupId>dev.ratkay</groupId>
    <artifactId>fhirmason-spring</artifactId>
    <version>0.0.1</version>
</dependency>
```

---

## Overview

Working with FHIR resources often involves fetching and combining multiple resources across several steps. FHIRMason models this as an accumulator pipeline: each step adds one or more named resources to a shared store, and the final state can be inspected, filtered, serialized, or linked.

Two builders are provided:

- **`OperationResult`** — synchronous, immutable, fluent chain
- **`AsyncOperationResult`** — async/coroutine DAG that automatically parallelises independent tasks

```kotlin
// Synchronous
val result = OperationResult.of(patient)
    .add("coverage") { fetchCoverage() }
    .addUsing("encounter") { p -> lookupEncounter(p) }
    .addAll("history") { fetchEncounterHistory() }
    .linkReferences()           // auto-wire FHIR references between resources

result.toParameters()           // serialize to a FHIR Parameters resource
result.toTransactionBundle()    // serialize to a FHIR transaction Bundle
result.getResult()              // the most recently added value (typed)

// Async
val result = AsyncOperationResult()
    .add("patient") { fetchPatient() }
    .add("coverage") { fetchCoverage() }
    .addAfter("encounter", "patient", Patient::class) { patient -> lookupEncounter(patient) }
    .runBlocking()
```

---

## OperationResult (Synchronous)

### Entry Points

```kotlin
// From a single value — name defaults to fhirType().lowercase() when omitted
OperationResult.of(patient)
OperationResult.of(patient, "myPatient")
OperationResult.of(patient, errorStrategy = ErrorStrategy.ACCUMULATE)

// From a list — each item keyed by its fhirType() unless a shared name is given
OperationResult.of(listOf(patient, appointment))
OperationResult.of(listOf(patient, appointment), "inputs")

// From an existing FHIR Parameters resource
OperationResult.fromParameters(parameters)
OperationResult.fromParametersTyped<Patient>(parameters, primaryKey = "patient")

// From a FHIR Bundle
OperationResult.fromBundle(bundle)
OperationResult.fromBundle(bundle) { entry -> entry.fullUrl }   // custom key strategy

// Empty — no values, no typed head; useful for conditional pipelines or merge targets
OperationResult.empty()
OperationResult.empty(errorStrategy = ErrorStrategy.ACCUMULATE)
```

```kotlin
// Start empty, conditionally add
val result = OperationResult.empty()
    .add("patient") { fetchPatient() }
    .addOrSkip("coverage") { fetchCoverage() }

// Valid even with no parameters
OperationResult.empty().toParameters()  // empty Parameters resource
```

### Builder Methods

All builder methods add values to the internal parameter map and return a new `OperationResult` whose generic type `T` tracks the most recently added value. Calling `getResult()` on the returned instance gives back that value without casting.

#### Single item

| Method | Lambda receives | Name resolution |
|---|---|---|
| `add(name?) { R }` | nothing | `name` or `fhirType()` |
| `addUsing(name?) { t -> R }` | current result `t: T` | `name` or `fhirType()` |

```kotlin
val result = OperationResult.of(patient)
    .add("appointment") { fetchAppointment() }             // no access to previous value
    .addUsing("encounter") { appt -> fetchEncounter(appt) } // receives last added value

val encounter: Encounter = result.getResult()   // typed — no cast needed
```

#### Multiple items

| Method | Lambda receives | Name resolution |
|---|---|---|
| `addAll(name?) { List<R> }` | nothing | `name` or per-item `fhirType()` |
| `addAllUsing(name?) { t -> List<R> }` | current result `t: T` | `name` or per-item `fhirType()` |

```kotlin
val result = OperationResult.of(patient)
    .addAll("history") { fetchEncounters() }
    .addAllUsing("observations") { encounters -> fetchObservations(encounters) }

val observations: List<Observation> = result.getResultList()  // typed list, no cast
```

#### From existing parameters

| Method | Description |
|---|---|
| `addFrom(name, type) { list -> R }` | Filters stored values under `name` by `type`, passes the typed list to the builder |
| `addAllFrom(name, type) { list -> List<R> }` | Same, but the builder returns a list |

```kotlin
val result = OperationResult.of(listOf(patient, appointment), "inputs")
    .addFrom("inputs", Patient::class) { patients ->
        buildClaimFor(patients.first())
    }
```

#### From all parameters, filtered by type and extension URLs

These methods search **all** accumulated parameters (regardless of key), keep only resources of the given type, and — if extension URLs are supplied — further filter by extension-URL presence. Two matching strategies are available, encoded directly in the method name:

| Method | Semantics | Description |
|---|---|---|
| `addFromHavingAllExtensions(type, extUrls...) { list -> R }` | AND | Resource must carry **every** supplied URL; builder returns a single `R` stored under the type's simple name |
| `addFromHavingAllExtensions(name, type, extUrls...) { list -> R }` | AND | Same, explicit output `name` |
| `addFromHavingAnyExtension(type, extUrls...) { list -> R }` | OR | Resource must carry **at least one** of the URLs; single `R` result |
| `addFromHavingAnyExtension(name, type, extUrls...) { list -> R }` | OR | Same, explicit output `name` |
| `addAllFromHavingAllExtensions(type, extUrls...) { list -> List<R> }` | AND | Builder returns a list |
| `addAllFromHavingAllExtensions(name, type, extUrls...) { list -> List<R> }` | AND | Named list variant |
| `addAllFromHavingAnyExtension(type, extUrls...) { list -> List<R> }` | OR | Builder returns a list |
| `addAllFromHavingAnyExtension(name, type, extUrls...) { list -> List<R> }` | OR | Named list variant |

When no URLs are supplied, all resources of the given type are passed to the builder regardless of which variant is used.

All unnamed variants have reified inline overloads so the `KClass` argument can be omitted in Kotlin. Named variants intentionally have no reified overload — a reified `addFromHavingAllExtensions(name, extUrls…)` would be ambiguous with the unnamed reified overload when the first argument is a `String`. Use the KClass overload when an explicit output key is needed.

```kotlin
// AND — Patients that carry both extensions
val result = OperationResult.of(patients, "patients")
    .addFromHavingAllExtensions(Patient::class, "http://ext/enrolled", "http://ext/consented") { filtered ->
        OperationOutcome().apply { addIssue().diagnostics = "Eligible: ${filtered.size}" }
    }

// OR — Patients that carry at least one of the extensions
val result2 = OperationResult.of(patients, "patients")
    .addFromHavingAnyExtension(Patient::class, "http://ext/high-priority", "http://ext/urgent") { filtered ->
        buildAlertFor(filtered)
    }

// Reified — no KClass needed
val result3 = OperationResult.of(patients, "patients")
    .addFromHavingAllExtensions<Patient, OperationOutcome>("http://ext/enrolled") { filtered ->
        buildSummary(filtered)
    }

// Named output key
val result4 = OperationResult.of(patients, "patients")
    .addFromHavingAllExtensions("enrolled-summary", Patient::class, "http://ext/enrolled") { filtered ->
        buildSummary(filtered)
    }
```

#### From all parameters, filtered by extension URL + value type or value predicate

These methods extend the URL-presence filters above by also inspecting the **value** of the matched extension. Two sub-families are available:

| Method | Filters by | Description |
|---|---|---|
| `addFromHavingExtensionWithValueType(type, url, valueType) { list -> R }` | URL + value type | Resource must have an extension at `url` with a value of `valueType`; single `R` result |
| `addFromHavingExtensionWithValueType(name, type, url, valueType) { list -> R }` | URL + value type | Same, explicit output `name` |
| `addAllFromHavingExtensionWithValueType(type, url, valueType) { list -> List<R> }` | URL + value type | Builder returns a list |
| `addAllFromHavingExtensionWithValueType(name, type, url, valueType) { list -> List<R> }` | URL + value type | Named list variant |
| `addFromHavingExtensionValueMatching(type, url, valueType, predicate) { list -> R }` | URL + typed predicate | Resource must have an extension at `url` with a value of `valueType` satisfying `predicate`; single `R` result |
| `addFromHavingExtensionValueMatching(name, type, url, valueType, predicate) { list -> R }` | URL + typed predicate | Same, explicit output `name` |
| `addAllFromHavingExtensionValueMatching(type, url, valueType, predicate) { list -> List<R> }` | URL + typed predicate | Builder returns a list |
| `addAllFromHavingExtensionValueMatching(name, type, url, valueType, predicate) { list -> List<R> }` | URL + typed predicate | Named list variant |

When the extension appears more than once at a URL, the resource is included if **any** of its values satisfies the condition. All unnamed variants have reified inline overloads. Named variants intentionally have no reified overload for the same reason as above.

`addFromHavingExtensionWithValueType` is a convenience shorthand for `addFromHavingExtensionValueMatching` with a trivially true predicate — prefer the latter when you need to inspect the value itself.

```kotlin
// Type check — Patients whose "enrolled" extension carries a StringType value
val result = OperationResult.of(patients, "patients")
    .addFromHavingExtensionWithValueType(Patient::class, "http://ext/enrolled", StringType::class) { filtered ->
        buildSummary(filtered)
    }

// Reified type check
val result2 = OperationResult.of(patients, "patients")
    .addFromHavingExtensionWithValueType<Patient, StringType, OperationOutcome>("http://ext/enrolled") { filtered ->
        buildSummary(filtered)
    }

// Value predicate — only Patients enrolled (StringType "true")
val result3 = OperationResult.of(patients, "patients")
    .addFromHavingExtensionValueMatching(Patient::class, "http://ext/enrolled", StringType::class, { it.value == "true" }) { filtered ->
        buildEnrolledSummary(filtered)
    }

// Reified predicate
val result4 = OperationResult.of(patients, "patients")
    .addFromHavingExtensionValueMatching<Patient, BooleanType, OperationOutcome>("http://ext/active", { it.booleanValue() }) { filtered ->
        buildActiveSummary(filtered)
    }
```

#### From all parameters, filtered by FHIRPath expression

`addFromMatching` and `addAllFromMatching` evaluate a FHIRPath expression against every accumulated resource of a given type and pass only those that evaluate to `true` to the builder. The expression is relative to each candidate resource (write `"active = true"`, not `"Patient.active = true"`).

| Method | Returns | Default key |
|---|---|---|
| `addFromMatching(name?, type, expression) { list -> R }` | `OperationResult<R>` | `type` simple name (lowercase) |
| `addAllFromMatching(name?, type, expression) { list -> List<R> }` | `OperationResult<List<R>>` | `type` simple name (lowercase) |

Both methods have reified Kotlin overloads (omit `type`) and generate `@JvmOverloads` for Java callers. Error handling follows Pattern A: malformed expressions produce an ERROR `OperationOutcome` and skip the head.

```kotlin
// Keep only active patients, then build a summary
val result = OperationResult.of(patients, "patients")
    .addFromMatching<Patient, OperationOutcome>(expression = "active = true") { active ->
        OperationOutcome().apply { addIssue().diagnostics = "${active.size} active patients" }
    }

// Multi-field condition — extension value AND regular field
val result2 = OperationResult.of(patients, "patients")
    .addAllFromMatching<Patient, Patient>(
        "high-risk",
        expression = "extension('http://ext/risk').value = 'high' and birthDate < @1960-01-01"
    ) { it }

// Named output key — KClass form (required when you also want an explicit name in Kotlin)
val result3 = OperationResult.of(patients, "patients")
    .addFromMatching("enrolled", Patient::class, "identifier.where(system='http://example.org/mpi').exists()") { filtered ->
        buildEnrolledSummary(filtered)
    }
```

#### Error-resilient variants

| Method | On exception | Returns |
|---|---|---|
| `addOrSkip(name?) { R }` | Records a warning `OperationOutcome`, skips the value | `OperationResult<T>` (unchanged head) |
| `addOrDefault(name?, default) { R }` | Records a warning, stores `default` instead | `OperationResult<R>` |

```kotlin
val result = OperationResult.of(patient)
    .addOrSkip("coverage") { fetchCoverageOrThrow() }   // failure adds a warning, pipeline continues
    .addOrDefault("score", defaultScore) { computeRiskScore() }
```

#### Retry on transient failures

`addWithRetry` / `addWithRetryUsing` call the builder lambda up to `maxAttempts` times with exponential backoff between attempts. On final failure an ERROR `OperationOutcome` is recorded (Pattern A — same as `add`). The `retryOn` predicate lets you restrict retries to specific exception types.

`addWithRetryUsing` passes the current pipeline head value `T` into the builder, matching the `addUsing` convention.

| Parameter | Type | Default | Meaning |
|---|---|---|---|
| `name` | `String?` | `null` | Key under which the value is stored (`fhirType()` if null) |
| `maxAttempts` | `Int` | `3` | Total number of attempts including the first; must be ≥ 1 |
| `initialDelayMs` | `Long` | `500` | Delay before the second attempt in ms; must be ≥ 0 |
| `retryOn` | `(Exception) -> Boolean` | `{ true }` | Return `false` to stop retrying for a specific exception |
| `builder` (`addWithRetry`) | `() -> R` | — | Resource-producing lambda |
| `builder` (`addWithRetryUsing`) | `(T) -> R` | — | Resource-producing lambda that receives the current head |

```kotlin
val result = OperationResult.of(patient)
    .addWithRetry("encounter", maxAttempts = 3, initialDelayMs = 200) {
        fhirClient.fetchEncounter(id)          // retried up to 3 times on any exception
    }
    .addWithRetryUsing("coverage", retryOn = { e -> e is IOException }) { p ->
        fhirClient.fetchCoverage(p.idPart)     // head Patient passed into the builder
    }
```

Delay schedule: `initialDelayMs` before attempt 2, `initialDelayMs * 2` before attempt 3, etc. Uses `Thread.sleep` (blocking); the async variant uses `kotlinx.coroutines.delay` (non-blocking).

#### Primitive value helpers

Store raw Kotlin/Java primitives as FHIR types without changing the pipeline head. These methods always return `OperationResult<T>` (the current head type is preserved) because primitives are metadata or configuration, not pipeline resources.

| Method | Kotlin type | FHIR wrapper | Using variant |
|---|---|---|---|
| `addString(name, value)` | `String` | `StringType` | `addStringUsing(name) { t -> String }` |
| `addBoolean(name, value)` | `Boolean` | `BooleanType` | `addBooleanUsing(name) { t -> Boolean }` |
| `addInteger(name, value)` | `Int` | `IntegerType` | `addIntegerUsing(name) { t -> Int }` |
| `addDecimal(name, value)` | `BigDecimal` | `DecimalType` | `addDecimalUsing(name) { t -> BigDecimal }` |
| `addCode(name, value)` | `String` | `CodeType` | `addCodeUsing(name) { t -> String }` |
| `addUri(name, value)` | `String` | `UriType` | `addUriUsing(name) { t -> String }` |
| `addDate(name, value)` | [`DateTimeInput`](#datetimeinput) | `DateType` | `addDateUsing(name) { t -> DateTimeInput }` |
| `addDateTime(name, value)` | [`DateTimeInput`](#datetimeinput) | `DateTimeType` | `addDateTimeUsing(name) { t -> DateTimeInput }` |
| `addInstant(name, value)` | [`DateTimeInput`](#datetimeinput) | `InstantType` | `addInstantUsing(name) { t -> DateTimeInput }` |
| `addTime(name, value)` | [`DateTimeInput`](#datetimeinput) | `TimeType` | `addTimeUsing(name) { t -> DateTimeInput }` |
| `addCanonical(name, value)` | `String` | `CanonicalType` | `addCanonicalUsing(name) { t -> String }` |

`addDate`, `addDateTime`, `addInstant`, and `addTime` each take a single [`DateTimeInput`](#datetimeinput) value that wraps any supported Java/Kotlin date-time type. `addDecimal` takes `java.math.BigDecimal` to avoid floating-point precision issues.

```kotlin
val result = OperationResult.of(patient)
    .addString("label", "priority")            // raw value — head stays Patient
    .addBoolean("active", true)
    .addStringUsing("patientId") { p -> p.idElement.idPart }  // derived from current result
    .add("encounter") { fetchEncounter() }     // head changes to Encounter

val encounter: Encounter = result.getResult()  // still typed correctly
```

#### Complex data type helpers

Construct and store common FHIR complex types without manually building HAPI objects. Like primitive helpers, these methods preserve the pipeline head type `T` and use Pattern B error handling (WARNING outcome, head preserved).

| Method | FHIR type | Key parameters |
|---|---|---|
| `addCoding(name, system, code, display?)` | `Coding` | `system`, `code`, optional `display` |
| `addReference(name, reference)` | `Reference` | `reference` string (e.g. `"Patient/123"`) |
| `addIdentifier(name, system, value)` | `Identifier` | `system`, `value` |
| `addPeriod(name, start?, end?)` | `Period` | nullable FHIR dateTime strings, or `LocalDateTime?` / `ZonedDateTime?` / `OffsetDateTime?` |
| `addQuantity(name, value, unit, system?, code?)` | `Quantity` | `BigDecimal` value, UCUM unit, optional system/code |
| `addCodeableConcept(name, system, code, display?, text?)` | `CodeableConcept` | coding fields plus optional free-text |

```kotlin
val result = OperationResult.of(patient)
    .addCoding("obs-code", "http://loinc.org", "8867-4", "Heart rate")
    .addReference("subject", "Patient/123")
    .addIdentifier("mrn", "http://hospital.org/mrn", "MRN-001")
    .addPeriod("admission", "2024-01-15", "2024-01-20")
    .addQuantity("weight", BigDecimal("70.5"), "kg", "http://unitsofmeasure.org", "kg")
    .addCodeableConcept("category", "http://snomed.info/sct", "413839001", "Chronic lung disease")

val p: Patient = result.getResult()  // head type unchanged
```

#### DateTimeInput

`DateTimeInput` is a sealed class that wraps any supported Java/Kotlin date-time value for use with `addDate`, `addDateTime`, `addInstant`, and `addTime`. It replaces the old per-type overload families, which could not be named uniformly because lambda return types share the same JVM signature after erasure.

| Subclass | Wrapped type |
|---|---|
| `DateTimeInput.OfString` | FHIR-format string (`"2024-01-15"`, `"10:30:00"`, …) |
| `DateTimeInput.OfLocalDate` | `java.time.LocalDate` |
| `DateTimeInput.OfLocalDateTime` | `java.time.LocalDateTime` |
| `DateTimeInput.OfZonedDateTime` | `java.time.ZonedDateTime` |
| `DateTimeInput.OfOffsetDateTime` | `java.time.OffsetDateTime` |
| `DateTimeInput.OfInstant` | `java.time.Instant` |
| `DateTimeInput.OfDate` | `java.util.Date` |
| `DateTimeInput.OfCalendar` | `java.util.Calendar` |
| `DateTimeInput.OfYearMonth` | `java.time.YearMonth` |
| `DateTimeInput.OfYear` | `java.time.Year` |
| `DateTimeInput.OfLocalTime` | `java.time.LocalTime` |

Use the `DateTimeInput.of(value)` factory (Kotlin and Java) or construct the subclass directly in Kotlin:

```kotlin
// Factory — same syntax from Kotlin and Java
DateTimeInput.of(LocalDate.of(1990, 6, 15))
DateTimeInput.of(ZonedDateTime.now())

// Direct subclass construction (Kotlin only)
DateTimeInput.OfLocalDate(LocalDate.of(1990, 6, 15))
```

Not every subclass is valid for every FHIR primitive type. Unsupported combinations (e.g. `OfInstant` passed to `addDate`) throw `IllegalArgumentException` at runtime.

---

#### FhirDateTimeConverter

`FhirDateTimeConverter` is a public Kotlin `object` in `dev.ratkay.operation.r4` that maps common Java/Kotlin date-time types to their HAPI FHIR R4 equivalents. All functions are pure and stateless — they can be used directly without an `OperationResult` pipeline.

Each typed overload converts directly; `DateTimeInput`-accepting overloads dispatch to the correct typed converter based on the wrapped subclass.

| Function | Input type(s) | HAPI FHIR result |
|---|---|---|
| `toFhirDate(value)` | `LocalDate` · `YearMonth` · `Year` · `java.util.Date` · `LocalDateTime` · `ZonedDateTime` · `OffsetDateTime` · `DateTimeInput` | `DateType` |
| `toFhirDateTime(value)` | `LocalDateTime` · `ZonedDateTime` · `OffsetDateTime` · `Instant` · `Date` · `Calendar` · `DateTimeInput` | `DateTimeType` |
| `toFhirInstant(value)` | `java.time.Instant` · `ZonedDateTime` · `OffsetDateTime` · `Date` · `DateTimeInput` | `InstantType` |
| `toFhirTime(value)` | `LocalTime` · `DateTimeInput` | `TimeType` |

**Conversion notes:**
- `LocalDate`, `YearMonth`, `Year` → no timezone (FHIR date is zone-agnostic)
- `LocalDateTime` → no timezone (FHIR allows zone-less dateTime); `toFhirDate` extracts the date part
- `ZonedDateTime` / `OffsetDateTime` → timezone offset preserved for dateTime/instant; `toFhirDate` extracts the local date in the given zone
- `Instant` / `java.util.Date` → converted to milliseconds, HAPI renders as UTC; `toFhirDateTime(Instant)` is UTC dateTime, `toFhirInstant(Instant)` is UTC instant

```kotlin
import dev.ratkay.operation.r4.FhirDateTimeConverter

// Standalone usage
val fhirDate    = FhirDateTimeConverter.toFhirDate(LocalDate.of(1990, 6, 15))   // DateType "1990-06-15"
val fhirDt      = FhirDateTimeConverter.toFhirDateTime(ZonedDateTime.now())      // DateTimeType with offset
val fhirInstant = FhirDateTimeConverter.toFhirInstant(Instant.now())             // InstantType UTC
val fhirTime    = FhirDateTimeConverter.toFhirTime(LocalTime.of(10, 30))         // TimeType "10:30"

// Inside an OperationResult pipeline — wrap values with DateTimeInput.of(...)
val result = OperationResult.of(patient)
    .addDate("dob",      DateTimeInput.of(LocalDate.of(1990, 6, 15)))
    .addDateTime("ts",   DateTimeInput.of(ZonedDateTime.now()))
    .addInstant("audit", DateTimeInput.of(Instant.now()))
    .addTime("slot",     DateTimeInput.of(LocalTime.of(10, 30)))
    .addPeriod("coverage", ZonedDateTime.now(), ZonedDateTime.now().plusYears(1))

// Builder (Using) variants
val result2 = OperationResult.of(patient)
    .addDateUsing("dob")      { DateTimeInput.of(it.birthDate.toInstant().atZone(ZoneOffset.UTC).toLocalDate()) }
    .addDateTimeUsing("ts")   { DateTimeInput.of(ZonedDateTime.now()) }
    .addInstantUsing("audit") { DateTimeInput.of(Instant.now()) }
    .addTimeUsing("slot")     { DateTimeInput.of(LocalTime.of(9, 0)) }
```

#### FhirExtensionHelper

`FhirExtensionHelper` is a public Kotlin `object` in `dev.ratkay.operation.r4` for retrieving FHIR extensions from any object that can carry them. All methods search **at every level** of the FHIR object graph — not just the top-level `.extension` list of the source — by recursively traversing FHIR child elements.

Any object implementing `IBaseHasExtensions` is a valid source: FHIR resources (`Patient`, `Observation`, …), all primitive types (`StringType`, `BooleanType`, …), all complex datatypes (`Coding`, `Reference`, …), and `Extension` itself (for nested sub-extensions).

Each retrieval method has two flavours:
- **Kotlin nullable** — returns `T?`; idiomatic for Kotlin callers.
- **Java Optional / `Class<T>`** — returns `Optional<T>` or takes a `Class<T>` parameter; idiomatic for Java callers.

| Method | Returns | Description |
|---|---|---|
| `hasExtension(source, url)` | `Boolean` | Deep presence check |
| `getByUrl(source, url)` | `Extension?` | First match at any depth |
| `getByUrlOptional(source, url)` | `Optional<Extension>` | Java-friendly alias for `getByUrl` |
| `getAllByUrl(source, url)` | `List<Extension>` | All matches at any depth |
| `getValueAs<T>(source, url)` | `T?` | Typed value of first match; Kotlin reified |
| `getValueAsOptional(source, url, Class<T>)` | `Optional<T>` | Java-friendly; pass `StringType::class.java` |
| `getAllValuesAs<T>(source, url)` | `List<T>` | All typed values; Kotlin reified |
| `getAllValuesAs(source, url, Class<T>)` | `List<T>` | Java-friendly overload |
| `getNested(source, url, nestedUrl)` | `Extension?` | First nested sub-extension at any depth |
| `getNestedOptional(source, url, nestedUrl)` | `Optional<Extension>` | Java-friendly alias for `getNested` |
| `getAllNested(source, url, nestedUrl)` | `List<Extension>` | All nested sub-extensions at any depth |

```kotlin
import dev.ratkay.operation.r4.FhirExtensionHelper

val patient = Patient().apply {
    // Extension lives on the nested HumanName element, not on Patient directly
    addName().addExtension("http://example.com/nid", StringType("12345"))
    // Complex nested extension (sub-extensions instead of a single value)
    addExtension(Extension("http://example.com/address-info").apply {
        addExtension("http://example.com/city",    StringType("Oslo"))
        addExtension("http://example.com/country", StringType("NO"))
    })
}

// Deep retrieval — finds extension on the nested HumanName
val nid: StringType? = FhirExtensionHelper.getValueAs<StringType>(patient, "http://example.com/nid")
// nid?.value == "12345"

// Nested sub-extension
val city: StringType? = FhirExtensionHelper.getValueAs<StringType>(
    FhirExtensionHelper.getNested(patient, "http://example.com/address-info", "http://example.com/city")!!,
    "http://example.com/city"
)

// Java-friendly Optional variants
val nidOpt: Optional<StringType> =
    FhirExtensionHelper.getValueAsOptional(patient, "http://example.com/nid", StringType::class.java)

val cityExt: Optional<Extension> =
    FhirExtensionHelper.getNestedOptional(patient, "http://example.com/address-info", "http://example.com/city")
```

#### Nested parameters (parts)

`addPart` builds nested FHIR `Parameters.part` structures using a sub-pipeline builder lambda, without relying on dot-delimited key names in the parameter map.

```kotlin
fun addPart(name: String, builder: OperationResult<T>.() -> OperationResult<*>): OperationResult<T>
```

The sub-pipeline receives its own empty parameter map so the parent map is not polluted. Entries produced by the builder are prefixed with `"name."` and stored in the parent map, where `toParameters()` reconstructs the nested `part` structure automatically. The pipeline head type `T` is preserved. Outcomes/errors from the sub-pipeline are not propagated to the parent.

```kotlin
val result = OperationResult.of(patient)
    .addPart("address") {
        addString("city", "Springfield")
            .addString("country", "US")
    }

// toParameters() produces:
// Parameters
//   parameter: name="address"
//     part: name="city",    valueString="Springfield"
//     part: name="country", valueString="US"
```

Parts can be nested arbitrarily deep:

```kotlin
val result = OperationResult.of(patient)
    .addPart("outer") {
        addPart("inner") {
            addString("leaf", "deep")
        }
    }

// toParameters() produces:
// Parameters
//   parameter: name="outer"
//     part: name="inner"
//       part: name="leaf", valueString="deep"
```

#### Extensions on parameters

`addWithExtension` attaches one or more FHIR `Extension` objects to an individual parameter entry. When serialized via `toParameters()`, the extensions are emitted on the corresponding `ParametersParameterComponent`.

```kotlin
fun <R : Base> addWithExtension(
    name: String,
    value: R,
    vararg exts: Extension
): OperationResult<T>
```

The pipeline head type `T` is preserved (behaves like `addOrSkip`). An `IdentityHashMap` is used internally so that two distinct instances that are `.equals()` each keep independent extension lists.

```kotlin
val flagExt = Extension("http://example.com/flag", BooleanType(true))

val result = OperationResult.of(patient)
    .addWithExtension("status", StringType("active"), flagExt)

// toParameters() produces:
// Parameters
//   parameter: name="status", valueString="active"
//     extension: url="http://example.com/flag", valueBoolean=true
```

Multiple extensions are supported:

```kotlin
val result = OperationResult.of(patient)
    .addWithExtension(
        "obs",
        CodeType("8867-4"),
        Extension("http://example.com/ext1", StringType("v1")),
        Extension("http://example.com/ext2", StringType("v2"))
    )
```

Extensions on dot-delimited (nested) keys work correctly — `buildParameterComponents` tracks the full key path during recursion and attaches extensions to the appropriate nested part.

> **Note:** Extensions on resource parameters (where `setResource` is used instead of `setValue`) are not captured by `fromParameters()` — the resource already carries its own extension list natively. Extensions on value parameters round-trip correctly.

### Error Strategy

`OperationResult` supports two strategies, set at construction time via `of()`:

| Strategy | Behaviour on failure in `add` / `addUsing` / etc. |
|---|---|
| `FAIL_FAST` (default) | Records the error `OperationOutcome` and skips all subsequent builder steps |
| `ACCUMULATE` | Records the error and continues executing subsequent steps |

```kotlin
val result = OperationResult.of(patient, errorStrategy = ErrorStrategy.ACCUMULATE)
    .add { riskyStep() }      // failure recorded but next step still runs
    .add { anotherStep() }

result.hasErrors()            // true if any step failed
result.isSuccessful()         // true if no failures
result.getOutcomes()          // List<OperationOutcome> — one per failure
result.toOperationOutcome()   // merged OperationOutcome with all issues
```

### Query Methods

```kotlin
result.getResult()               // T — most recently added value (throws if none)
result.getResultList()           // List<R> — when T is List<R> (after addAll/addAllUsing)
result.getAllParameters()         // Map<String, List<Base>> — full snapshot
result.getAll("patient")         // List<Base> for a specific key (empty if absent)

// Type-safe lookups — reified overloads avoid KClass arguments
result.getByType<Patient>()      // all Patient instances across all keys
result.getByType(Patient::class) // same, explicit KClass form

result.containsKey("patient")    // Boolean
result.getKeys()                 // Set<String>
result.count("patient")          // Int — entries under that key
result.totalCount()              // Int — all entries across all keys
result.isEmpty()
result.isNotEmpty()
```

### Functional Transformations

These return a new `OperationResult` without modifying the original.

#### Filtering

```kotlin
// Reified overloads — no KClass argument needed
result.filterByType<Patient>()         // keep only entries whose values are Patients
result.filterByType(Patient::class)    // same, explicit form

result.filterByName("patient")         // keep only the "patient" key
```

#### Mapping and chaining

```kotlin
// Type-safe map — transforms every value across all keys, changes pipeline head type
val mapped: OperationResult<Appointment> = result.mapValues { base -> toAppointment(base) }

// flatMap — chain an inner pipeline; all its parameter entries are merged into the outer map
val combined: OperationResult<Coverage> = OperationResult.of(patient)
    .flatMap { p ->
        OperationResult.of(coverage(p), "coverage")
            .add("encounter") { lookupEncounter(p) }
    }
// outer map now contains: patient, coverage, encounter

// mapHead — change the pipeline head type WITHOUT adding an entry to the parameter map
val withCoverage: OperationResult<Coverage> = OperationResult.of(patient, "patient")
    .mapHead { p -> resolveCoverage(p) }   // head is now Coverage; "patient" key unchanged
    .add("coverage") { it }                 // now store it explicitly if desired

// mapHeadUsing — same but with the head exposed as `this` in the lambda
val withAppt: OperationResult<Appointment> = OperationResult.of(patient, "patient")
    .mapHeadUsing { buildAppointment(this) }

// merge — combine two OperationResults; overlapping keys accumulate their values
val merged = resultA.merge(resultB)   // OperationResult<T> — A's head type preserved
```

#### Structural edits

```kotlin
result.remove("coverage")             // new OperationResult without the "coverage" key (no-op if absent)
result.rename("old", "new")           // move all values from "old" to "new"
```

#### Side effects (peek)

```kotlin
result.peek { map ->                  // inspect the map without modifying the pipeline
    log.debug("keys: {}", map.keys)
}
```

#### Conditional chaining

| Method | Condition | On exception |
|--------|-----------|--------------|
| `whenTrue(condition) { ... }` | Runs block and merges its parameters when `condition` is `true`; returns `OperationResult<T>` unchanged otherwise | WARNING outcome, state preserved |
| `ifPresent { t -> ... }` | Runs block with the typed head value when it is non-null; skips when head is `null` | WARNING outcome, state preserved |
| `guardFalse(condition, message)` | When `condition` is `false`, records a WARNING `OperationOutcome` with `IssueType.BUSINESSRULE` and the given `message`; pipeline continues | n/a — no block |

```kotlin
// Only add an encounter if the patient is active
OperationResult.of(patient)
    .guardFalse(patient.active, "Patient is not active — skipping encounter")
    .whenTrue(patient.hasGeneralPractitioner()) {
        add("gp") { resolveGp(patient) }
    }
    .ifPresent { p ->
        OperationResult.of(buildEncounter(p), "encounter")
    }
```

All three methods preserve the head type `T`. The block passed to `whenTrue` and `ifPresent` may contain any pipeline operations including head-changing ones — the outer head is always restored after the block completes.

#### FHIRPath conditional chaining and extraction

Three FHIRPath-aware methods complement the existing conditional API. All expressions are relative to the current head resource (write `"active = true"`, not `"Patient.active = true"`).

| Method | Head requirement | Behaviour |
|---|---|---|
| `whenPath(expression) { ... }` | Must be a `Base`; silent skip if null/non-Base | Runs block and merges parameters when expression is truthy; WARNING on malformed expression |
| `guardPath(expression, message)` | Must be a `Base`; WARNING if null/non-Base | Records WARNING `OperationOutcome` with `message` when expression is `false`; WARNING on malformed expression |
| `selectByPath(type, expression, name?)` | Must be a `Base`; ERROR if null/non-Base | Evaluates expression, promotes first matching result of `type` to the new pipeline head, stores under `name`; ERROR if no match found |

```kotlin
val result = OperationResult.of(patient)
    .guardPath("active = true", "Patient must be active")
    .whenPath("identifier.where(system='http://example.org/mpi').exists()") {
        add("mpi-flag") { StringType("enrolled") }
    }
    .selectByPath<HumanName>("name.where(use='official').first()", "official-name")
```

`selectByPath` has a reified Kotlin overload (`selectByPath<HumanName>("name.first()")`). Error handling for `whenPath` and `guardPath` follows Pattern B (head preserved); `selectByPath` follows Pattern A (head skipped on failure).

#### Convenience lookups

```kotlin
result.takeFirst("patient")                       // Base? — first value under "patient"
result.takeFirstTyped("patient", Patient::class)  // Patient? — first Patient under "patient"
result.takeFirstTyped<Patient>("patient")         // same, reified form
```

### Logging and Metrics

FHIRMason logs pipeline activity via SLF4J (no binding included — add your own). Three log levels are used:

| Level | When |
|---|---|
| `DEBUG` | Every step: name, FHIR type, and duration |
| `TRACE` | Parameter map state (key names and counts) after each step |
| `WARN`  | When `addOrSkip` / `addOrDefault` catches an exception |

Log format:
```
FHIRMason | step='coverage' | type=Coverage | duration=45ms
FHIRMason | state: {patient=1, coverage=1}
FHIRMason | step='risky' | WARN: connection timed out
```

#### Per-step metrics (`timed()` / `getMetrics()`)

Call `timed()` anywhere in the chain to enable `StepMetrics` collection. When disabled (the default), `getMetrics()` returns an empty list — zero overhead.

```kotlin
val result = OperationResult.of(patient)
    .timed()                                      // enable metrics collection
    .add("coverage") { fetchCoverage() }
    .add("encounter") { fetchEncounter() }

result.getMetrics().forEach { m ->
    println("${m.stepName}: ${m.durationMs}ms  success=${m.success}  type=${m.resourceType}")
}
// coverage: 38ms  success=true  type=Coverage
// encounter: 12ms  success=true  type=Encounter
```

`StepMetrics` fields: `stepName`, `resourceType`, `durationMs`, `success`.

#### Async logging and metrics

`AsyncOperationResult` logs task lifecycle events and DAG completion:

```
FHIRMason.async | task='patient'   | status=STARTED
FHIRMason.async | task='patient'   | status=COMPLETED | duration=120ms
FHIRMason.async | task='coverage'  | status=COMPLETED | duration=85ms
FHIRMason.async | dag=COMPLETED    | totalDuration=135ms | tasks=2
```

```kotlin
val dag = AsyncOperationResult()
    .timed()
    .add("patient")  { fetchPatient() }
    .add("coverage") { fetchCoverage() }

dag.runBlocking()

dag.getMetrics()           // Map<String, StepMetrics> keyed by task name
dag.getTotalDuration()     // wall-clock DAG execution time in ms
```

### Reference Linking

`linkReferences` wires FHIR references between accumulated resources and returns a **new** `OperationResult` — the original resources are never mutated (deep copy is performed before modification).

#### Automatic (HAPI introspection)

Scans every resource's child properties via HAPI's `Base.children()` API, detects unset `Reference(...)` fields, and wires them when exactly one unambiguous candidate exists in the map. Ambiguous cases (multiple candidates for the same field) are silently skipped.

```kotlin
val result = OperationResult.of(patient("p1"))
    .add { encounter("e1") }
    .add { observation("obs1") }
    .linkReferences()

// encounter.subject    → Reference("Patient/p1")   — one Patient, unambiguous
// observation.subject  → Reference("Patient/p1")
// observation.encounter → Reference("Encounter/e1")
```

#### Explicit rules

For precise control, supply one or more `ReferenceLinkRule` instances. Each rule requires exactly one target resource (throws `IllegalArgumentException` if multiple targets exist); multiple sources are fine — all receive the reference.

```kotlin
val encounterPatientRule = ReferenceLinkRule(
    sourceType = Encounter::class,
    targetType = Patient::class,
    setter     = { enc, pat -> enc.subject = Reference("Patient/${pat.idPart}") }
)
val observationEncounterRule = ReferenceLinkRule(
    sourceType = Observation::class,
    targetType = Encounter::class,
    setter     = { obs, enc -> obs.encounter = Reference("Encounter/${enc.idPart}") }
)

val result = OperationResult.of(patient)
    .add { encounter }
    .add { observation }
    .linkReferences(encounterPatientRule, observationEncounterRule)
```

### Error Handling

Every builder step (`add`, `addAll`, `addUsing`, etc.) catches exceptions internally. When an exception is thrown:

- The exception is converted to an `OperationOutcome` with `ERROR` severity and added to the outcome list.
- If the caught exception is a HAPI FHIR `BaseServerResponseException` that already carries an embedded `OperationOutcome`, **that outcome is preserved** rather than replaced with a generic one. Rich diagnostic information from upstream FHIR servers or clients survives the pipeline intact.
- In `FAIL_FAST` mode subsequent steps are skipped; in `ACCUMULATE` mode they continue.

```kotlin
// Embedded OperationOutcome from HAPI FHIR exceptions is preserved in full
val result = OperationResult.of(patient, errorStrategy = ErrorStrategy.ACCUMULATE)
    .add("encounter") { fhirClient.read(Encounter::class.java, encounterId) }  // may throw BaseServerResponseException
    .add("coverage")  { fhirClient.read(Coverage::class.java, coverageId) }

result.hasErrors()           // true when any step threw
result.getOutcomes()         // List<OperationOutcome> — one per failing step
result.toOperationOutcome()  // single merged OperationOutcome with all issues
```

#### Graceful degradation

| Method | On error | Result type |
|---|---|---|
| `addOrSkip(name?) { R }` | skips step, records WARNING outcome | `OperationResult<T>` (unchanged head) |
| `addOrDefault(name?, default) { R }` | uses `default`, records WARNING outcome | `OperationResult<R>` |

#### Surfacing errors as HAPI FHIR exceptions

`throwIfErrors()` is a fluent terminal step that throws `InternalErrorException` (with the merged `OperationOutcome` attached) when the pipeline has errors, and returns `this` unchanged when there are none:

```kotlin
fun summary(@IdParam id: IdType): Parameters =
    OperationResult.of(fetchPatient(id))
        .add("encounter") { fetchEncounter(id) }
        .add("coverage")  { fetchCoverage(id) }
        .throwIfErrors()   // throws InternalErrorException if any step failed
        .toParameters()
```

### Output

#### FHIR Parameters

```kotlin
val params: Parameters = result.toParameters()
```

Nested parameter structure is fully preserved on round-trips. Parameters with nested `part` entries are flattened to composite `"parent.child"` keys internally; `toParameters()` reconstructs the original nesting.

```kotlin
// Round-trip — nested structure is preserved
val result = OperationResult.fromParameters(original)
result.toParameters()   // structurally identical to `original`
```

#### FHIR Bundle

```kotlin
result.toTransactionBundle()          // Bundle (type = TRANSACTION), PUT/POST requests added per resource
result.toBatchBundle()                // Bundle (type = BATCH)
result.toBundle(Bundle.BundleType.COLLECTION)
result.toBundle(Bundle.BundleType.SEARCHSET) { entry ->
    entry.search.mode = Bundle.SearchEntryMode.MATCH  // optional entry config block
}
```

#### Error Terminal

```kotlin
result.throwIfErrors()  // returns this unchanged, or throws InternalErrorException if any step failed
```

### Constructing from FHIR Resources

```kotlin
// From a Parameters resource
val result = OperationResult.fromParameters(parameters)

// Typed head — getResult() returns a Patient without casting
val result = OperationResult.fromParametersTyped<Patient>(parameters, primaryKey = "patient")

// From a Bundle — keys default to fhirType().lowercase()
val result = OperationResult.fromBundle(bundle)

// Custom key strategy
val result = OperationResult.fromBundle(bundle) { entry ->
    entry.fullUrl ?: entry.resource.fhirType().lowercase()
}
```

#### Extracting typed values from parameters

After loading a `Parameters` resource with `fromParameters`, use `extractParam` or `extractParamList` to pull a named value out of the internal map and set it as the new pipeline head for downstream processing.

| Method | Signature | Behaviour on missing / wrong type |
|---|---|---|
| `extractParam` | `extractParam<R>(name)` | Throws `IllegalArgumentException` |
| `extractParamList` | `extractParamList<R>(name)` | Returns empty list |

```kotlin
// Pull a single typed value and continue the pipeline
val result = OperationResult.fromParameters(input)
    .extractParam<Patient>("patient")
    .addUsing("encounter") { patient -> lookupEncounter(patient) }

// Pull all values of a given type stored under one key
val result = OperationResult.fromParameters(input)
    .extractParamList<Observation>("observations")

// Both preserve the full parameters map — only the pipeline head changes
result.containsKey("patient")       // true
result.containsKey("observations")  // true
```

---

## AsyncOperationResult (Async / DAG)

`AsyncOperationResult` builds a task graph where each task is keyed by name. Tasks with no dependencies run in parallel; tasks that declare dependencies wait only for those specific tasks. Cycle detection happens at registration time.

### Registration Methods

#### Root tasks (no dependencies)

```kotlin
AsyncOperationResult()
    .add("patient") { fetchPatient() }        // suspending lambda → single Base
    .addList("observations") { fetchObs() }   // suspending lambda → List<Base>
```

#### Dependent tasks — raw Map API

For tasks with multiple dependencies, the lambda receives a `Map<String, List<Base>>` containing the results of all declared dependencies.

```kotlin
.addAfter("claim", "patient", "coverage") { deps ->
    val patient  = deps["patient"]!!.filterIsInstance<Patient>().first()
    val coverage = deps["coverage"]!!.filterIsInstance<Coverage>().first()
    buildClaim(patient, coverage)
}

.addListAfter("items", "claim") { deps ->
    val claim = deps["claim"]!!.filterIsInstance<Claim>().first()
    buildLineItems(claim)
}
```

#### Dependent tasks — type-safe single-dependency overloads

When a task has exactly one dependency, use the typed overloads to skip the manual map lookup.

| Method | Lambda receives | Returns |
|---|---|---|
| `addAfter(key, dep, type) { t -> Base }` | single typed value | `Base` |
| `addListAfter(key, dep, type) { t -> List<Base> }` | single typed value | `List<Base>` |
| `addAfterAll(key, dep, type) { list -> Base }` | typed list | `Base` |
| `addListAfterAll(key, dep, type) { list -> List<Base> }` | typed list | `List<Base>` |
| `addWithRetry(key, …) { Base }` | none | `Base` |
| `addAfterWithRetry(key, *deps, …) { Map → Base }` | dep map | `Base` |
| `addListAfterWithRetry(key, *deps, …) { Map → List<Base> }` | dep map | `List<Base>` |
| `addAfterWithRetry(key, dep, type, …) { t -> Base }` | single typed value | `Base` |
| `addListAfterWithRetry(key, dep, type, …) { t -> List<Base> }` | single typed value | `List<Base>` |
| `addAfterAllWithRetry(key, dep, type, …) { list -> Base }` | typed list | `Base` |
| `addListAfterAllWithRetry(key, dep, type, …) { list -> List<Base> }` | typed list | `List<Base>` |
| `addWithTimeout(key, timeoutMs) { Base }` | none | `Base` |
| `addListWithTimeout(key, timeoutMs) { List<Base> }` | none | `List<Base>` |
| `addAfterWithTimeout(key, *deps, timeoutMs) { Map → Base }` | dep map | `Base` |
| `addListAfterWithTimeout(key, *deps, timeoutMs) { Map → List<Base> }` | dep map | `List<Base>` |
| `addAfterWithTimeout(key, dep, type, timeoutMs) { t -> Base }` | single typed value | `Base` |
| `addListAfterWithTimeout(key, dep, type, timeoutMs) { t -> List<Base> }` | single typed value | `List<Base>` |
| `addAfterAllWithTimeout(key, dep, type, timeoutMs) { list -> Base }` | typed list | `Base` |
| `addListAfterAllWithTimeout(key, dep, type, timeoutMs) { list -> List<Base> }` | typed list | `List<Base>` |
| `addIf(condition, key) { Base }` | none | `Base` (or no-op) |
| `addListIf(condition, key) { List<Base> }` | none | `List<Base>` (or no-op) |
| `addWithDefault(key, default) { Base }` | none | `Base` |
| `addListWithDefault(key, default) { List<Base> }` | none | `List<Base>` |
| `merge { AsyncOperationResult() … }` | — (lambda builds inner DAG) | `this` |
| `merge(other: AsyncOperationResult)` | — (pre-built inner DAG) | `this` |

```kotlin
AsyncOperationResult()
    .add("patient") { fetchPatient() }
    .addList("observations") { fetchObservations() }
    // receives the first Patient stored under "patient"
    .addAfter("encounter", "patient", Patient::class) { patient ->
        lookupEncounter(patient)
    }
    // receives all Observation instances stored under "observations"
    .addAfterAll("summary", "observations", Observation::class) { observations ->
        buildSummary(observations)
    }
```

### Execution

```kotlin
// From a suspend context
val result: OperationResult<Base> = asyncResult.run()

// From a blocking context (e.g. tests, CLI entry points)
val result: OperationResult<Base> = asyncResult.runBlocking()
```

The returned `OperationResult<Base>` supports all the same query, transformation, serialisation, and reference-linking methods as the synchronous builder.

### DAG inspection (`describe`)

Before executing, call `describe()` to get a human-readable snapshot of the registered task graph — tasks grouped into parallel execution tiers, with each tier's dependency set listed.

```kotlin
val dag = AsyncOperationResult()
    .add("patient") { fetchPatient() }
    .add("coverage") { fetchCoverage() }
    .addAfter("appointment", "patient") { _ -> buildAppointment() }
    .addAfter("claim", "appointment", "coverage") { _ -> buildClaim() }

println(dag.describe())
```

Output:
```
AsyncOperationResult DAG:
  Tier 1 (parallel): [patient, coverage]
  Tier 2 (parallel): [appointment] → depends on [patient]
  Tier 3 (parallel): [claim] → depends on [appointment, coverage]
```

Tier assignment: `tier(task) = 1` for root tasks; `tier(task) = 1 + max(tier(dep))` for dependent tasks. All tasks in the same tier can execute in parallel.

Returns `"AsyncOperationResult DAG: (empty)"` when no tasks have been registered.

### Retry on transient failures

`addWithRetry` works the same as in the sync builder but the lambda is `suspend` and delays use `kotlinx.coroutines.delay` (non-blocking).

```kotlin
val dag = AsyncOperationResult()
    .add("patient") { fetchPatient() }
    .addWithRetry("encounter", maxAttempts = 3, initialDelayMs = 200) {
        fhirClient.fetchEncounter(id)
    }
    .addWithRetry("coverage", retryOn = { e -> e is IOException }) {
        fhirClient.fetchCoverage(id)
    }

val result = dag.run()
```

On final failure the key is absent from the result and an ERROR `OperationOutcome` is recorded. See the sync builder section for the full parameter table.

### Retry for dependent tasks

`addAfterWithRetry` / `addListAfterWithRetry` bring the same exponential-backoff retry behaviour to dependent tasks. The block receives the resolved dependency map on every attempt.

| Parameter | Type | Default | Meaning |
|---|---|---|---|
| `key` | `String` | — | Storage key for the result |
| `deps` | `vararg String` | — | Dependency keys passed to the block |
| `maxAttempts` | `Int` | `3` | Total attempts including the first; must be ≥ 1 |
| `initialDelayMs` | `Long` | `500` | Delay before the second attempt in ms; must be ≥ 0 |
| `retryOn` | `(Exception) -> Boolean` | `{ true }` | Return `false` to stop retrying for a specific exception |
| `block` | `suspend (Map<String, List<Base>>) -> Base` | — | Suspending lambda; receives dependency results |

```kotlin
val dag = AsyncOperationResult()
    .add("patient") { fetchPatient() }
    .addAfterWithRetry("encounter", "patient", maxAttempts = 3, initialDelayMs = 200) { deps ->
        val p = deps["patient"]!!.first() as Patient
        fhirClient.fetchEncounter(p.idPart)   // retried up to 3 times on any exception
    }
    .addListAfterWithRetry("observations", "patient", retryOn = { e -> e is IOException }) { deps ->
        val p = deps["patient"]!!.first() as Patient
        fhirClient.fetchObservations(p.idPart)
    }

val result = dag.run()
```

When a dependency fails the retry block never runs — the dependent task is skipped with a dependency-failure `OperationOutcome`, identical to `addAfter`.

#### Type-safe single-dependency overloads

When a task has exactly one dependency, use the typed overloads to avoid the manual map lookup and cast. The compiler extracts the first matching instance (or the full typed list for `addAfterAllWithRetry` / `addListAfterAllWithRetry`) and passes it directly to the block.

| Method | Lambda receives |
|---|---|
| `addAfterWithRetry(key, dep, type, …) { t -> Base }` | single typed value |
| `addListAfterWithRetry(key, dep, type, …) { t -> List<Base> }` | single typed value |
| `addAfterAllWithRetry(key, dep, type, …) { list -> Base }` | typed list |
| `addListAfterAllWithRetry(key, dep, type, …) { list -> List<Base> }` | typed list |

```kotlin
val dag = AsyncOperationResult()
    .add("patient") { fetchPatient() }
    .addAfterWithRetry("encounter", "patient", Patient::class, maxAttempts = 3, initialDelayMs = 200) { patient ->
        fhirClient.fetchEncounter(patient.idPart)   // no cast needed
    }
    .addListAfterWithRetry("observations", "patient", Patient::class) { patient ->
        fhirClient.fetchObservations(patient.idPart)
    }

val result = dag.run()
```

### Per-task timeout

Wrap any task in a coroutine timeout. If the block does not finish within the deadline, the key is absent from the final result and an ERROR `OperationOutcome` carrying the timeout diagnostics is recorded. Downstream tasks that depend on a timed-out key are skipped.

| Method | Lambda input | Result |
|---|---|---|
| `addWithTimeout(key, timeoutMs) { Base }` | none | `Base` |
| `addListWithTimeout(key, timeoutMs) { List<Base> }` | none | `List<Base>` |
| `addAfterWithTimeout(key, *deps, timeoutMs) { Map → Base }` | dep map | `Base` |
| `addListAfterWithTimeout(key, *deps, timeoutMs) { Map → List<Base> }` | dep map | `List<Base>` |

```kotlin
val dag = AsyncOperationResult()
    .addWithTimeout("patient", timeoutMs = 2_000) { fetchPatient() }
    .addAfterWithTimeout("encounter", "patient", timeoutMs = 1_000) { deps ->
        val p = deps["patient"]!!.first() as Patient
        fhirClient.fetchEncounter(p.idPart)
    }

val result = dag.run()
if (result.hasErrors()) {
    // one or more tasks timed out
}
```

#### Type-safe single-dependency overloads

When a task has exactly one dependency, use the typed overloads to avoid the manual map lookup and cast.

| Method | Lambda receives |
|---|---|
| `addAfterWithTimeout(key, dep, type, timeoutMs) { t -> Base }` | single typed value |
| `addListAfterWithTimeout(key, dep, type, timeoutMs) { t -> List<Base> }` | single typed value |
| `addAfterAllWithTimeout(key, dep, type, timeoutMs) { list -> Base }` | typed list |
| `addListAfterAllWithTimeout(key, dep, type, timeoutMs) { list -> List<Base> }` | typed list |

```kotlin
val dag = AsyncOperationResult()
    .addWithTimeout("patient", timeoutMs = 2_000) { fetchPatient() }
    .addAfterWithTimeout("encounter", "patient", Patient::class, timeoutMs = 1_000) { patient ->
        fhirClient.fetchEncounter(patient.idPart)   // no cast needed
    }
    .addListAfterAllWithTimeout("relatedPersons", "patients", Patient::class, timeoutMs = 500) { patients ->
        patients.flatMap { fhirClient.fetchRelatedPersons(it.idPart) }
    }

val result = dag.run()
```

### Conditional task registration

`addIf` / `addListIf` register a task only when a boolean condition is true. When the condition is false the method is a no-op — the key is never registered. This avoids the awkward `if (condition) dag.add(...) else dag` pattern.

```kotlin
val dag = AsyncOperationResult()
    .add("patient") { fetchPatient() }
    .addIf(includeAppointments, "appointment") { fetchAppointment() }
    .addListIf(includeMedications, "medications") { fetchMedications() }

val result = dag.run()
```

> A key skipped by `addIf(false, …)` is never registered. Referencing it as a dependency in `addAfter` will throw `IllegalArgumentException` at registration time.

### Fallback values

`addWithDefault` / `addListWithDefault` silently substitute a fallback value when the block throws, rather than leaving the key absent. The task is treated as fully successful — no `OperationOutcome` is added and downstream tasks that depend on the key will run, receiving the fallback value.

```kotlin
val dag = AsyncOperationResult()
    .addWithDefault("coverage", emptyCoverage) { fetchCoverage() }   // fallback if fetch fails
    .addListWithDefault("medications", emptyList()) { fetchMedications() }
    .addAfter("summary", "coverage") { deps ->
        // always runs — "coverage" is always present (real value or fallback)
        buildSummary(deps["coverage"]!!.first() as Coverage)
    }

val result = dag.run()
```

A WARN log line is emitted when the fallback is used.

### DAG-level execution timeout

`timeout(durationMs)` sets a maximum wall-clock time for the entire `run()` invocation. If the DAG has not finished within the deadline, `run()` returns immediately with a single `TIMEOUT`-coded `OperationOutcome` and no task results. `runBlocking()` inherits this timeout.

```kotlin
val result = AsyncOperationResult()
    .timeout(5_000)                            // entire DAG must finish within 5 s
    .add("patient") { fetchPatient() }
    .add("coverage") { fetchCoverage() }
    .run()

if (result.hasErrors()) {
    // check outcome diagnostics — "DAG execution exceeded timeout of 5000ms"
}
```

Note: `getTotalDuration()` returns `0` after a DAG-level timeout because the duration assignment inside the execution body is interrupted before it can run.

### Executor / Thread Pool

By default, `run()` and `runBlocking()` dispatch all tasks on `Dispatchers.IO` — a shared pool of up to 64 threads designed for blocking I/O. This ensures independent tasks always execute in parallel, even when called from a single-threaded context like Java's `runBlocking()`.

Call `withExecutor(Executor)` to replace the default with a custom thread pool. `withExecutor` accepts any `java.util.concurrent.Executor` — no coroutine imports are required from the caller.

```kotlin
// Default — Dispatchers.IO is used automatically; no withExecutor needed
val result = AsyncOperationResult()
    .add("patient") { fhirClient.fetchPatient(id) }   // runs on IO pool
    .add("coverage") { fhirClient.fetchCoverage(id) }  // runs in parallel on IO pool
    .run()

// Custom bounded pool
val pool = Executors.newFixedThreadPool(4)
val result = AsyncOperationResult()
    .withExecutor(pool)
    .add("patient") { fetchPatient() }
    .run()
```

```java
// Java — default Dispatchers.IO gives true parallelism from runBlocking()
OperationResult<Base> result = new AsyncOperationResult()
    .add("patient", () -> fetchPatient())
    .add("coverage", () -> fetchCoverage())
    .runBlocking();                         // tasks run in parallel on IO pool

// Java — override with a bounded pool
ExecutorService pool = Executors.newFixedThreadPool(4);
OperationResult<Base> result = new AsyncOperationResult()
    .withExecutor(pool)
    .add("patient", () -> fetchPatient())
    .runBlocking();
```

`runBlocking()` inherits the executor automatically (it calls `run()` internally).

### DAG Composition (`merge`)

`merge` combines an independently built sub-DAG into the current DAG. All task nodes from the
inner `AsyncOperationResult` are absorbed into the outer DAG's node map so they participate
in the same parallel execution.

After a `merge` call, any task added via `addAfter` or `addListAfter` may reference keys from
either the original outer DAG or the merged inner DAG:

```kotlin
// Lambda form — build the inner DAG inline
val result = AsyncOperationResult()
    .add("patient") { fetchPatient() }
    .merge {
        AsyncOperationResult()
            .add("coverage") { fetchCoverage() }
            .addAfter("claim", "coverage", Coverage::class) { cov -> buildClaim(cov) }
    }
    .addAfter("summary", "patient", "claim") { deps ->
        buildSummary(deps["patient"]!!, deps["claim"]!!)
    }
    .run()
```

```kotlin
// Instance form — useful when the inner DAG is produced by a factory function
fun billingDag(): AsyncOperationResult = AsyncOperationResult()
    .add("coverage") { fetchCoverage() }
    .add("claim")    { fetchClaim() }

val result = AsyncOperationResult()
    .add("patient") { fetchPatient() }
    .merge(billingDag())
    .run()
```

**Constraints:**
- Inner nodes may only depend on other inner nodes — cross-DAG inner-to-outer dependencies
  cannot be expressed (the inner DAG is built independently).
- Task keys must be unique across both DAGs — a duplicate key throws `IllegalArgumentException`.
- Empty inner DAGs are silently accepted (no-op).
- The inner DAG's `timed()` setting is ignored; the outer DAG's timing applies to all tasks
  after the merge.

### Constraints

- Task keys must be unique — duplicate registration throws `IllegalArgumentException`
- Dependencies must be registered before the task that declares them
- Cycles are detected at registration time and throw `IllegalArgumentException`

---

## Usage Examples

### 1. Simple accumulation

```kotlin
val result = OperationResult.of(patient)
    .add("appointment") { fetchAppointment() }
    .add("coverage") { fetchCoverage() }

result.toParameters()
// Parameters contains: patient, appointment, coverage
```

### 2. Type-safe chaining

```kotlin
val result = OperationResult.of(patient)
    .addUsing("encounter") { p -> lookupEncounter(p) }   // p: Patient
    .addUsing("coverage") { e -> lookupCoverage(e) }     // e: Encounter

val coverage: Coverage = result.getResult()  // no cast
```

### 3. Collecting a typed list

```kotlin
val result = OperationResult.of(patient)
    .addAllUsing("history") { p -> fetchEncounterHistory(p) }

val history: List<Encounter> = result.getResultList()
result.count("history")   // number of encounters
```

### 4. Deriving a resource from accumulated inputs

```kotlin
val result = OperationResult.of(listOf(patient, appointment), "inputs")
    .addFrom("inputs", Patient::class) { patients ->
        buildClaimFor(patients.first())
    }

result.getByType<Claim>()   // [the derived Claim]
```

### 5. Resilient pipeline with error accumulation

```kotlin
val result = OperationResult.of(patient, errorStrategy = ErrorStrategy.ACCUMULATE)
    .add("coverage") { fetchCoverage() }      // may throw
    .addOrSkip("score") { computeScore() }    // failure → warning, pipeline continues

if (result.hasErrors()) {
    log.warn(result.toOperationOutcome().issueFirstRep.diagnostics)
}
```

### 6. Automatic reference linking

```kotlin
val result = OperationResult.of(patient("p1"))
    .add { encounter("e1") }
    .add { observation("obs1") }
    .linkReferences()   // wires subject/encounter references automatically

// Produces new resources (originals unchanged):
// encounter.subject     = Reference("Patient/p1")
// observation.subject   = Reference("Patient/p1")
// observation.encounter = Reference("Encounter/e1")
```

### 7. Explicit reference rules

```kotlin
val result = OperationResult.of(patient)
    .add { encounter }
    .linkReferences(
        ReferenceLinkRule(Encounter::class, Patient::class) { enc, pat ->
            enc.subject = Reference("Patient/${pat.idPart}")
        }
    )
```

### 8. Round-trip from Parameters

```kotlin
val incoming: Parameters = getParametersFromRequest()

val result = OperationResult.fromParameters(incoming)
    .add("derived") { deriveResource(result) }

result.toParameters()   // nested part structure from `incoming` is preserved
```

### 9. Constructing from a Bundle

```kotlin
val bundle: Bundle = fetchBundle()

val result = OperationResult.fromBundle(bundle)
// or with a custom key per entry:
val result = OperationResult.fromBundle(bundle) { entry -> entry.fullUrl }

result.getByType<Patient>()
result.toTransactionBundle()
```

### 10. Filtering and transforming

```kotlin
val patientsOnly = result.filterByType<Patient>()
val patientEntry = result.filterByName("patient")
val allPatients  = result.getByType<Patient>()

// Type-safe map — changes pipeline head type to Appointment
val mapped: OperationResult<Appointment> = result.mapValues { base -> normalize(base) }

// Peek for side effects (debugging, logging) — returns the same instance unchanged
val same = result.peek { map -> log.debug("state: {}", map.keys) }

// Convenience first-value lookups
val firstPatient: Patient? = result.takeFirstTyped<Patient>("patient")
val first: Base?           = result.takeFirst("patient")

// Remove a key (no-op if absent) or rename a key
val trimmed  = result.remove("draft")
val renamed  = result.rename("old", "new")

// Merge two pipelines — overlapping keys accumulate
val merged = resultA.merge(resultB)

// flatMap — chain an inner pipeline and merge all of its entries into the outer map
val combined = OperationResult.of(patient)
    .flatMap { p ->
        OperationResult.of(encounter(p), "encounter")
            .add("coverage") { fetchCoverage(p) }
    }
// map now contains: patient, encounter, coverage

// mapHead — transform the pipeline head WITHOUT adding a parameter entry
// useful for intermediate type conversions that should not appear in the output map
val withEncounter: OperationResult<Encounter> = OperationResult.of(patient, "patient")
    .mapHead { p -> buildEncounter(p) }    // head → Encounter, no new map entry
    .add("encounter") { it }               // explicitly store when ready

// mapHeadUsing — same, with head available as `this`
val withAppt: OperationResult<Appointment> = OperationResult.of(patient, "patient")
    .mapHeadUsing { buildAppointment(this) }
```

| Method | Head changes | Map entry added | On exception |
|--------|-------------|-----------------|--------------|
| `add` | yes | yes | ERROR outcome, head skipped |
| `flatMap` | yes (inner head) | yes (inner map merged) | no catch — throws |
| `mapHead` | yes | **no** | ERROR outcome, head skipped |
| `mapHeadUsing` | yes | **no** | ERROR outcome, head skipped |

### 11. Async DAG — parallel fetching with typed dependencies

```kotlin
val result = AsyncOperationResult()
    // These three tasks have no dependencies and run in parallel
    .add("patient")     { fetchPatient(patientId) }
    .add("coverage")    { fetchCoverage(coverageId) }
    .addList("history") { fetchEncounterHistory(patientId) }
    // Runs after "patient" resolves; receives a typed Patient directly
    .addAfter("encounter", "patient", Patient::class) { patient ->
        lookupEncounter(patient)
    }
    // Runs after "coverage" and "encounter" both resolve
    .addAfter("claim", "coverage", "encounter") { deps ->
        val coverage  = deps["coverage"]!!.filterIsInstance<Coverage>().first()
        val encounter = deps["encounter"]!!.filterIsInstance<Encounter>().first()
        buildClaim(coverage, encounter)
    }
    .runBlocking()

result.toParameters()
```

### 12. Async from a coroutine

```kotlin
suspend fun buildOutput(): Parameters {
    return AsyncOperationResult()
        .add("patient") { fetchPatient() }
        .addAfter("summary", "patient", Patient::class) { patient ->
            generateSummary(patient)
        }
        .run()
        .toParameters()
}
```

---

## Tech Stack

| | |
|---|---|
| Language | Kotlin 2.1.21 (JVM 11) |
| FHIR | HAPI FHIR 7.6.1 (R4 and DSTU3) |
| Async | Kotlin Coroutines 1.10.2 |
| Logging | SLF4J 1.7.36 API (no binding — consumer-supplied) |
| Spring Boot | 2.7.18 (optional — `fhirmason-spring` module) |
| Build | Maven (multi-module) |
| Testing | JUnit Jupiter 5.14.3, Hamcrest 3.0, ApprovalCrest, Logback 1.2.12, AssertJ 3.23.1 |

---

## Modules

| Module | Artifact ID | Description |
|---|---|---|
| `fhirmason-api` | `fhirmason-api` | Shared building blocks: `IOperationResult<T>`, `ErrorStrategy`, `StepMetrics`, `DateTimeInput` |
| `fhirmason-r4` | `fhirmason-r4` | R4 pipeline builders (`OperationResult`, `AsyncOperationResult`) |
| `fhirmason-dstu3` | `fhirmason-dstu3` | DSTU3 pipeline builders (`OperationResult`, `AsyncOperationResult`) |
| `fhirmason-spring` | `fhirmason-spring` | Spring Boot auto-configuration and base provider class |

---

## Building

```bash
mvn clean test          # build and test all modules
mvn clean install       # build, test, and install to local repo
```

---

## Project Structure

```
fhirmason-api/
└── src/main/java/dev/ratkay/operation/
    ├── IOperationResult.kt             # Version-agnostic interface (R4 + DSTU3 both implement)
    ├── ErrorStrategy.kt                # FAIL_FAST / ACCUMULATE enum
    ├── StepMetrics.kt                  # Per-step timing and outcome data
    └── DateTimeInput.kt                # Sealed wrapper for date/time values (collapses overloads)

fhirmason-r4/
└── src/
    ├── main/java/dev/ratkay/operation/r4/
    │   ├── OperationResult.kt              # Synchronous accumulator builder (R4)
    │   ├── AsyncOperationResult.kt         # Async/coroutine DAG-based builder
    │   ├── ParameterMapSerializer.kt       # Dot-delimited key flatten / unflatten logic
    │   ├── ReferenceLinkRule.kt            # Explicit reference linking rule descriptor
    │   ├── ReferenceLinkHelper.kt          # Reference linking algorithm helpers (internal)
    │   ├── FhirDateTimeConverter.kt        # Java/Kotlin date-time → FHIR R4 type converter
    │   ├── OperationOutcomeExtensions.kt   # Exception → OperationOutcome helpers
    │   ├── FhirPathHelper.kt               # FhirPath evaluation helpers (internal)
    │   └── FhirExtensionHelper.kt          # Deep extension retrieval utility
    └── test/java/dev/ratkay/operation/r4/
        ├── OperationResultTest.kt
        ├── OperationResultFromTest.kt
        ├── OperationResultLinkReferencesTest.kt
        ├── OperationResultMetricsTest.kt
        ├── OperationResultConditionalTest.kt
        ├── OperationResultMapHeadTest.kt
        ├── OperationResultFhirErrorHandlingTest.kt
        ├── OperationResultRetryTest.kt
        ├── OperationResultFhirPathTest.kt
        ├── OperationResultComplexTest.kt
        ├── AsyncOperationResultTest.kt
        ├── AsyncOperationResultMetricsTest.kt
        ├── AsyncOperationResultDescribeTest.kt
        ├── AsyncOperationResultRetryTest.kt
        ├── AsyncOperationResultDependentRetryTest.kt
        ├── AsyncOperationResultMergeTest.kt
        ├── AsyncOperationResultTimeoutTest.kt
        ├── AsyncOperationResultDagTimeoutTest.kt
        ├── AsyncOperationResultConditionalTest.kt
        ├── AsyncOperationResultDefaultTest.kt
        ├── AsyncOperationResultComplexTest.kt
        ├── FhirDateTimeConverterTest.kt
        └── FhirExtensionHelperTest.kt

fhirmason-dstu3/
└── src/
    ├── main/java/dev/ratkay/operation/dstu3/
    │   ├── OperationResult.kt              # Synchronous accumulator builder (DSTU3)
    │   ├── AsyncOperationResult.kt         # Async/coroutine DAG-based builder (DSTU3)
    │   ├── ParameterMapSerializer.kt       # Dot-delimited key flatten / unflatten logic
    │   ├── ReferenceLinkRule.kt            # Explicit reference linking rule descriptor
    │   ├── ReferenceLinkHelper.kt          # Reference linking algorithm helpers (internal)
    │   ├── FhirDateTimeConverter.kt        # Java/Kotlin date-time → FHIR DSTU3 type converter
    │   ├── OperationOutcomeExtensions.kt   # Exception → OperationOutcome helpers
    │   ├── FhirPathHelper.kt               # FhirPath evaluation helpers (internal)
    │   └── FhirExtensionHelper.kt          # Deep extension retrieval utility
    └── test/java/dev/ratkay/operation/dstu3/
        ├── OperationResultTest.kt
        ├── OperationResultFromTest.kt
        ├── OperationResultLinkReferencesTest.kt
        ├── OperationResultMetricsTest.kt
        ├── OperationResultConditionalTest.kt
        ├── OperationResultMapHeadTest.kt
        ├── OperationResultFhirErrorHandlingTest.kt
        ├── OperationResultRetryTest.kt
        ├── OperationResultFhirPathTest.kt
        ├── OperationResultComplexTest.kt
        ├── AsyncOperationResultTest.kt
        ├── AsyncOperationResultMetricsTest.kt
        ├── AsyncOperationResultDescribeTest.kt
        ├── AsyncOperationResultRetryTest.kt
        ├── AsyncOperationResultDependentRetryTest.kt
        ├── AsyncOperationResultMergeTest.kt
        ├── AsyncOperationResultTimeoutTest.kt
        ├── AsyncOperationResultDagTimeoutTest.kt
        ├── AsyncOperationResultConditionalTest.kt
        ├── AsyncOperationResultDefaultTest.kt
        ├── AsyncOperationResultComplexTest.kt
        ├── AsyncOperationResultTypedDepOverloadsTest.kt
        ├── FhirDateTimeConverterTest.kt
        └── FhirExtensionHelperTest.kt

fhirmason-spring/
└── src/
    ├── main/java/dev/ratkay/spring/
    │   ├── FhirMasonAutoConfiguration.kt   # Spring Boot auto-configuration
    │   ├── FhirMasonProperties.kt          # @ConfigurationProperties (prefix=fhirmason)
    │   ├── FhirMasonFactory.kt             # Spring @Bean — creates pipelines
    │   └── FhirMasonOperationProvider.kt   # Abstract base for operation providers
    ├── main/resources/META-INF/
    │   ├── spring.factories                # Boot 2.x auto-config registration
    │   └── spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
    └── test/java/dev/ratkay/spring/
        └── FhirMasonAutoConfigurationTest.kt
```

---

## Spring Boot Integration

### Dependency

```xml
<dependency>
    <groupId>dev.ratkay</groupId>
    <artifactId>fhirmason-spring</artifactId>
    <version>0.0.1</version>
</dependency>
```

### Auto-configuration

When `fhirmason-spring` is on the classpath in a Spring Boot application, a `FhirMasonFactory` bean is registered automatically. No explicit configuration is required.

### Configuration Properties

```yaml
fhirmason:
  error-strategy: ACCUMULATE   # FAIL_FAST | ACCUMULATE (default: ACCUMULATE)
  metrics:
    enabled: true               # enable per-step timing (default: false)
```

### Injecting the Factory

```kotlin
@Service
class PatientService(private val fhirMason: FhirMasonFactory) {

    fun buildBundle(patient: Patient): Parameters =
        fhirMason.pipeline(patient)
            .add("encounter") { fetchEncounter(patient.idElement.idPart) }
            .add("coverage")  { fetchCoverage(patient.idElement.idPart) }
            .toParameters()

    fun buildAsync(): OperationResult<Base> =
        fhirMason.asyncPipeline()
            .add("patient") { fetchPatient() }
            .add("coverage") { fetchCoverage() }
            .runBlocking()
}
```

### Operation Provider Base Class

Extend `FhirMasonOperationProvider` to inherit pipeline helpers in your HAPI FHIR operation provider. Implement `IResourceProvider` in your subclass.

```kotlin
@Component
class PatientOperationProvider(fhirMason: FhirMasonFactory) :
    FhirMasonOperationProvider(fhirMason), IResourceProvider {

    override fun getResourceType() = Patient::class.java

    @Operation(name = "\$summary")
    fun summary(@IdParam id: IdType): Parameters =
        parameters {
            pipeline(fetchPatient(id))
                .add("encounter") { fetchEncounter(id) }
                .add("coverage")  { fetchCoverage(id) }
        }

    @Operation(name = "\$bundle")
    fun bundle(@IdParam id: IdType): Bundle =
        bundle(Bundle.BundleType.COLLECTION) {
            pipeline(fetchPatient(id))
                .addAll("observations") { fetchObservations(id) }
        }
}
```

### Custom Factory Bean

Override the auto-configured factory by declaring your own:

```kotlin
@Configuration
class FhirMasonConfig {
    @Bean
    fun fhirMasonFactory(properties: FhirMasonProperties) =
        FhirMasonFactory(properties)  // or a custom subclass
}
```

---

## Using from Java

FHIRMason is fully usable from Java with an unbroken fluent chain.

### Factory methods

`@JvmStatic` annotations on the companion object mean you call factories directly on the class — no `.Companion.` required:

```java
// Single resource — name defaults to fhirType().lowercase()
OperationResult<Patient> result = OperationResult.of(patient);

// Single resource with explicit name and error strategy
OperationResult<Patient> result = OperationResult.of(patient, "patient", ErrorStrategy.ACCUMULATE);

// Empty pipeline
OperationResult<Base> empty = OperationResult.empty();
OperationResult<Base> accumulating = OperationResult.empty(ErrorStrategy.ACCUMULATE);

// From existing FHIR resources
OperationResult<Base> fromParams = OperationResult.fromParameters(parameters);
OperationResult<Patient> typed = OperationResult.fromParametersTyped(parameters, "patient", Patient.class);
OperationResult<Base> fromBundle = OperationResult.fromBundle(bundle);
```

### Builder methods with optional parameters

`@JvmOverloads` generates overloads for every trailing-default parameter, so you only supply what you need:

```java
// add without a name — key defaults to fhirType().lowercase()
result.add(() -> buildEncounter())
      .add("coverage", () -> buildCoverage());

// addUsing — receives the current pipeline head
OperationResult.of(patient)
    .addUsing(encounter -> buildClaim(encounter))
    .addUsing("summary", encounter -> buildSummary(encounter));

// addAll / addAllUsing work the same way
result.addAll(() -> List.of(obs1, obs2))
      .addAll("vitals", () -> List.of(obs3));
```

### Convenience methods with optional trailing parameters

```java
// addCoding — display is optional
result.addCoding("status", "http://loinc.org", "55423-8")
      .addCoding("type",   "http://loinc.org", "55423-8", "Step count");

// addQuantity — system and code are optional
result.addQuantity("weight", new BigDecimal("70"), "kg")
      .addQuantity("bmi",    new BigDecimal("22"), "kg/m2", "http://unitsofmeasure.org", "kg/m2");

// addCodeableConcept — display and text are optional
result.addCodeableConcept("category", "http://loinc.org", "vital-signs")
      .addCodeableConcept("category", "http://loinc.org", "vital-signs", "Vital Signs", "Patient vitals");

// toBundle — configBlock is optional
Bundle plain = result.toBundle(Bundle.BundleType.COLLECTION);
Bundle custom = result.toBundle(Bundle.BundleType.COLLECTION, entry -> entry.setFullUrl("urn:uuid:" + UUID.randomUUID()));
```

### Retry methods

```java
// addWithRetry — all retry parameters have defaults
result.addWithRetry(() -> fetchPatientFromServer())
      .addWithRetry("obs", 5, () -> fetchObservation())
      .addWithRetry("enc", 3, 1000L, () -> fetchEncounter())
      .addWithRetry("cov", 3, 500L, e -> !(e instanceof FatalException), () -> fetchCoverage());
```

---

## Artifacts

### FHIR R4

```xml
<dependency>
    <groupId>dev.ratkay</groupId>
    <artifactId>fhirmason-r4</artifactId>
    <version>0.0.1</version>
</dependency>
```

### FHIR DSTU3

```xml
<dependency>
    <groupId>dev.ratkay</groupId>
    <artifactId>fhirmason-dstu3</artifactId>
    <version>0.0.1</version>
</dependency>
```

DSTU3 `OperationResult` lives in package `dev.ratkay.operation.dstu3` and mirrors the R4 API with one exception: `addCanonical` and `addCanonicalUsing` are omitted because `CanonicalType` does not exist in DSTU3.

### Version-agnostic code

Both `OperationResult` variants implement `IOperationResult<T>` from the `fhirmason-api` module, which is a transitive dependency of both `fhirmason-r4` and `fhirmason-dstu3`. Code that only needs to inspect error state, metrics, or key/count information can accept `IOperationResult<*>` without importing a version-specific module:

```kotlin
import dev.ratkay.operation.IOperationResult

fun validate(result: IOperationResult<*>) {
    if (result.hasErrors()) result.throwIfErrors()
}
```
