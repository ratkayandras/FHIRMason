package dev.ratkay.extension

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.parser.IParser
import com.github.karsaig.approvalcrest.jupiter.MatcherAssert.assertThat
import com.github.karsaig.approvalcrest.jupiter.matcher.Matchers.sameJsonAsApproved
import dev.ratkay.getParametersWithPatientAndOperationOutcome
import dev.ratkay.getParametersWithStringValue
import dev.ratkay.getParametersWithTwoPatients
import org.hl7.fhir.r4.model.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class ParametersExtensionTest {

    companion object {
        private lateinit var jsonParser: IParser

        @JvmStatic
        @BeforeAll
        fun setup() {
            jsonParser = FhirContext.forR4().newJsonParser()
        }
    }

    @Test
    fun `getResource extension returns expected resource and type`() {
        val parameters = getParametersWithPatientAndOperationOutcome()

        val patient = parameters.getResource<Patient>("patient")

        val patientJson = jsonParser.encodeResourceToString(patient)

        assertThat(patientJson, sameJsonAsApproved())
    }

    @Test
    fun `getResource throws NoSuchElementException when parameter does not exist`() {
        val parameters = getParametersWithPatientAndOperationOutcome()

        assertThrows<NoSuchElementException> { parameters.getResource<Patient>("missing") }
    }

    @Test
    fun `getResource throws IllegalArgumentException when multiple parameter matches the parameter name`() {
        val parameters = getParametersWithTwoPatients()

        assertThrows<IllegalArgumentException> { parameters.getResource<Patient>("patient") }
    }

    @Test
    fun `getResource throws IllegalArgumentException when type of parameter does not match`() {
        val parameters = getParametersWithPatientAndOperationOutcome()

        assertThrows<IllegalArgumentException> { parameters.getResource<RelatedPerson>("patient") }
    }

    @Test
    fun `getResource throws IllegalArgumentException when parameter does not have a resource under parameter name`() {
        val parameters = getParametersWithStringValue()

        assertThrows<IllegalArgumentException> { parameters.getResource<RelatedPerson>("name") }
    }

    @Test
    fun `getResources returns a list of expected type`() {
        val parameters = getParametersWithTwoPatients()

        val patients: List<Patient> = parameters.getResources("patient")

        val jsonList = patients.map { jsonParser.encodeResourceToString(it) }

        assertThat(jsonList, sameJsonAsApproved())
    }

    @Test
    fun `getResources returns empty list if type does not match`() {
        val parameters = getParametersWithTwoPatients()

        val relatedPeople: List<RelatedPerson> = parameters.getResources("patient")

        val jsonList = relatedPeople.map { jsonParser.encodeResourceToString(it) }

        assertEquals(0, jsonList.size)
    }

    @Test
    fun `getResources returns empty list if paramName does not match`() {
        val parameters = getParametersWithTwoPatients()

        val patients: List<Patient> = parameters.getResources("relatedPerson")

        val jsonList = patients.map { jsonParser.encodeResourceToString(it) }

        assertEquals(0, jsonList.size)
    }
}