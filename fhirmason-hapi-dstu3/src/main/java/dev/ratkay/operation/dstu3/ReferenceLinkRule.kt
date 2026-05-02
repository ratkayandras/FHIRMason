package dev.ratkay.operation.dstu3

import org.hl7.fhir.dstu3.model.Resource
import kotlin.reflect.KClass

/**
 * Describes an explicit reference-linking rule between two FHIR DSTU3 resource types.
 *
 * @param S the FHIR resource type that holds the reference (the "source")
 * @param T the FHIR resource type being referenced (the "target")
 */
data class ReferenceLinkRule<S : Resource, T : Resource>(
    val sourceType: KClass<S>,
    val targetType: KClass<T>,
    private val setter: (S, T) -> Unit
) {
    @Suppress("UNCHECKED_CAST")
    internal fun applyTo(source: Resource, target: Resource) =
        setter(source as S, target as T)
}
