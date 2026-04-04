package dev.ratkay.operation

import org.hl7.fhir.r4.model.DateTimeType
import org.hl7.fhir.r4.model.DateType
import org.hl7.fhir.r4.model.InstantType
import org.hl7.fhir.r4.model.TimeType
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
 * Maps common Java/Kotlin date-time types to their HAPI FHIR R4 equivalents.
 *
 * All functions are pure and stateless. This object can be used directly by library consumers
 * who need to convert between the Java type system and FHIR primitive types outside of an
 * [OperationResult] pipeline.
 *
 * ### Conversion strategies
 * - `java.time` types: formatted via `toString()` (ISO-8601) and passed to the HAPI string
 *   constructor, which preserves precision and timezone exactly as expressed in the source value.
 * - `java.util.Date` / `java.util.Calendar`: passed directly to the HAPI constructor that
 *   accepts them.
 *
 * ### Timezone behaviour
 * - [LocalDate], [YearMonth], [Year]: no timezone — FHIR date is zone-agnostic.
 * - [LocalDateTime]: no timezone — FHIR allows zone-less dateTime.
 * - [ZonedDateTime] / [OffsetDateTime]: timezone is preserved in the FHIR string.
 * - [Instant] / [Date]: converted to UTC milliseconds; HAPI renders them with a UTC offset.
 */
object FhirDateTimeConverter {

    /**
     * FHIR dateTime requires at least second precision when a time component is present.
     * Java's `toString()` on `LocalDateTime`/`ZonedDateTime`/`OffsetDateTime` omits trailing
     * zero fields (e.g. `"2024-01-01T10:30"` with no seconds), which HAPI rejects. These
     * formatters always emit seconds, producing HAPI-compliant strings.
     */
    private val LOCAL_DT_FORMATTER   = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
    private val OFFSET_DT_FORMATTER  = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

    // ── DateType ──────────────────────────────────────────────────────────────

    /** FHIR date from a full calendar date (precision: day). Result: `"YYYY-MM-DD"`. */
    fun toFhirDate(value: LocalDate): DateType = DateType(value.toString())

    /** FHIR date from a year-month pair (precision: month). Result: `"YYYY-MM"`. */
    fun toFhirDate(value: YearMonth): DateType = DateType(value.toString())

    /** FHIR date from a year (precision: year). Result: `"YYYY"`. */
    fun toFhirDate(value: Year): DateType = DateType(value.toString())

    /** FHIR date from a legacy [Date]. HAPI infers day precision from the epoch value. */
    fun toFhirDate(value: Date): DateType = DateType(value)

    /** FHIR date from a local date-time — extracts the date part, discards time. */
    fun toFhirDate(value: LocalDateTime): DateType = DateType(value.toLocalDate().toString())

    /** FHIR date from a zoned date-time — extracts the date part in the given zone, discards time. */
    fun toFhirDate(value: ZonedDateTime): DateType = DateType(value.toLocalDate().toString())

    /** FHIR date from an offset date-time — extracts the date part, discards time. */
    fun toFhirDate(value: OffsetDateTime): DateType = DateType(value.toLocalDate().toString())

    // ── DateTimeType ──────────────────────────────────────────────────────────

    /** FHIR dateTime from a local date-time (no timezone). Result: `"YYYY-MM-DDTHH:MM:SS"`. */
    fun toFhirDateTime(value: LocalDateTime): DateTimeType = DateTimeType(value.format(LOCAL_DT_FORMATTER))

    /** FHIR dateTime from a zoned date-time. Timezone offset is preserved. */
    fun toFhirDateTime(value: ZonedDateTime): DateTimeType = DateTimeType(value.format(OFFSET_DT_FORMATTER))

    /** FHIR dateTime from an offset date-time. Timezone offset is preserved. */
    fun toFhirDateTime(value: OffsetDateTime): DateTimeType = DateTimeType(value.format(OFFSET_DT_FORMATTER))

    /** FHIR dateTime from a legacy [Date]. HAPI infers second precision and uses UTC. */
    fun toFhirDateTime(value: Date): DateTimeType = DateTimeType(value)

    /** FHIR dateTime from a legacy [Calendar]. Timezone from the Calendar instance is used. */
    fun toFhirDateTime(value: Calendar): DateTimeType = DateTimeType(value)

    /**
     * FHIR dateTime from a [java.time.Instant] — rendered as UTC.
     * Use [toFhirInstant] when millisecond precision is required.
     */
    fun toFhirDateTime(value: Instant): DateTimeType = DateTimeType(Date.from(value))

    // ── InstantType ───────────────────────────────────────────────────────────

    /**
     * FHIR instant from a [java.time.Instant].
     * FHIR instant requires millisecond precision and a timezone — HAPI renders it as UTC.
     */
    fun toFhirInstant(value: Instant): InstantType = InstantType(Date.from(value))

    /** FHIR instant from a zoned date-time. The instant is derived from the zone+offset. */
    fun toFhirInstant(value: ZonedDateTime): InstantType = InstantType(Date.from(value.toInstant()))

    /** FHIR instant from an offset date-time. The instant is derived from the offset. */
    fun toFhirInstant(value: OffsetDateTime): InstantType = InstantType(Date.from(value.toInstant()))

    /** FHIR instant from a legacy [Date]. */
    fun toFhirInstant(value: Date): InstantType = InstantType(value)

    // ── TimeType ──────────────────────────────────────────────────────────────

    /** FHIR time from a local time. Result: `"HH:MM:SS[.nnnnnnnnn]"`. */
    fun toFhirTime(value: LocalTime): TimeType = TimeType(value.toString())
}
