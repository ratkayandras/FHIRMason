package eu.ratkay.operation

import com.github.karsaig.approvalcrest.jupiter.MatcherAssert.assertThat
import com.github.karsaig.approvalcrest.jupiter.matcher.Matchers.sameBeanAs
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
import org.junit.jupiter.api.Test

class SurtrTest {

    @Test
    fun `get resource when initial resource is not OperationOutcome`() {
        // GIVEN
        val patient = getPatient()

        // WHEN
        val surtr = Surtr.of(patient)

        // THEN
        val expected = getParameters("resource", patient)

        assertThat(surtr.resource, sameBeanAs(expected))
    }

    @Test
    fun `get resource when initial resource is not OperationOutcome with named Resource`() {
        // GIVEN
        val patient = getPatient()

        // WHEN
        val surtr = Surtr.of(patient, "patient")

        // THEN
        val expected = getParameters("patient", patient)

        assertThat(surtr.resource, sameBeanAs(expected))
    }

    @Test
    fun `get resource when initial resource is OperationOutcome but not FATAL or ERROR`() {
        // GIVEN
        val operationOutcome = OperationOutcome()

        // WHEN
        val surtr = Surtr.of(operationOutcome)

        // THEN
        val expected = getParameters("resource", operationOutcome)

        assertThat(surtr.resource, sameBeanAs(expected))
    }

    @Test
    fun `get resource when initial resource is OperationOutcome with FATAL issue`() {
        // GIVEN
        val operationOutcome = getOperationOutcome(IssueSeverity.FATAL)

        // WHEN
        val surtr = Surtr.of(operationOutcome)

        // THEN
        assertThat(surtr.resource, sameBeanAs(operationOutcome))
    }

    @Test
    fun `get resource when initial resource is OperationOutcome with ERROR issue`() {
        // GIVEN
        val operationOutcome = getOperationOutcome(IssueSeverity.ERROR)

        // WHEN
        val surtr = Surtr.of(operationOutcome)

        // THEN
        assertThat(surtr.resource, sameBeanAs(operationOutcome))
    }

    @Test
    fun `operate when initial resource is not OperationOutcome`() {
        // GIVEN
        val patient = getPatient()

        // WHEN
        val surtr = Surtr.of(patient)
            .operate {
                getAppointment()
            }

        // THEN
        val expected = getParameters("resource", getAppointment())

        assertThat(surtr.resource, sameBeanAs(expected))
    }

    @Test
    fun `operate when initial resource is not OperationOutcome but ERROR`() {
        // GIVEN
        val operationOutcome = getOperationOutcome(IssueSeverity.ERROR)

        // WHEN
        val surtr = Surtr.of(operationOutcome)
            .operate {
                getAppointment()
            }

        // THEN
        assertThat(surtr.resource, sameBeanAs(operationOutcome))
    }

    /*
        Hash: d5a7f
     */
    @Test
    fun `operate combined when initial resource is not OperationOutcome`() {
        // GIVEN
        val patient = getPatient()

        // WHEN
        val surtr = Surtr.of(patient)
            .operateCombined {
                getAppointment()
            }

        // THEN
        assertThat(surtr.resource, sameJsonAsApproved())
    }

    @Test
    fun `operate on Resource when initial resource is not OperationOutcome`() {
        // GIVEN
        val patient = getPatient()

        // WHEN
        val surtr = Surtr.of(patient)
            .operateResource { resource ->
                getAppointment(resource)
            }

        // THEN
        val expected = getParameters("resource", getAppointment(patient))

        assertThat(surtr.resource, sameBeanAs(expected))
    }

    @Test
    fun `operate on Resource when initial resource is not OperationOutcome but ERROR`() {
        // GIVEN
        val operationOutcome = getOperationOutcome(IssueSeverity.ERROR)

        // WHEN
        val surtr = Surtr.of(operationOutcome)
            .operateResource { resource ->
                getAppointment(resource)
            }

        // THEN
        assertThat(surtr.resource, sameBeanAs(operationOutcome))
    }

    @Test
    fun `operate on Parameters when initial resource is not OperationOutcome`() {
        // GIVEN
        val patient = getPatient()

        // WHEN
        val surtr = Surtr.of(patient, "patient")
            .operateParameters { params ->
                getAppointment(params.getParameter("patient").resource)
            }

        // THEN
        val expected = getParameters("resource", getAppointment(patient))

        assertThat(surtr.resource, sameBeanAs(expected))
    }

    @Test
    fun `operate on Parameters when initial resource is not OperationOutcome but ERROR`() {
        // GIVEN
        val operationOutcome = getOperationOutcome(IssueSeverity.ERROR)

        // WHEN
        val surtr = Surtr.of(operationOutcome)
            .operateParameters { resource ->
                getAppointment(resource)
            }

        // THEN
        assertThat(surtr.resource, sameBeanAs(operationOutcome))
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