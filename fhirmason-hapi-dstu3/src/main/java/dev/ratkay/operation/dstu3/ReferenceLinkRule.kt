package dev.ratkay.operation.dstu3

import org.hl7.fhir.dstu3.model.Resource
import java.util.function.BiConsumer
import kotlin.reflect.KClass

/**
 * Describes an explicit reference-linking rule between two FHIR DSTU3 resource types.
 *
 * When [OperationResult.linkReferences] is called with a list of rules, each rule is
 * applied to every matching (source, target) pair found in the accumulated parameters.
 * The [setter] lambda receives the strongly-typed source and target so callers don't
 * need to cast.
 *
 * Kotlin callers use the primary constructor with `KClass` and a lambda:
 * ```kotlin
 * val rule = ReferenceLinkRule(
 *     sourceType = Encounter::class,
 *     targetType = Patient::class,
 *     setter     = { encounter, patient -> encounter.subject = Reference("Patient/${patient.idPart}") }
 * )
 * ```
 *
 * Java callers use the [of] factory with `Class` and a `BiConsumer`:
 * ```java
 * ReferenceLinkRule<Encounter, Patient> rule = ReferenceLinkRule.of(
 *     Encounter.class, Patient.class,
 *     (encounter, patient) -> encounter.setSubject(new Reference("Patient/" + patient.getIdPart()))
 * );
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

    companion object {
        /**
         * Java-friendly factory for [ReferenceLinkRule] — accepts [Class] and [BiConsumer]
         * instead of [KClass] and a Kotlin lambda.
         *
         * Example:
         * ```java
         * ReferenceLinkRule<Encounter, Patient> rule = ReferenceLinkRule.of(
         *     Encounter.class, Patient.class,
         *     (encounter, patient) ->
         *         encounter.setSubject(new Reference("Patient/" + patient.getIdPart()))
         * );
         * ```
         *
         * @param sourceType the Java [Class] of the resource that holds the reference.
         * @param targetType the Java [Class] of the resource being referenced.
         * @param setter [BiConsumer] that wires the reference from [source] to [target].
         */
        @JvmStatic
        fun <S : Resource, T : Resource> of(
            sourceType: Class<S>,
            targetType: Class<T>,
            setter: BiConsumer<S, T>
        ): ReferenceLinkRule<S, T> =
            ReferenceLinkRule(sourceType.kotlin, targetType.kotlin) { s, t -> setter.accept(s, t) }
    }
}
