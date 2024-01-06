package eu.ratkay.dto

import org.hl7.fhir.r4.model.Resource

data class ResourceHolder(val name: String, val resource: Resource)