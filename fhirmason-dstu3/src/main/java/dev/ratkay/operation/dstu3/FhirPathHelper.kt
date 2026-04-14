package dev.ratkay.operation.dstu3

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.fhirpath.IFhirPath
import org.hl7.fhir.dstu3.model.Base
import org.hl7.fhir.dstu3.model.BooleanType
import org.hl7.fhir.instance.model.api.IBase

/**
 * Utility object for evaluating FHIRPath expressions against FHIR DSTU3 resources.
 *
 * Wraps HAPI FHIR's [IFhirPath] engine, obtained once from [FhirContext.forDstu3Cached]
 * and reused for all evaluations.
 */
internal object FhirPathHelper {

    private val engine: IFhirPath = FhirContext.forDstu3Cached().newFhirPath()

    /**
     * Evaluates [expression] against [resource] and returns `true` when the result is truthy:
     * - Single [BooleanType] with value `true`
     * - Non-empty collection of any non-boolean values
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
     */
    fun <T : Base> evaluateFirst(resource: Base, expression: String, type: Class<T>): T? =
        engine.evaluateFirst(resource, expression, type).orElse(null)
}

/**
 * Filters [allValues] to instances of [type] where [expression] evaluates to `true`
 * via [FhirPathHelper.matches].
 */
internal fun <I : Base> filterByPath(
    allValues: List<Base>,
    type: Class<I>,
    expression: String
): List<I> = allValues.filterIsInstance(type).filter { FhirPathHelper.matches(it, expression) }
