# FHIRMason

<img width="1024" height="1024" alt="image" src="https://github.com/user-attachments/assets/85172d09-f525-4365-b485-bf746576d494" />

A Kotlin library that provides a fluent, chainable API for accumulating and transforming [FHIR R4](https://hl7.org/fhir/R4/) resources. FHIRMason wraps FHIR `Base` objects in an `OperationResult` builder, enabling composable pipelines that collect named resources into a shared parameter map.

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

```kotlin
// Reified overloads — no KClass argument needed
result.filterByType<Patient>()         // keep only entries whose values are Patients
result.filterByType(Patient::class)    // same, explicit form

result.filterByName("patient")         // keep only the "patient" key
result.mapValues { base -> transform(base) }
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
val mapped       = result.mapValues { base -> normalize(base) }
```

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
| Language | Kotlin 1.9.20 (JVM 11) |
| FHIR | HAPI FHIR 6.4.2 (R4) |
| Async | Kotlin Coroutines 1.5.0 |
| Logging | SLF4J 1.7.36 API (no binding — consumer-supplied) |
| Spring Boot | 2.7.18 (optional — `fhirmason-spring` module) |
| Build | Maven (multi-module) |
| Testing | JUnit Jupiter 5.9.1, Hamcrest 2.2, ApprovalCrest, Logback 1.2.12, AssertJ 3.23.1 |

---

## Modules

| Module | Artifact ID | Description |
|---|---|---|
| `fhirmason-core` | `fhirmason-core` | Core pipeline builders — no Spring dependency |
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
fhirmason-core/
└── src/
    ├── main/java/dev/ratkay/operation/
    │   ├── OperationResult.kt              # Synchronous accumulator builder
    │   ├── AsyncOperationResult.kt         # Async/coroutine DAG-based builder
    │   ├── ReferenceLinkRule.kt            # Explicit reference linking rule descriptor
    │   ├── StepMetrics.kt                  # Per-step timing and outcome data
    │   ├── ErrorStrategy.kt                # FAIL_FAST / ACCUMULATE enum
    │   └── OperationOutcomeExtensions.kt   # Exception → OperationOutcome helper
    └── test/java/dev/ratkay/operation/
        ├── OperationResultTest.kt
        ├── OperationResultFromTest.kt
        ├── OperationResultLinkReferencesTest.kt
        ├── OperationResultMetricsTest.kt
        ├── AsyncOperationResultTest.kt
        └── AsyncOperationResultMetricsTest.kt

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

## Core Artifact

```xml
<dependency>
    <groupId>dev.ratkay</groupId>
    <artifactId>fhirmason-core</artifactId>
    <version>0.0.1</version>
</dependency>
```
