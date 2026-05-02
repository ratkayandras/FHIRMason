package dev.ratkay.operation

/**
 * Controls how an [IOperationResult] pipeline reacts when a step throws an exception.
 *
 * - [FAIL_FAST]: subsequent builder steps are skipped as soon as an error is recorded;
 *   the pipeline short-circuits and only the error outcomes are accumulated.
 * - [ACCUMULATE]: every step is attempted regardless of prior errors; all outcomes
 *   (successes and failures) are collected before the caller inspects the result.
 * - [PROPAGATE]: lambda exceptions bubble to the caller unchanged, without being wrapped
 *   as [dev.ratkay.operation.IOperationResult] outcomes. Useful when the caller's
 *   infrastructure (e.g. Spring `@ControllerAdvice`) already handles exceptions and needs
 *   the original exception type, stack trace, and message fidelity intact.
 */
enum class ErrorStrategy {
    FAIL_FAST,
    ACCUMULATE,
    PROPAGATE
}
