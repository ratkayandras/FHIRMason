package dev.ratkay.operation.r4

import org.hl7.fhir.r4.model.Base
import org.hl7.fhir.r4.model.Identifier
import kotlin.reflect.KClass

internal fun identifiersOf(resource: Base): List<Identifier> =
    resource.children()
        .firstOrNull { it.name == "identifier" }
        ?.values
        ?.filterIsInstance<Identifier>()
        ?: emptyList()

internal fun <I : Base> filterByIdentifierSystem(
    allValues: List<Base>,
    type: KClass<I>,
    system: String
): List<I> = allValues.filterIsInstance(type.java)
    .filter { resource -> identifiersOf(resource).any { it.system == system } }

internal fun <I : Base> filterByIdentifierValue(
    allValues: List<Base>,
    type: KClass<I>,
    identifierValue: String
): List<I> = allValues.filterIsInstance(type.java)
    .filter { resource -> identifiersOf(resource).any { it.value == identifierValue } }

internal fun <I : Base> filterByIdentifier(
    allValues: List<Base>,
    type: KClass<I>,
    system: String,
    identifierValue: String
): List<I> = allValues.filterIsInstance(type.java)
    .filter { resource ->
        identifiersOf(resource).any { it.system == system && it.value == identifierValue }
    }
