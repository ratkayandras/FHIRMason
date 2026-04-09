package dev.ratkay.operation

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.fhirpath.IFhirPath
import org.hl7.fhir.instance.model.api.IBase
import org.hl7.fhir.r4.model.Base
import org.hl7.fhir.r4.model.BooleanType

/**
 * Utility object for evaluating FHIRPath expressions against FHIR R4 resources.
 *
 * Wraps HAPI FHIR's [IFhirPath] engine, which is obtained once from
 * [FhirContext.forR4Cached] and reused for all evaluations. The engine is
 * thread-safe and expensive to create, so keeping a single instance here avoids
 * repeated initialisation overhead.
 *
 * ### Truthiness semantics for [matches]
 * FHIRPath expressions return a collection, not a single boolean. This helper
 * follows the standard FHIRPath truthiness rules:
 * - Empty collection → `false`
 * - Single [BooleanType] → its boolean value
 * - Non-empty, non-boolean collection → `true`
 *
 * ### Usage
 * ```kotlin
 * val engine = FhirPathHelper
 *
 * // Boolean predicate
 * val isActive: Boolean = FhirPathHelper.matches(patient, "active = true")
 *
 * // Typed extraction
 * val family: StringType? = FhirPathHelper.evaluateFirst(patient, "name.family", StringType::class.java)
 * ```
 */
internal object FhirPathHelper {

    private val engine: IFhirPath = FhirContext.forR4Cached().newFhirPath()

    /**
     * Evaluates [expression] against [resource] and returns `true` when the result is truthy:
     * - Single [BooleanType] with value `true`
     * - Non-empty collection of any non-boolean values
     *
     * Returns `false` for an empty collection or a [BooleanType] with value `false`.
     *
     * @throws Exception if the expression is syntactically invalid or cannot be evaluated;
     *   callers (e.g. [OperationResult] builder steps) are responsible for catching this.
     */
    fun matches(resource: Base, expression: String): Boolean {
        val results = engine.evaluate(resource, expression, IBase::class.java)
        if (results.isEmpty()) return false
        val first = results.first()
        return if (first is BooleanType) first.booleanValue() else true
    }

    /**
     * Evaluates [expression] against [resource] and returns the first result cast to [type],
     * or `null` if the expression produces no results or the first result is not of [type].
     *
     * @throws Exception if the expression is syntactically invalid or cannot be evaluated.
     */
    fun <T : Base> evaluateFirst(resource: Base, expression: String, type: Class<T>): T? =
        engine.evaluateFirst(resource, expression, type).orElse(null)
}

/**
 * Filters [allValues] to instances of [type] where [expression] evaluates to `true`
 * via [FhirPathHelper.matches].
 *
 * Resources that do not match the expression are silently excluded.
 * Evaluation exceptions propagate to the caller.
 */
internal fun <I : Base> filterByPath(
    allValues: List<Base>,
    type: Class<I>,
    expression: String
): List<I> = allValues.filterIsInstance(type).filter { FhirPathHelper.matches(it, expression) }
