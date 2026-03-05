# FHIRMason

A Kotlin library that provides a fluent, chainable API for accumulating and transforming [FHIR R4](https://hl7.org/fhir/R4/) resources. FHIRMason wraps FHIR `Base` objects in an `OperationResult` builder, enabling composable pipelines that collect named resources into a shared parameter map.

---

## Overview

Working with FHIR resources often involves fetching and combining multiple resources across several steps. FHIRMason models this as an accumulator pipeline: each step adds one or more named resources to a shared store, and the final state can be inspected or serialized to a `Parameters` resource.

```kotlin
val result = OperationResult.of(patient, "patient")
    .add("coverage") { fetchCoverage() }
    .addUsing("encounter") { p -> lookupEncounter(p) }
    .addAll("history") { fetchEncounterHistory() }

result.toParameters()   // serialize everything to a FHIR Parameters resource
result.getResult()      // the most recently added value
```

---

## Features

### Entry Points

```kotlin
// Wrap a single FHIR Base value — name defaults to its fhirType()
OperationResult.of(patient)
OperationResult.of(patient, "myPatient")

// Wrap a list of values — each item keyed by its fhirType() unless a name is given
OperationResult.of(listOf(patient, appointment))
OperationResult.of(listOf(patient, appointment), "items")
```

### Builder Methods

All builder methods add values to the internal parameter map and return a new `OperationResult` whose `getResult()` points to the newly added value(s).

#### Single item

| Method | Lambda signature | Name resolution |
|---|---|---|
| `add(name?) { R }` | No receiver | `name` or `fhirType()` |
| `addUsing(name?) { t -> R }` | Receives current result | `name` or `fhirType()` |

#### Multiple items

| Method | Lambda signature | Name resolution |
|---|---|---|
| `addAll(name?) { List<R> }` | No receiver | `name` or per-item `fhirType()` |
| `addAllUsing(name?) { t -> List<R> }` | Receives current result | `name` or per-item `fhirType()` |

#### From existing parameters

| Method | Description |
|---|---|
| `addFrom(name, type) { list -> R }` | Filters stored values under `name` by `type`, passes them to the builder, adds result back under the same `name` |
| `addAllFrom(name, type) { list -> List<R> }` | Same as above but builder returns a list |

### Query Methods

```kotlin
result.getAllParameters()       // Map<String, List<Base>> — full snapshot
result.getAll("patient")        // List<Base> for a specific name (empty if absent)
result.getByType(Patient::class) // all Patient instances across all keys
result.containsKey("patient")   // Boolean
result.getKeys()                // Set<String>
result.count("patient")         // Int — entries under that name
result.totalCount()             // Int — all entries across all names
result.isEmpty()                // Boolean
result.isNotEmpty()             // Boolean
result.getResult()              // T — the most recently added value
```

### Functional Transformations

These return a new `OperationResult` with a filtered or transformed parameter map without modifying the original.

```kotlin
result.filterByType(Patient::class)    // keep only entries whose values are Patients
result.filterByName("patient")         // keep only the "patient" entry
result.mapValues { base -> ... }       // transform every stored value
```

### Output

```kotlin
result.toParameters()   // FHIR Parameters resource — one parameter entry per stored value
```

---

## Usage Examples

### Accumulating multiple resources

```kotlin
val result = OperationResult.of(patient, "patient")
    .add("appointment") { -> fetchAppointment() }
    .add("coverage") { -> fetchCoverage() }

val params = result.toParameters()
// Parameters contains: patient, appointment, coverage
```

### Using the current result in the next step

```kotlin
val result = OperationResult.of(patient)
    .addUsing("encounter") { p -> lookupEncounter(p) }
    .addUsing("coverage") { e -> lookupCoverageForEncounter(e) }
```

### Adding a list of resources

```kotlin
val result = OperationResult.of(patient, "patient")
    .addAllUsing("history") { p -> fetchEncounterHistory(p) }

result.count("history")   // number of encounters retrieved
```

### Deriving a new resource from previously accumulated ones

```kotlin
val result = OperationResult.of(listOf(patient, appointment), "inputs")
    .addFrom("inputs", Patient::class) { patients ->
        buildClaimFor(patients.first())
    }

result.getByType(Claim::class)   // the derived Claim
```

### Inspecting and filtering the accumulated state

```kotlin
val patients = result.getByType(Patient::class)

val patientsOnly = result.filterByType(Patient::class)
val patientEntry = result.filterByName("patient")
```

---

## Tech Stack

| | |
|---|---|
| Language | Kotlin 1.9.20 (JVM 11) |
| FHIR | HAPI FHIR 6.4.2 (R4) |
| Build | Maven |
| Testing | JUnit Jupiter 5.9.1, Hamcrest 2.2 |

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
├── main/java/dev/ratkay/
│   └── operation/
│       └── OperationResult.kt         # Core builder/accumulator class
└── test/java/dev/ratkay/
    └── operation/
        └── OperationResultTest.kt     # Unit test suite
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
