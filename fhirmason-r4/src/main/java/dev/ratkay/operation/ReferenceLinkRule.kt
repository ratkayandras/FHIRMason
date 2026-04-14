package dev.ratkay.operation

import org.hl7.fhir.r4.model.Resource
import kotlin.reflect.KClass

/**
 * Describes an explicit reference-linking rule between two FHIR resource types.
 *
 * When [OperationResult.linkReferences] is called with a list of rules, each rule is
 * applied to every matching (source, target) pair found in the accumulated parameters.
 * The [setter] lambda receives the strongly-typed source and target so callers don't
 * need to cast.
 *
 * Example:
 * ```kotlin
 * val rule = ReferenceLinkRule(
 *     sourceType = Encounter::class,
 *     targetType = Patient::class,
 *     setter     = { encounter, patient -> encounter.subject = Reference("Patient/${patient.idPart}") }
 * )
 * ```
 *
 * @param S the FHIR resource type that holds the reference (the "source")
 * @param T the FHIR resource type being referenced (the "target")
 */
data class ReferenceLinkRule<S : Resource, T : Resource>(
    val sourceType: KClass<S>,
    val targetType: KClass<T>,
    private val setter: (S, T) -> Unit
) {
    /**
     * Applies the rule's [setter] to the given [source] and [target] after an unchecked
     * downcast.  The cast is safe because callers (inside [OperationResult]) only call
     * this method after verifying that [source] is an instance of [sourceType] and
     * [target] is an instance of [targetType].
     */
    @Suppress("UNCHECKED_CAST")
    internal fun applyTo(source: Resource, target: Resource) =
        setter(source as S, target as T)
}
