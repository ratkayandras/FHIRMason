package eu.ratkay.operation

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.parser.IParser
import com.github.karsaig.approvalcrest.jupiter.MatcherAssert.assertThat
import com.github.karsaig.approvalcrest.jupiter.matcher.Matchers.sameJsonAsApproved
import org.hl7.fhir.r4.model.Appointment
import org.hl7.fhir.r4.model.HumanName
import org.hl7.fhir.r4.model.OperationOutcome
import org.hl7.fhir.r4.model.OperationOutcome.IssueSeverity
import org.hl7.fhir.r4.model.Parameters
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.Reference
import org.hl7.fhir.r4.model.Resource
import org.hl7.fhir.r4.model.StringType
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class OperationResultTest {

    companion object {
        private lateinit var jsonParser: IParser
        @JvmStatic
        @BeforeAll
        fun setup() {
            jsonParser = FhirContext.forR4().newJsonParser()
        }
    }

    /*
        Hash: 5c96b9
     */
    @Test
    fun `get resource when initial resource is not OperationOutcome`() {
        // GIVEN
        val patient = getPatient()

        // WHEN
        val operationResult = OperationResult.of(patient)

        val actual = jsonParser.encodeResourceToString(operationResult.resource)

        // THEN
        assertThat(actual, sameJsonAsApproved())
    }

    /*
        Hash: a4f839
     */
    @Test
    fun `get resource when initial resource is not OperationOutcome with named Resource`() {
        // GIVEN
        val patient = getPatient()

        // WHEN
        val operationResult = OperationResult.of(patient, "patient")

        val actual = jsonParser.encodeResourceToString(operationResult.resource)

        // THEN
        assertThat(actual, sameJsonAsApproved())
    }

    /*
        Hash: 3876de
     */
    @Test
    fun `get resource when initial resource is OperationOutcome but not FATAL or ERROR`() {
        // GIVEN
        val operationOutcome = OperationOutcome()

        // WHEN
        val operationResult = OperationResult.of(operationOutcome)

        val actual = jsonParser.encodeResourceToString(operationResult.resource)

        // THEN
        assertThat(actual, sameJsonAsApproved())
    }

    /*
        Hash: 36cb0a
     */
    @Test
    fun `get resource when initial resource is OperationOutcome with FATAL issue`() {
        // GIVEN
        val operationOutcome = getOperationOutcome(IssueSeverity.FATAL)

        // WHEN
        val operationResult = OperationResult.of(operationOutcome)

        val actual = jsonParser.encodeResourceToString(operationResult.resource)

        // THEN
        assertThat(actual, sameJsonAsApproved())
    }

    /*
        Hash: 2a55bf
     */
    @Test
    fun `get resource when initial resource is OperationOutcome with ERROR issue`() {
        // GIVEN
        val operationOutcome = getOperationOutcome(IssueSeverity.ERROR)

        // WHEN
        val operationResult = OperationResult.of(operationOutcome)

        val actual = jsonParser.encodeResourceToString(operationResult.resource)

        // THEN
        assertThat(actual, sameJsonAsApproved())
    }

    /*
        Hash: a7344d
     */
    @Test
    fun `operate when initial resource is not OperationOutcome`() {
        // GIVEN
        val patient = getPatient()

        // WHEN
        val operationResult = OperationResult.of(patient)
            .operate {
                getAppointment()
            }

        val actual = jsonParser.encodeResourceToString(operationResult.resource)

        // THEN
        assertThat(actual, sameJsonAsApproved())
    }

    /*
        Hash: a90e46
     */
    @Test
    fun `operate named when initial resource is not OperationOutcome`() {
        // GIVEN
        val patient = getPatient()

        // WHEN
        val operationResult = OperationResult.of(patient)
            .operate("My Custom Appointment") {
                getAppointment()
            }

        val actual = jsonParser.encodeResourceToString(operationResult.resource)

        // THEN
        assertThat(actual, sameJsonAsApproved())
    }

    /*
        Hash: 63d1fb
     */
    @Test
    fun `operate when initial resource is OperationOutcome but ERROR`() {
        // GIVEN
        val operationOutcome = getOperationOutcome(IssueSeverity.ERROR)

        // WHEN
        val operationResult = OperationResult.of(operationOutcome)
            .operate {
                getAppointment()
            }

        val actual = jsonParser.encodeResourceToString(operationResult.resource)

        // THEN
        assertThat(actual, sameJsonAsApproved())
    }

    /*
        Hash: fa2270
     */
    @Test
    fun `operate named when initial resource is OperationOutcome but ERROR`() {
        // GIVEN
        val operationOutcome = getOperationOutcome(IssueSeverity.ERROR)

        // WHEN
        val operationResult = OperationResult.of(operationOutcome)
            .operate("Error will show") {
                getAppointment()
            }

        val actual = jsonParser.encodeResourceToString(operationResult.resource)

        // THEN
        assertThat(actual, sameJsonAsApproved())
    }

    /*
        Hash: d5a1c0
     */
    @Test
    fun `operate combined when initial resource is not OperationOutcome`() {
        // GIVEN
        val patient = getPatient()

        // WHEN
        val operationResult = OperationResult.of(patient)
            .operateCombined {
                getAppointment()
            }

        val actual = jsonParser.encodeResourceToString(operationResult.resource)

        // THEN
        assertThat(actual, sameJsonAsApproved())
    }

    /*
        Hash: 245f27
     */
    @Test
    fun `operate named combined when initial resource is not OperationOutcome`() {
        // GIVEN
        val patient = getPatient()

        // WHEN
        val operationResult = OperationResult.of(patient)
            .operateCombined("My Custom Appointment") {
                getAppointment()
            }

        val actual = jsonParser.encodeResourceToString(operationResult.resource)

        // THEN
        assertThat(actual, sameJsonAsApproved())
    }

    /*
        Hash: 532b73
     */
    @Test
    fun `operate and combined both named when initial resource is not OperationOutcome`() {
        // GIVEN
        val patient = getPatient()

        // WHEN
        val operationResult = OperationResult.of(patient, "My Custom Patient")
            .operateCombined("My Custom Appointment") {
                getAppointment()
            }

        val actual = jsonParser.encodeResourceToString(operationResult.resource)

        // THEN
        assertThat(actual, sameJsonAsApproved())
    }

    /*
        Hash: 830218
     */
    @Test
    fun `operate combined when initial resource is not OperationOutcome and add multiple`() {
        // GIVEN
        val patient = getPatient()

        // WHEN
        val operationResult = OperationResult.of(patient)
            .operateCombined { getAppointment() }
            .operateCombined { getOperationOutcome(IssueSeverity.INFORMATION) }
            .operateCombined { getParameters("theName", Patient()) }

        val actual = jsonParser.encodeResourceToString(operationResult.resource)

        // THEN
        assertThat(actual, sameJsonAsApproved())
    }

    /*
        Hash: eb7dca
     */
    @Test
    fun `operate named combined when initial resource is not OperationOutcome and add multiple`() {
        // GIVEN
        val patient = getPatient()

        // WHEN
        val operationResult = OperationResult.of(patient)
            .operateCombined("My Custom Appointment") { getAppointment() }
            .operateCombined("My Custom OperationOutcome") { getOperationOutcome(IssueSeverity.INFORMATION) }
            .operateCombined("Won't show") { getParameters("theName", Patient()) }

        val actual = jsonParser.encodeResourceToString(operationResult.resource)

        // THEN
        assertThat(actual, sameJsonAsApproved())
    }

    /*
        Hash: fdb30a
     */
    @Test
    fun `operate combined when initial resource is not OperationOutcome and add multiple ERROR created during operation chain`() {
        // GIVEN
        val patient = getPatient()

        // WHEN
        val operationResult = OperationResult.of(patient)
            .operateCombined { getAppointment() }
            .operateCombined { getOperationOutcome(IssueSeverity.ERROR) }
            .operateCombined { getParameters("theName", Patient()) }

        val actual = jsonParser.encodeResourceToString(operationResult.resource)

        // THEN
        assertThat(actual, sameJsonAsApproved())
    }

    /*
        Hash: c762d8
     */
    @Test
    fun `operate resource combined when initial resource is not OperationOutcome`() {
        // GIVEN
        val patient = getPatient()

        // WHEN
        val operationResult = OperationResult.of(patient)
            .operateResourceCombined { patientResource -> getAppointment(patientResource) }

        val actual = jsonParser.encodeResourceToString(operationResult.resource)

        // THEN
        assertThat(actual, sameJsonAsApproved())
    }

    /*
        Hash: b768cc
     */
    @Test
    fun `operate resource combined when initial resource is OperationOutcome but ERROR`() {
        // GIVEN
        val patient = getPatient()

        // WHEN
        val operationResult = OperationResult.of(patient)
            .operateResourceCombined { _ -> getOperationOutcome(IssueSeverity.ERROR) }

        val actual = jsonParser.encodeResourceToString(operationResult.resource)

        // THEN
        assertThat(actual, sameJsonAsApproved())
    }

    /*
        Hash: 7192d9
     */
    @Test
    fun `operate resource combined when initial resource is OperationOutcome but INFORMATION`() {
        // GIVEN
        val patient = getPatient()

        // WHEN
        val operationResult = OperationResult.of(patient)
            .operateResourceCombined { _ -> getOperationOutcome(IssueSeverity.INFORMATION) }

        val actual = jsonParser.encodeResourceToString(operationResult.resource)

        // THEN
        assertThat(actual, sameJsonAsApproved())
    }

    /*
        Hash: ddbdc4
     */
    @Test
    fun `operate combined when initial resource is OperationOutcome but ERROR`() {
        // GIVEN
        val operationOutcome = getOperationOutcome(IssueSeverity.ERROR)

        // WHEN
        val operationResult = OperationResult.of(operationOutcome)
            .operateCombined {
                getAppointment()
            }

        val actual = jsonParser.encodeResourceToString(operationResult.resource)

        // THEN
        assertThat(actual, sameJsonAsApproved())
    }

    /*
        Hash: 32e928
     */
    @Test
    fun `operate named resource combined when initial resource is not OperationOutcome`() {
        // GIVEN
        val patient = getPatient()

        // WHEN
        val operationResult = OperationResult.of(patient)
            .operateResourceCombined("MyAppointment") { patientResource -> getAppointment(patientResource) }

        val actual = jsonParser.encodeResourceToString(operationResult.resource)

        // THEN
        assertThat(actual, sameJsonAsApproved())
    }

    /*
        Hash: 397385
     */
    @Test
    fun `operate named resource combined when initial resource is OperationOutcome but ERROR`() {
        // GIVEN
        val patient = getPatient()

        // WHEN
        val operationResult = OperationResult.of(patient)
            .operateResourceCombined("Won't show") { _ -> getOperationOutcome(IssueSeverity.ERROR) }

        val actual = jsonParser.encodeResourceToString(operationResult.resource)

        // THEN
        assertThat(actual, sameJsonAsApproved())
    }

    /*
        Hash: e38af5
     */
    @Test
    fun `operate named resource combined when initial resource is OperationOutcome but INFORMATION`() {
        // GIVEN
        val patient = getPatient()

        // WHEN
        val operationResult = OperationResult.of(patient)
            .operateResourceCombined("MyOperationOutcome") { _ -> getOperationOutcome(IssueSeverity.INFORMATION) }

        val actual = jsonParser.encodeResourceToString(operationResult.resource)

        // THEN
        assertThat(actual, sameJsonAsApproved())
    }

    /*
        Hash: bc2232
     */
    @Test
    fun `operate on Resource when initial resource is not OperationOutcome`() {
        // GIVEN
        val patient = getPatient()

        // WHEN
        val operationResult = OperationResult.of(patient)
            .operateResource { resource ->
                getAppointment(resource)
            }

        val actual = jsonParser.encodeResourceToString(operationResult.resource)

        // THEN
        assertThat(actual, sameJsonAsApproved())
    }

    /*
        Hash: e229e3
     */
    @Test
    fun `operate on Resource named when initial resource is not OperationOutcome`() {
        // GIVEN
        val patient = getPatient()

        // WHEN
        val operationResult = OperationResult.of(patient)
            .operateResource("MyPatient") { resource ->
                getAppointment(resource)
            }

        val actual = jsonParser.encodeResourceToString(operationResult.resource)

        // THEN
        assertThat(actual, sameJsonAsApproved())
    }

    /*
        Hash: b697f6
     */
    @Test
    fun `operate on Resource when initial resource is OperationOutcome but ERROR`() {
        // GIVEN
        val operationOutcome = getOperationOutcome(IssueSeverity.ERROR)

        // WHEN
        val operationResult = OperationResult.of(operationOutcome)
            .operateResource { resource ->
                getAppointment(resource)
            }

        val actual = jsonParser.encodeResourceToString(operationResult.resource)

        // THEN
        assertThat(actual, sameJsonAsApproved())
    }

    /*
        Hash: d9ca8a
     */
    @Test
    fun `operate on Resource named when initial resource is OperationOutcome but ERROR`() {
        // GIVEN
        val operationOutcome = getOperationOutcome(IssueSeverity.ERROR)

        // WHEN
        val operationResult = OperationResult.of(operationOutcome)
            .operateResource("Won't show") { resource ->
                getAppointment(resource)
            }

        val actual = jsonParser.encodeResourceToString(operationResult.resource)

        // THEN
        assertThat(actual, sameJsonAsApproved())
    }

    /*
        Hash: dfb4e6
     */
    @Test
    fun `operate on Parameters when initial resource is not OperationOutcome`() {
        // GIVEN
        val patient = getPatient()

        // WHEN
        val operationResult = OperationResult.of(patient, "patient")
            .operateParameters { params ->
                getAppointment(params.getParameter("patient").resource)
            }

        val actual = jsonParser.encodeResourceToString(operationResult.resource)

        // THEN
        assertThat(actual, sameJsonAsApproved())
    }

    /*
        Hash: 63efc4
     */
    @Test
    fun `operate on Parameters named when initial resource is not OperationOutcome`() {
        // GIVEN
        val patient = getPatient()

        // WHEN
        val operationResult = OperationResult.of(patient, "patient")
            .operateParameters("MyAppointment") { params ->
                getAppointment(params.getParameter("patient").resource)
            }

        val actual = jsonParser.encodeResourceToString(operationResult.resource)

        // THEN
        assertThat(actual, sameJsonAsApproved())
    }

    /*
        Hash: 7bf7f5
     */
    @Test
    fun `operate on Parameters combined when initial resource is not OperationOutcome`() {
        // GIVEN
        val patient = getPatient()

        // WHEN
        val operationResult = OperationResult.of(patient, "patient")
            .operateParametersCombined { params ->
                getAppointment(params.getParameter("patient").resource)
            }

        val actual = jsonParser.encodeResourceToString(operationResult.resource)

        // THEN
        assertThat(actual, sameJsonAsApproved())
    }

    /*
        Hash: 11cb5c
     */
    @Test
    fun `operate on Parameters named combined when initial resource is not OperationOutcome`() {
        // GIVEN
        val patient = getPatient()

        // WHEN
        val operationResult = OperationResult.of(patient, "patient")
            .operateParametersCombined("MyAppointment") { params ->
                getAppointment(params.getParameter("patient").resource)
            }

        val actual = jsonParser.encodeResourceToString(operationResult.resource)

        // THEN
        assertThat(actual, sameJsonAsApproved())
    }

    /*
        Hash: ec3b62
     */
    @Test
    fun `operate on Parameters when initial resource is OperationOutcome but ERROR`() {
        // GIVEN
        val operationOutcome = getOperationOutcome(IssueSeverity.ERROR)

        // WHEN
        val operationResult = OperationResult.of(operationOutcome)
            .operateParameters { resource ->
                getAppointment(resource)
            }

        val actual = jsonParser.encodeResourceToString(operationResult.resource)

        // THEN
        assertThat(actual, sameJsonAsApproved())
    }

    /*
        Hash: f665c6
     */
    @Test
    fun `operate on Parameters named when initial resource is OperationOutcome but ERROR`() {
        // GIVEN
        val operationOutcome = getOperationOutcome(IssueSeverity.ERROR)

        // WHEN
        val operationResult = OperationResult.of(operationOutcome)
            .operateParameters("Won't show") { resource ->
                getAppointment(resource)
            }

        val actual = jsonParser.encodeResourceToString(operationResult.resource)

        // THEN
        assertThat(actual, sameJsonAsApproved())
    }

    /*
        Hash: 11e464
     */
    @Test
    fun `operate on Parameters combined when initial resource is OperationOutcome but ERROR`() {
        // GIVEN
        val operationOutcome = getOperationOutcome(IssueSeverity.ERROR)

        // WHEN
        val operationResult = OperationResult.of(operationOutcome)
            .operateParametersCombined { resource ->
                getAppointment(resource)
            }

        val actual = jsonParser.encodeResourceToString(operationResult.resource)

        // THEN
        assertThat(actual, sameJsonAsApproved())
    }

    /*
        Hash: cb75e9
     */
    @Test
    fun `operate on Parameters named combined when initial resource is OperationOutcome but ERROR`() {
        // GIVEN
        val operationOutcome = getOperationOutcome(IssueSeverity.ERROR)

        // WHEN
        val operationResult = OperationResult.of(operationOutcome)
            .operateParametersCombined("Won't show") { resource ->
                getAppointment(resource)
            }

        val actual = jsonParser.encodeResourceToString(operationResult.resource)

        // THEN
        assertThat(actual, sameJsonAsApproved())
    }

    /*
        Hash: 9e64e7
     */
    @Test
    fun `operate on Parameters combined when initial resource is OperationOutcome but INFORMATION`() {
        // GIVEN
        val operationOutcome = getOperationOutcome(IssueSeverity.INFORMATION)

        // WHEN
        val operationResult = OperationResult.of(operationOutcome)
            .operateParametersCombined { resource ->
                getAppointment(resource)
            }

        val actual = jsonParser.encodeResourceToString(operationResult.resource)

        // THEN
        assertThat(actual, sameJsonAsApproved())
    }

    /*
        Hash: e21612
     */
    @Test
    fun `operate on Parameters named combined when initial resource is OperationOutcome but INFORMATION`() {
        // GIVEN
        val operationOutcome = getOperationOutcome(IssueSeverity.INFORMATION)

        // WHEN
        val operationResult = OperationResult.of(operationOutcome)
            .operateParametersCombined("MyAppointment") { resource ->
                getAppointment(resource)
            }

        val actual = jsonParser.encodeResourceToString(operationResult.resource)

        // THEN
        assertThat(actual, sameJsonAsApproved())
    }

    private fun getPatient(): Patient {
        return Patient().apply {
            name = listOf(HumanName().apply {
                family = "Doe"
                given = listOf(StringType("John"))
            })
        }
    }

    private fun getOperationOutcome(issueSeverity: IssueSeverity): OperationOutcome {
        return OperationOutcome().apply {
            issue = listOf(OperationOutcome.OperationOutcomeIssueComponent().apply {
                severity = issueSeverity
            })
        }
    }

    private fun getParameters(theName: String, theResource: Resource): Parameters {
        return Parameters().apply {
            parameter = listOf(Parameters.ParametersParameterComponent().apply {
                name = theName
                resource = theResource
            })
        }
    }

    private fun getAppointment(resource: Resource? = OperationOutcome()): Appointment {
        return Appointment().apply {
            status = Appointment.AppointmentStatus.BOOKED
            participant = listOf(Appointment.AppointmentParticipantComponent().apply {
                actor = Reference(resource).apply {
                    display = resource?.fhirType()
                }
            })
        }
    }
}