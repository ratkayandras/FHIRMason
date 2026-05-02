package dev.ratkay.spring

import dev.ratkay.operation.ErrorStrategy
import org.hl7.fhir.r4.model.Patient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class FhirMasonAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(FhirMasonAutoConfiguration::class.java))

    // ── Auto-configuration loads ──────────────────────────────────────────────

    @Test
    fun `auto-configuration registers FhirMasonFactory bean`() {
        contextRunner.run { context ->
            assertTrue(context.containsBean("fhirMasonFactory"))
            assertNotNull(context.getBean(FhirMasonFactory::class.java))
        }
    }

    @Test
    fun `auto-configuration registers FhirMasonProperties bean`() {
        contextRunner.run { context ->
            assertNotNull(context.getBean(FhirMasonProperties::class.java))
        }
    }

    // ── Default property values ───────────────────────────────────────────────

    @Test
    fun `default error strategy is ACCUMULATE`() {
        contextRunner.run { context ->
            val props = context.getBean(FhirMasonProperties::class.java)
            assertEquals(ErrorStrategy.ACCUMULATE, props.errorStrategy)
        }
    }

    @Test
    fun `default metrics enabled is false`() {
        contextRunner.run { context ->
            val props = context.getBean(FhirMasonProperties::class.java)
            assertFalse(props.metrics.enabled)
        }
    }

    // ── Property binding ──────────────────────────────────────────────────────

    @Test
    fun `error strategy binds from application properties`() {
        contextRunner
            .withPropertyValues("fhirmason.error-strategy=FAIL_FAST")
            .run { context ->
                val props = context.getBean(FhirMasonProperties::class.java)
                assertEquals(ErrorStrategy.FAIL_FAST, props.errorStrategy)
            }
    }

    @Test
    fun `metrics enabled binds from application properties`() {
        contextRunner
            .withPropertyValues("fhirmason.metrics.enabled=true")
            .run { context ->
                val props = context.getBean(FhirMasonProperties::class.java)
                assertTrue(props.metrics.enabled)
            }
    }

    // ── FhirMasonFactory behaviour ────────────────────────────────────────────

    @Test
    fun `factory pipeline creates an OperationResult`() {
        contextRunner.run { context ->
            val factory = context.getBean(FhirMasonFactory::class.java)
            val patient = Patient().apply { setId("p1") }
            val result = factory.pipeline(patient)
            assertNotNull(result)
            assertEquals(patient, result.getResult())
        }
    }

    @Test
    fun `factory pipeline has no metrics when metrics disabled`() {
        contextRunner
            .withPropertyValues("fhirmason.metrics.enabled=false")
            .run { context ->
                val factory = context.getBean(FhirMasonFactory::class.java)
                val result = factory.pipeline(Patient().apply { setId("p1") })
                assertTrue(result.getMetrics().isEmpty())
            }
    }

    @Test
    fun `factory pipeline collects metrics when metrics enabled`() {
        contextRunner
            .withPropertyValues("fhirmason.metrics.enabled=true")
            .run { context ->
                val factory = context.getBean(FhirMasonFactory::class.java)
                val result = factory.pipeline(Patient().apply { setId("p1") })
                    .add("enc") { org.hl7.fhir.r4.model.Encounter() }
                assertEquals(1, result.getMetrics().size)
            }
    }

    // ── @ConditionalOnMissingBean ─────────────────────────────────────────────

    @Test
    fun `user-defined FhirMasonFactory bean is not replaced`() {
        val customProps = FhirMasonProperties().apply { errorStrategy = ErrorStrategy.FAIL_FAST }
        val customFactory = FhirMasonFactory(customProps)
        contextRunner
            .withBean(FhirMasonFactory::class.java, { customFactory })
            .run { context ->
                assertSame(customFactory, context.getBean(FhirMasonFactory::class.java))
            }
    }
}
