package dev.ratkay.operation.dstu3

import dev.ratkay.operation.DateTimeInput
import org.hl7.fhir.dstu3.model.DateTimeType
import org.hl7.fhir.dstu3.model.DateType
import org.hl7.fhir.dstu3.model.InstantType
import org.hl7.fhir.dstu3.model.TimeType
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.Year
import java.time.YearMonth
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Date

/**
 * Maps common Java/Kotlin date-time types to their HAPI FHIR DSTU3 equivalents.
 *
 * All functions are pure and stateless.
 */
object FhirDateTimeConverter {

    private val LOCAL_DT_FORMATTER   = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
    private val OFFSET_DT_FORMATTER  = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

    // ── DateType ──────────────────────────────────────────────────────────────

    fun toFhirDate(value: LocalDate): DateType = DateType(value.toString())
    fun toFhirDate(value: YearMonth): DateType = DateType(value.toString())
    fun toFhirDate(value: Year): DateType = DateType(value.toString())
    fun toFhirDate(value: Date): DateType = DateType(value)
    fun toFhirDate(value: LocalDateTime): DateType = DateType(value.toLocalDate().toString())
    fun toFhirDate(value: ZonedDateTime): DateType = DateType(value.toLocalDate().toString())
    fun toFhirDate(value: OffsetDateTime): DateType = DateType(value.toLocalDate().toString())

    // ── DateTimeType ──────────────────────────────────────────────────────────

    fun toFhirDateTime(value: LocalDateTime): DateTimeType = DateTimeType(value.format(LOCAL_DT_FORMATTER))
    fun toFhirDateTime(value: ZonedDateTime): DateTimeType = DateTimeType(value.format(OFFSET_DT_FORMATTER))
    fun toFhirDateTime(value: OffsetDateTime): DateTimeType = DateTimeType(value.format(OFFSET_DT_FORMATTER))
    fun toFhirDateTime(value: Date): DateTimeType = DateTimeType(value)
    fun toFhirDateTime(value: Calendar): DateTimeType = DateTimeType(value)
    fun toFhirDateTime(value: Instant): DateTimeType = DateTimeType(Date.from(value))

    // ── InstantType ───────────────────────────────────────────────────────────

    fun toFhirInstant(value: Instant): InstantType = InstantType(Date.from(value))
    fun toFhirInstant(value: ZonedDateTime): InstantType = InstantType(Date.from(value.toInstant()))
    fun toFhirInstant(value: OffsetDateTime): InstantType = InstantType(Date.from(value.toInstant()))
    fun toFhirInstant(value: Date): InstantType = InstantType(value)

    // ── TimeType ──────────────────────────────────────────────────────────────

    fun toFhirTime(value: LocalTime): TimeType = TimeType(value.toString())

    // ── DateTimeInput dispatch ─────────────────────────────────────────────────

    fun toFhirDate(value: DateTimeInput): DateType = when (value) {
        is DateTimeInput.OfString        -> DateType(value.value)
        is DateTimeInput.OfLocalDate     -> toFhirDate(value.value)
        is DateTimeInput.OfYearMonth     -> toFhirDate(value.value)
        is DateTimeInput.OfYear          -> toFhirDate(value.value)
        is DateTimeInput.OfDate          -> toFhirDate(value.value)
        is DateTimeInput.OfLocalDateTime -> toFhirDate(value.value)
        is DateTimeInput.OfZonedDateTime -> toFhirDate(value.value)
        is DateTimeInput.OfOffsetDateTime -> toFhirDate(value.value)
        is DateTimeInput.OfInstant, is DateTimeInput.OfCalendar, is DateTimeInput.OfLocalTime ->
            throw IllegalArgumentException("${value::class.simpleName} cannot be converted to a FHIR date")
    }

    fun toFhirDateTime(value: DateTimeInput): DateTimeType = when (value) {
        is DateTimeInput.OfString         -> DateTimeType(value.value)
        is DateTimeInput.OfLocalDateTime  -> toFhirDateTime(value.value)
        is DateTimeInput.OfZonedDateTime  -> toFhirDateTime(value.value)
        is DateTimeInput.OfOffsetDateTime -> toFhirDateTime(value.value)
        is DateTimeInput.OfInstant        -> toFhirDateTime(value.value)
        is DateTimeInput.OfDate           -> toFhirDateTime(value.value)
        is DateTimeInput.OfCalendar       -> toFhirDateTime(value.value)
        is DateTimeInput.OfLocalDate, is DateTimeInput.OfYearMonth,
        is DateTimeInput.OfYear, is DateTimeInput.OfLocalTime ->
            throw IllegalArgumentException("${value::class.simpleName} cannot be converted to a FHIR dateTime")
    }

    fun toFhirInstant(value: DateTimeInput): InstantType = when (value) {
        is DateTimeInput.OfString         -> InstantType(value.value)
        is DateTimeInput.OfInstant        -> toFhirInstant(value.value)
        is DateTimeInput.OfZonedDateTime  -> toFhirInstant(value.value)
        is DateTimeInput.OfOffsetDateTime -> toFhirInstant(value.value)
        is DateTimeInput.OfDate           -> toFhirInstant(value.value)
        is DateTimeInput.OfLocalDate, is DateTimeInput.OfYearMonth, is DateTimeInput.OfYear,
        is DateTimeInput.OfLocalDateTime, is DateTimeInput.OfCalendar, is DateTimeInput.OfLocalTime ->
            throw IllegalArgumentException("${value::class.simpleName} cannot be converted to a FHIR instant")
    }

    fun toFhirTime(value: DateTimeInput): TimeType = when (value) {
        is DateTimeInput.OfString    -> TimeType(value.value)
        is DateTimeInput.OfLocalTime -> toFhirTime(value.value)
        is DateTimeInput.OfLocalDate, is DateTimeInput.OfYearMonth, is DateTimeInput.OfYear,
        is DateTimeInput.OfLocalDateTime, is DateTimeInput.OfZonedDateTime,
        is DateTimeInput.OfOffsetDateTime, is DateTimeInput.OfInstant,
        is DateTimeInput.OfDate, is DateTimeInput.OfCalendar ->
            throw IllegalArgumentException("${value::class.simpleName} cannot be converted to a FHIR time")
    }
}
