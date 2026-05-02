package dev.ratkay.spring

import dev.ratkay.operation.ErrorStrategy
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration properties for FHIRMason, bound from the `fhirmason` prefix.
 *
 * Example `application.yml`:
 * ```yaml
 * fhirmason:
 *   error-strategy: ACCUMULATE
 *   metrics:
 *     enabled: true
 * ```
 */
@ConfigurationProperties(prefix = "fhirmason")
class FhirMasonProperties {
    /** Controls how pipeline errors are handled. Defaults to [ErrorStrategy.ACCUMULATE]. */
    var errorStrategy: ErrorStrategy = ErrorStrategy.ACCUMULATE

    /** Metrics configuration for FHIRMason pipelines and DAGs. */
    val metrics: MetricsProperties = MetricsProperties()

    /** Configuration properties controlling per-step metrics collection. */
    class MetricsProperties {
        /** When true, every pipeline created by [FhirMasonFactory] will have timing enabled. */
        var enabled: Boolean = false
    }
}
