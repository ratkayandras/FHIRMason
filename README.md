# FHIRMason

A Kotlin library that provides a fluent, chainable API for accumulating and transforming [FHIR R4](https://hl7.org/fhir/R4/) resources. FHIRMason wraps FHIR `Base` objects in an `OperationResult` builder, enabling composable pipelines that collect named resources into a shared parameter map.

---

## Overview

Working with FHIR resources often involves fetching and combining multiple resources across several steps. FHIRMason models this as an accumulator pipeline: each step adds one or more named resources to a shared store, and the final state can be inspected or serialized to a `Parameters` resource.

Two builders are provided:

- **`OperationResult`** — synchronous, immutable, fluent chain
- **`AsyncOperationResult`** — async/coroutine DAG that automatically parallelises independent tasks

```kotlin
// Synchronous
val result = OperationResult.of(patient, "patient")
    .add("coverage") { fetchCoverage() }
    .addUsing("encounter") { p -> lookupEncounter(p) }
    .addAll("history") { fetchEncounterHistory() }

result.toParameters()   // serialize everything to a FHIR Parameters resource
result.getResult()      // the most recently added value

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
// Single value — name defaults to fhirType().lowercase() when omitted
OperationResult.of(patient)
OperationResult.of(patient, "myPatient")

// List of values — each item keyed by its fhirType() unless a shared name is given
OperationResult.of(listOf(patient, appointment))
OperationResult.of(listOf(patient, appointment), "inputs")
```

### Builder Methods

All builder methods add values to the internal parameter map and return a new `OperationResult` whose `getResult()` points to the newly added value(s).

#### Single item

| Method | Lambda receives | Name resolution |
|---|---|---|
| `add(name?) { R }` | nothing | `name` or `fhirType()` |
| `addUsing(name?) { t -> R }` | current result `t` | `name` or `fhirType()` |

```kotlin
val result = OperationResult.of(patient, "patient")
    .add("appointment") { fetchAppointment() }           // no access to previous result
    .addUsing("coverage") { appt -> fetchCoverage(appt) } // receives the last added value
```

#### Multiple items

| Method | Lambda receives | Name resolution |
|---|---|---|
| `addAll(name?) { List<R> }` | nothing | `name` or per-item `fhirType()` |
| `addAllUsing(name?) { t -> List<R> }` | current result `t` | `name` or per-item `fhirType()` |

```kotlin
val result = OperationResult.of(patient, "patient")
    .addAll("history") { fetchEncounters() }
    .addAllUsing("observations") { encounters -> fetchObservations(encounters) }
```

#### From existing parameters

| Method | Description |
|---|---|
| `addFrom(name, type) { list -> R }` | Filters stored values under `name` by `type`, passes the typed list to the builder, stores the result back under `name` |
| `addAllFrom(name, type) { list -> List<R> }` | Same but the builder returns a list |

```kotlin
// Accumulate mixed inputs under "inputs", then derive a Claim from the Patient within
val result = OperationResult.of(listOf(patient, appointment), "inputs")
    .addFrom("inputs", Patient::class) { patients ->
        buildClaimFor(patients.first())
    }

result.getByType(Claim::class)  // [the derived Claim]
```

### Query Methods

```kotlin
result.getResult()              // T — the most recently added value (throws if none)
result.getAllParameters()        // Map<String, List<Base>> — full snapshot
result.getAll("patient")        // List<Base> for a specific key (empty if absent)
result.getByType(Patient::class) // all Patient instances across all keys
result.containsKey("patient")   // Boolean
result.getKeys()                // Set<String>
result.count("patient")         // Int — entries under that key
result.totalCount()             // Int — all entries across all keys
result.isEmpty()                // Boolean
result.isNotEmpty()             // Boolean
```

### Functional Transformations

These return a new `OperationResult` with a filtered or transformed parameter map without modifying the original.

```kotlin
result.filterByType(Patient::class)    // keep only entries whose values are Patients
result.filterByName("patient")         // keep only the "patient" entry
result.mapValues { base -> transform(base) } // transform every stored value
```

### Output

```kotlin
result.toParameters()   // FHIR Parameters resource — one parameter entry per stored value
```

---

## AsyncOperationResult (Async / DAG)

`AsyncOperationResult` builds a task graph where each task is keyed by name. Tasks with no dependencies run in parallel; tasks that declare dependencies wait only for those specific tasks. Cycle detection happens at registration time.

### Registration Methods

#### Root tasks (no dependencies)

```kotlin
AsyncOperationResult()
    .add("patient") { fetchPatient() }           // suspending lambda → single Base
    .addList("observations") { fetchObs() }      // suspending lambda → List<Base>
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
    // single value injected — receives the first Patient stored under "patient"
    .addAfter("encounter", "patient", Patient::class) { patient ->
        lookupEncounter(patient)
    }
    // list injected — receives all Observation instances stored under "observations"
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

The returned `OperationResult<Base>` supports all the same query, transformation, and serialisation methods as the synchronous builder.

### Constraints

- Task keys must be unique — duplicate registration throws `IllegalArgumentException`
- Dependencies must be registered before the task that declares them
- Cycles are detected at registration time and throw `IllegalArgumentException`

---

## Usage Examples

### 1. Simple accumulation

```kotlin
val result = OperationResult.of(patient, "patient")
    .add("appointment") { fetchAppointment() }
    .add("coverage") { fetchCoverage() }

val params = result.toParameters()
// Parameters contains: patient, appointment, coverage
```

### 2. Chaining steps with the previous result

```kotlin
val result = OperationResult.of(patient)
    .addUsing("encounter") { p -> lookupEncounter(p) }
    .addUsing("coverage") { e -> lookupCoverageForEncounter(e) }
```

### 3. Collecting a list

```kotlin
val result = OperationResult.of(patient, "patient")
    .addAllUsing("history") { p -> fetchEncounterHistory(p) }

result.count("history")   // number of encounters retrieved
```

### 4. Deriving a resource from previously accumulated inputs

```kotlin
val result = OperationResult.of(listOf(patient, appointment), "inputs")
    .addFrom("inputs", Patient::class) { patients ->
        buildClaimFor(patients.first())
    }

result.getByType(Claim::class)   // [the derived Claim]
```

### 5. Filtering the accumulated state

```kotlin
val patientsOnly  = result.filterByType(Patient::class)
val patientEntry  = result.filterByName("patient")
val allPatients   = result.getByType(Patient::class)
```

### 6. Async DAG — parallel fetching with typed dependencies

```kotlin
val result = AsyncOperationResult()
    // These three tasks have no dependencies and run in parallel
    .add("patient")      { fetchPatient(patientId) }
    .add("coverage")     { fetchCoverage(coverageId) }
    .addList("history")  { fetchEncounterHistory(patientId) }
    // Runs after "patient" resolves; receives a typed Patient directly
    .addAfter("encounter", "patient", Patient::class) { patient ->
        lookupEncounter(patient)
    }
    // Runs after "coverage" and "encounter" both resolve; uses raw map for multi-dep
    .addAfter("claim", "coverage", "encounter") { deps ->
        val coverage  = deps["coverage"]!!.filterIsInstance<Coverage>().first()
        val encounter = deps["encounter"]!!.filterIsInstance<Encounter>().first()
        buildClaim(coverage, encounter)
    }
    .runBlocking()

result.toParameters()   // all five resources serialized
```

### 7. Using AsyncOperationResult from a coroutine

```kotlin
suspend fun buildOperationOutput(): Parameters {
    val result = AsyncOperationResult()
        .add("patient") { fetchPatient() }
        .addAfter("summary", "patient", Patient::class) { patient ->
            generateSummary(patient)
        }
        .run()   // suspend — no thread blocking

    return result.toParameters()
}
```

---

## Tech Stack

| | |
|---|---|
| Language | Kotlin 1.9.20 (JVM 11) |
| FHIR | HAPI FHIR 6.4.2 (R4) |
| Async | Kotlin Coroutines 1.5.0 |
| Build | Maven |
| Testing | JUnit Jupiter 5.9.1, Hamcrest 2.2, ApprovalCrest |

---

## Building

```bash
mvn clean install
```

## Running Tests

```bash
mvn test
```

---

## Project Structure

```
src/
├── main/java/dev/ratkay/operation/
│   ├── OperationResult.kt          # Synchronous accumulator builder
│   └── AsyncOperationResult.kt     # Async/coroutine DAG-based builder
└── test/java/dev/ratkay/operation/
    ├── OperationResultTest.kt
    └── AsyncOperationResultTest.kt
```

---

## Artifact

```xml
<dependency>
    <groupId>dev.ratkay</groupId>
    <artifactId>fhirmason</artifactId>
    <version>0.0.1</version>
</dependency>
```
