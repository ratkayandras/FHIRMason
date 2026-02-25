# FHIRMason

A Kotlin library that provides a fluent, chainable API for working with [FHIR R4](https://hl7.org/fhir/R4/) resources. FHIRMason wraps FHIR resources in a monadic `OperationResult` container, enabling safe, composable transformation pipelines for healthcare data — with automatic error short-circuiting throughout the chain.

---

## Overview

Working with FHIR resources often involves a sequence of transformation steps where any step can fail with an `OperationOutcome`. FHIRMason models this as a Railway-Oriented pipeline: each step either produces a new resource or propagates an error, so downstream steps never need to check for failures manually.

```kotlin
val result = OperationResult.of(patient, "patient")
    .operateResource { p -> lookupCoverage(p) }          // transform using current resource
    .operateCombined { fetchEncounterHistory() }          // add to accumulated resources
    .operateParameters { params -> buildClaim(params) }   // transform via Parameters view
    .asBundle()                                           // serialize to FHIR Bundle
```

If any step returns an `OperationOutcome` with ERROR or FATAL severity, the rest of the chain is skipped and the outcome is returned directly.

---

## Features

### Result States

`OperationResult<T>` is a sealed class with three internal states:

| State | Description |
|---|---|
| `ResourceSuccess<T>` | Wraps a single FHIR `Resource` |
| `CollectionSuccess<C, T>` | Wraps a collection of FHIR `Resource`s |
| `Error<T>` | Wraps an `OperationOutcome` with ERROR or FATAL severity |

### Entry Points

```kotlin
// Wrap a single resource (auto-detects errors)
OperationResult.of(resource)
OperationResult.of(resource, "customName")

// Wrap a collection of resources
OperationResult.ofCollection(listOf(patient, appointment))
```

### Chaining Operations

All operation methods are no-ops when the result is in an `Error` state.

| Method | Lambda receives | Replaces current resource |
|---|---|---|
| `operate { }` | nothing | yes |
| `operate(name) { }` | nothing | yes |
| `operateResource { r -> }` | current resource | yes |
| `operateResource(name) { r -> }` | current resource | yes |
| `operateList { }` | nothing | yes (as collection) |
| `operateResourceList { r -> }` | current resource | yes (as collection) |
| `operateParameters { p -> }` | current state as `Parameters` | yes |
| `operateParameters(name) { p -> }` | current state as `Parameters` | yes |

#### Combined variants

`*Combined` methods accumulate all previous resources alongside the new result. Use these when the final output should contain multiple resources from different steps.

| Method | Accumulates previous resources |
|---|---|
| `operateCombined { }` | yes |
| `operateCombined(name) { }` | yes |
| `operateResourceCombined { r -> }` | yes |
| `operateResourceCombined(name) { r -> }` | yes |
| `operateResourceListCombined { r -> }` | yes |
| `operateParametersCombined { p -> }` | yes |
| `operateParametersCombined(name) { p -> }` | yes |

### Output Formats

```kotlin
operationResult.asParameters()  // → Parameters (or OperationOutcome on error)
operationResult.asBundle()      // → Bundle     (or OperationOutcome on error)
```

---

## Usage Examples

### Simple transformation

```kotlin
val result = OperationResult.of(patient)
    .operateResource { p -> enrichPatient(p) }
    .asParameters()
```

### Accumulating multiple resources

```kotlin
val result = OperationResult.of(patient, "patient")
    .operateCombined("appointment") { fetchAppointment() }
    .operateCombined("coverage") { fetchCoverage() }
    .asBundle()
// Bundle contains patient + appointment + coverage
```

### Error short-circuiting

```kotlin
val result = OperationResult.of(errorOutcome) // ERROR severity
    .operateCombined { fetchAppointment() }    // skipped
    .operateCombined { fetchCoverage() }       // skipped
    .asParameters()
// Returns the OperationOutcome directly
```

### Passing resources between steps via Parameters

```kotlin
val result = OperationResult.of(patient, "patient")
    .operateParametersCombined("encounter") { params ->
        val p = params.getParameter("patient").resource as Patient
        buildEncounter(p)
    }
    .asBundle()
```

---

## Error Handling

An `OperationOutcome` is treated as an error if it meets **both** conditions:
- Has at least one issue (`hasIssue() == true`)
- At least one issue has severity `ERROR` or `FATAL`

`OperationOutcome` resources with `WARNING` or `INFORMATION` severity are treated as ordinary resources and do not short-circuit the chain.

---

## Tech Stack

| | |
|---|---|
| Language | Kotlin 1.9.20 (JVM 11) |
| FHIR | HAPI FHIR 6.4.2 (R4) |
| Build | Maven |
| Testing | JUnit Jupiter 5.9.1, Hamcrest 2.2, ApprovalCrest 0.61.6 |

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
│   ├── dto/
│   │   └── ResourceHolder.kt          # Name + Resource pair
│   ├── extension/
│   │   ├── operationoutcomeextension.kt  # Error detection
│   │   └── resoruceextension.kt          # Resource → Parameters/Bundle helpers
│   └── operation/
│       └── OperationResult.kt         # Core sealed class
└── test/java/dev/ratkay/
    └── operation/
        └── OperationResultTest.kt     # Approval-based test suite
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
