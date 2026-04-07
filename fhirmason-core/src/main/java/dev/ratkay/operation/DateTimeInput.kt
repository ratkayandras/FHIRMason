package dev.ratkay.operation

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.Year
import java.time.YearMonth
import java.time.ZonedDateTime
import java.util.Calendar
import java.util.Date

/**
 * Sealed wrapper that carries any Java/Kotlin date-time value into [OperationResult]'s
 * `addDate`, `addDateTime`, `addInstant`, and `addTime` methods.
 *
 * Using a single wrapper type collapses the per-type overload families (which would
 * otherwise conflict after JVM type erasure for lambda-returning variants) into one
 * method per FHIR primitive type. The conversion to the correct HAPI FHIR type is
 * delegated to [FhirDateTimeConverter].
 *
 * ### Kotlin usage
 * ```kotlin
 * result
 *     .addDate("dob",       DateTimeInput.of(LocalDate.of(1990, 6, 15)))
 *     .addDateTime("ts",    DateTimeInput.of(ZonedDateTime.now()))
 *     .addInstant("audit",  DateTimeInput.of(Instant.now()))
 *     .addTime("slot",      DateTimeInput.of(LocalTime.of(9, 0)))
 * ```
 *
 * ### Java usage
 * ```java
 * result
 *     .addDate("dob",      DateTimeInput.of(LocalDate.of(1990, 6, 15)))
 *     .addDateTime("ts",   DateTimeInput.of(ZonedDateTime.now()))
 *     .addInstant("audit", DateTimeInput.of(Instant.now()))
 *     .addTime("slot",     DateTimeInput.of(LocalTime.of(9, 0)));
 * ```
 *
 * ### Subclasses
 * All subclasses are `data class`es so they participate in structural equality and can be
 * pattern-matched exhaustively in `when` expressions.
 */
sealed class DateTimeInput {
    data class OfString(val value: String) : DateTimeInput()
    data class OfLocalDate(val value: LocalDate) : DateTimeInput()
    data class OfLocalDateTime(val value: LocalDateTime) : DateTimeInput()
    data class OfZonedDateTime(val value: ZonedDateTime) : DateTimeInput()
    data class OfOffsetDateTime(val value: OffsetDateTime) : DateTimeInput()
    data class OfInstant(val value: Instant) : DateTimeInput()
    data class OfDate(val value: Date) : DateTimeInput()
    data class OfCalendar(val value: Calendar) : DateTimeInput()
    data class OfYearMonth(val value: YearMonth) : DateTimeInput()
    data class OfYear(val value: Year) : DateTimeInput()
    data class OfLocalTime(val value: LocalTime) : DateTimeInput()

    companion object {
        /** Wraps a FHIR-format date/time/instant/time string (e.g. `"2024-01-15"`, `"10:30:00"`). */
        @JvmStatic fun of(value: String): DateTimeInput = OfString(value)

        /** Wraps a [LocalDate] (day precision, no timezone). */
        @JvmStatic fun of(value: LocalDate): DateTimeInput = OfLocalDate(value)

        /** Wraps a [LocalDateTime] (date + time, no timezone). */
        @JvmStatic fun of(value: LocalDateTime): DateTimeInput = OfLocalDateTime(value)

        /** Wraps a [ZonedDateTime] (date + time + zone). */
        @JvmStatic fun of(value: ZonedDateTime): DateTimeInput = OfZonedDateTime(value)

        /** Wraps an [OffsetDateTime] (date + time + UTC offset). */
        @JvmStatic fun of(value: OffsetDateTime): DateTimeInput = OfOffsetDateTime(value)

        /** Wraps a [java.time.Instant] (UTC milliseconds). */
        @JvmStatic fun of(value: Instant): DateTimeInput = OfInstant(value)

        /** Wraps a legacy [java.util.Date]. */
        @JvmStatic fun of(value: Date): DateTimeInput = OfDate(value)

        /** Wraps a legacy [java.util.Calendar]. */
        @JvmStatic fun of(value: Calendar): DateTimeInput = OfCalendar(value)

        /** Wraps a [YearMonth] (month precision, no timezone). */
        @JvmStatic fun of(value: YearMonth): DateTimeInput = OfYearMonth(value)

        /** Wraps a [Year] (year precision, no timezone). */
        @JvmStatic fun of(value: Year): DateTimeInput = OfYear(value)

        /** Wraps a [LocalTime] (time-of-day, no date, no timezone). */
        @JvmStatic fun of(value: LocalTime): DateTimeInput = OfLocalTime(value)
    }
}
