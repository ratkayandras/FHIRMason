package dev.ratkay.operation

/**
 * Timing and outcome data captured for a single pipeline step.
 *
 * Collected by [OperationResult] when metrics are enabled via [OperationResult.timed].
 *
 * @property stepName     The key under which the result was stored (e.g. `"patient"`, `"coverage"`).
 * @property resourceType The FHIR type of the produced resource (e.g. `"Patient"`, `"Coverage"`).
 *                        Empty string when the step failed before a value was produced.
 * @property durationMs   Wall-clock duration of the builder lambda in milliseconds.
 * @property success      `true` when the lambda completed without throwing.
 */
data class StepMetrics(
    val stepName: String,
    val resourceType: String,
    val durationMs: Long,
    val success: Boolean
)
