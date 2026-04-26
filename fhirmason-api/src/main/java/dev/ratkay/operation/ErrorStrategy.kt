package dev.ratkay.operation

/**
 * Controls how an [IOperationResult] pipeline reacts when a step throws an exception.
 *
 * - [FAIL_FAST]: subsequent builder steps are skipped as soon as an error is recorded;
 *   the pipeline short-circuits and only the error outcomes are accumulated.
 * - [ACCUMULATE]: every step is attempted regardless of prior errors; all outcomes
 *   (successes and failures) are collected before the caller inspects the result.
 */
enum class ErrorStrategy {
    FAIL_FAST,
    ACCUMULATE
}
