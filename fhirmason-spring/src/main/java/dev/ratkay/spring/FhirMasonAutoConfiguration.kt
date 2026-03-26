package dev.ratkay.spring

import dev.ratkay.operation.OperationResult
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Spring Boot auto-configuration for FHIRMason.
 *
 * Activates when [OperationResult] is on the classpath (i.e. `fhirmason-core` is a dependency).
 * Registers a [FhirMasonFactory] bean backed by [FhirMasonProperties].
 *
 * To override the auto-configured factory, declare your own [FhirMasonFactory] bean.
 */
@Configuration
@ConditionalOnClass(OperationResult::class)
@EnableConfigurationProperties(FhirMasonProperties::class)
open class FhirMasonAutoConfiguration(private val properties: FhirMasonProperties) {

    @Bean
    @ConditionalOnMissingBean
    open fun fhirMasonFactory(): FhirMasonFactory = FhirMasonFactory(properties)
}
