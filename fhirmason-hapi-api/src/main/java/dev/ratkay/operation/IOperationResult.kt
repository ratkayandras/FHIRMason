package dev.ratkay.operation

/**
 * Version-agnostic view of an OperationResult pipeline.
 *
 * Implemented by both [dev.ratkay.operation.OperationResult] (R4) and
 * [dev.ratkay.operation.dstu3.OperationResult] (DSTU3).
 * Code that only needs error state, metrics, or key/count queries can accept
 * this interface without depending on a specific FHIR version module.
 *
 * @param T the pipeline head type (the most recently added FHIR resource)
 */
interface IOperationResult<T> {

    // ── Error state ───────────────────────────────────────────────────────

    /** Returns `true` if any ERROR- or FATAL-severity outcome has been recorded. */
    fun hasErrors(): Boolean

    /** Returns `true` if no ERROR- or FATAL-severity outcomes have been recorded. */
    fun isSuccessful(): Boolean

    /** Returns `true` if any WARNING- or INFORMATION-severity outcome has been recorded. */
    fun hasWarnings(): Boolean

    /**
     * Throws an exception if [hasErrors] is `true`; otherwise returns this result unchanged.
     * The returned type is `IOperationResult<T>` — concrete implementations override with a
     * covariant return type (e.g. `OperationResult<T>`).
     */
    fun throwIfErrors(): IOperationResult<T>

    // ── Metrics & timing ──────────────────────────────────────────────────

    /**
     * Enables per-step metrics collection for subsequent pipeline steps.
     * The returned type is `IOperationResult<T>` — concrete implementations override with a
     * covariant return type.
     */
    fun timed(): IOperationResult<T>

    /** Returns a snapshot of [StepMetrics] collected so far. Empty when [timed] was not called. */
    fun getMetrics(): List<StepMetrics>

    // ── Query ─────────────────────────────────────────────────────────────

    /** Returns `true` if the parameter map contains an entry for [name]. */
    fun containsKey(name: String): Boolean

    /** Returns the set of all keys currently in the parameter map. */
    fun getKeys(): Set<String>

    /** Returns the number of values stored under [name], or 0 if the key is absent. */
    fun count(name: String): Int

    /** Returns the total number of values across all keys. */
    fun totalCount(): Int

    /** Returns `true` if the parameter map contains no entries. */
    fun isEmpty(): Boolean

    /** Returns `true` if the parameter map contains at least one entry. */
    fun isNotEmpty(): Boolean
}
