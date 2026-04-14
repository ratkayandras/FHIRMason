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

    // ── DateTimeInput dispatch ─────────────────────────────────────────────────

    /**
     * Dispatches a [DateTimeInput] to the appropriate [DateType] converter.
     *
     * Supported subclasses: [DateTimeInput.OfString], [DateTimeInput.OfLocalDate],
     * [DateTimeInput.OfYearMonth], [DateTimeInput.OfYear], [DateTimeInput.OfDate],
     * [DateTimeInput.OfLocalDateTime], [DateTimeInput.OfZonedDateTime], [DateTimeInput.OfOffsetDateTime].
     *
     * @throws IllegalArgumentException for subclasses that do not map to a FHIR date
     *   ([DateTimeInput.OfInstant], [DateTimeInput.OfCalendar], [DateTimeInput.OfLocalTime]).
     */
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

    /**
     * Dispatches a [DateTimeInput] to the appropriate [DateTimeType] converter.
     *
     * Supported subclasses: [DateTimeInput.OfString], [DateTimeInput.OfLocalDateTime],
     * [DateTimeInput.OfZonedDateTime], [DateTimeInput.OfOffsetDateTime],
     * [DateTimeInput.OfInstant], [DateTimeInput.OfDate], [DateTimeInput.OfCalendar].
     *
     * @throws IllegalArgumentException for subclasses that do not map to a FHIR dateTime
     *   ([DateTimeInput.OfLocalDate], [DateTimeInput.OfYearMonth], [DateTimeInput.OfYear],
     *   [DateTimeInput.OfLocalTime]).
     */
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

    /**
     * Dispatches a [DateTimeInput] to the appropriate [InstantType] converter.
     *
     * Supported subclasses: [DateTimeInput.OfString], [DateTimeInput.OfInstant],
     * [DateTimeInput.OfZonedDateTime], [DateTimeInput.OfOffsetDateTime], [DateTimeInput.OfDate].
     *
     * @throws IllegalArgumentException for subclasses that do not map to a FHIR instant.
     */
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

    /**
     * Dispatches a [DateTimeInput] to the appropriate [TimeType] converter.
     *
     * Supported subclasses: [DateTimeInput.OfString], [DateTimeInput.OfLocalTime].
     *
     * @throws IllegalArgumentException for all other subclasses.
     */
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
