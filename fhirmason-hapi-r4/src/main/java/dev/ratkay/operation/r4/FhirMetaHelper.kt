package dev.ratkay.operation.r4

import org.hl7.fhir.r4.model.Base
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.Resource

internal fun metaTagsOf(resource: Base): List<Coding> =
    if (resource is Resource) resource.meta.tag else emptyList()

internal fun metaSecurityOf(resource: Base): List<Coding> =
    if (resource is Resource) resource.meta.security else emptyList()

internal fun metaProfilesOf(resource: Base): List<String> =
    if (resource is Resource) resource.meta.profile.map { it.value } else emptyList()

internal fun <I : Base> filterByMetaTagSystem(
    allValues: List<Base>,
    type: Class<I>,
    system: String
): List<I> = allValues.filterIsInstance(type)
    .filter { resource -> metaTagsOf(resource).any { it.system == system } }

internal fun <I : Base> filterByMetaTagCode(
    allValues: List<Base>,
    type: Class<I>,
    code: String
): List<I> = allValues.filterIsInstance(type)
    .filter { resource -> metaTagsOf(resource).any { it.code == code } }

internal fun <I : Base> filterByMetaTag(
    allValues: List<Base>,
    type: Class<I>,
    system: String,
    code: String
): List<I> = allValues.filterIsInstance(type)
    .filter { resource -> metaTagsOf(resource).any { it.system == system && it.code == code } }

internal fun <I : Base> filterByMetaSecuritySystem(
    allValues: List<Base>,
    type: Class<I>,
    system: String
): List<I> = allValues.filterIsInstance(type)
    .filter { resource -> metaSecurityOf(resource).any { it.system == system } }

internal fun <I : Base> filterByMetaSecurityCode(
    allValues: List<Base>,
    type: Class<I>,
    code: String
): List<I> = allValues.filterIsInstance(type)
    .filter { resource -> metaSecurityOf(resource).any { it.code == code } }

internal fun <I : Base> filterByMetaSecurity(
    allValues: List<Base>,
    type: Class<I>,
    system: String,
    code: String
): List<I> = allValues.filterIsInstance(type)
    .filter { resource -> metaSecurityOf(resource).any { it.system == system && it.code == code } }

internal fun <I : Base> filterByMetaProfile(
    allValues: List<Base>,
    type: Class<I>,
    url: String
): List<I> = allValues.filterIsInstance(type)
    .filter { resource -> metaProfilesOf(resource).any { it == url } }
