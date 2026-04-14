package dev.ratkay.operation.dstu3

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.instanceOf
import org.hl7.fhir.dstu3.model.DateTimeType
import org.hl7.fhir.dstu3.model.DateType
import org.hl7.fhir.dstu3.model.InstantType
import org.hl7.fhir.dstu3.model.TimeType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.Year
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

class FhirDateTimeConverterTest {

    // ── toFhirDate ────────────────────────────────────────────────────────────

    @Test
    fun `toFhirDate with LocalDate produces DateType with day precision`() {
        val date = LocalDate.of(2024, 3, 15)
        val result = FhirDateTimeConverter.toFhirDate(date)
        assertThat(result, instanceOf(DateType::class.java))
        assertEquals("2024-03-15", result.valueAsString)
    }

    @Test
    fun `toFhirDate with YearMonth produces DateType with month precision`() {
        val ym = YearMonth.of(2024, 3)
        val result = FhirDateTimeConverter.toFhirDate(ym)
        assertThat(result, instanceOf(DateType::class.java))
        assertEquals("2024-03", result.valueAsString)
    }

    @Test
    fun `toFhirDate with Year produces DateType with year precision`() {
        val year = Year.of(2024)
        val result = FhirDateTimeConverter.toFhirDate(year)
        assertThat(result, instanceOf(DateType::class.java))
        assertEquals("2024", result.valueAsString)
    }

    @Test
    fun `toFhirDate with java util Date produces DateType`() {
        val date = Date(0) // epoch
        val result = FhirDateTimeConverter.toFhirDate(date)
        assertThat(result, instanceOf(DateType::class.java))
        assertTrue(result.valueAsString.isNotBlank())
    }

    @Test
    fun `toFhirDate with LocalDateTime extracts date part`() {
        val ldt = LocalDateTime.of(2024, 3, 15, 22, 45, 0)
        val result = FhirDateTimeConverter.toFhirDate(ldt)
        assertThat(result, instanceOf(DateType::class.java))
        assertEquals("2024-03-15", result.valueAsString)
    }

    @Test
    fun `toFhirDate with ZonedDateTime extracts date part in given zone`() {
        val zdt = ZonedDateTime.of(2024, 3, 15, 22, 45, 0, 0, ZoneOffset.ofHours(5))
        val result = FhirDateTimeConverter.toFhirDate(zdt)
        assertThat(result, instanceOf(DateType::class.java))
        assertEquals("2024-03-15", result.valueAsString)
    }

    @Test
    fun `toFhirDate with OffsetDateTime extracts date part`() {
        val odt = OffsetDateTime.of(2024, 3, 15, 22, 45, 0, 0, ZoneOffset.ofHours(5))
        val result = FhirDateTimeConverter.toFhirDate(odt)
        assertThat(result, instanceOf(DateType::class.java))
        assertEquals("2024-03-15", result.valueAsString)
    }

    // ── toFhirDateTime ────────────────────────────────────────────────────────

    @Test
    fun `toFhirDateTime with LocalDateTime produces zone-less dateTime with seconds`() {
        val ldt = LocalDateTime.of(2024, 3, 15, 10, 30, 0)
        val result = FhirDateTimeConverter.toFhirDateTime(ldt)
        assertThat(result, instanceOf(DateTimeType::class.java))
        assertEquals("2024-03-15T10:30:00", result.valueAsString)
    }

    @Test
    fun `toFhirDateTime with ZonedDateTime preserves timezone offset`() {
        val zdt = ZonedDateTime.of(2024, 3, 15, 10, 30, 0, 0, ZoneId.of("America/New_York"))
        val result = FhirDateTimeConverter.toFhirDateTime(zdt)
        assertThat(result, instanceOf(DateTimeType::class.java))
        assertTrue(result.valueAsString.startsWith("2024-03-15T10:30:00"),
            "Value should start with local time: ${result.valueAsString}")
        assertTrue(result.valueAsString.contains("-04:00") || result.valueAsString.contains("-05:00"),
            "Value should contain Eastern offset: ${result.valueAsString}")
    }

    @Test
    fun `toFhirDateTime with OffsetDateTime preserves offset`() {
        val odt = OffsetDateTime.of(2024, 3, 15, 10, 30, 45, 0, ZoneOffset.ofHours(5))
        val result = FhirDateTimeConverter.toFhirDateTime(odt)
        assertThat(result, instanceOf(DateTimeType::class.java))
        assertEquals("2024-03-15T10:30:45+05:00", result.valueAsString)
    }

    @Test
    fun `toFhirDateTime with java util Date produces DateTimeType`() {
        val date = Date()
        val result = FhirDateTimeConverter.toFhirDateTime(date)
        assertThat(result, instanceOf(DateTimeType::class.java))
        assertTrue(result.valueAsString.isNotBlank())
    }

    @Test
    fun `toFhirDateTime with Calendar produces DateTimeType`() {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2024, Calendar.MARCH, 15, 10, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val result = FhirDateTimeConverter.toFhirDateTime(cal)
        assertThat(result, instanceOf(DateTimeType::class.java))
        assertTrue(result.valueAsString.startsWith("2024-03-15"),
            "Value should start with date: ${result.valueAsString}")
    }

    @Test
    fun `toFhirDateTime with Instant produces DateTimeType in UTC`() {
        val instant = Instant.parse("2024-03-15T10:30:00.000Z")
        val result = FhirDateTimeConverter.toFhirDateTime(instant)
        assertThat(result, instanceOf(DateTimeType::class.java))
        assertTrue(result.valueAsString.isNotBlank())
        assertTrue(result.valueAsString.contains("2024-03-15"),
            "Value should contain the date: ${result.valueAsString}")
    }

    // ── toFhirInstant ─────────────────────────────────────────────────────────

    @Test
    fun `toFhirInstant with java time Instant produces InstantType`() {
        val instant = Instant.parse("2024-03-15T10:30:00.000Z")
        val result = FhirDateTimeConverter.toFhirInstant(instant)
        assertThat(result, instanceOf(InstantType::class.java))
        assertTrue(result.valueAsString.isNotBlank())
    }

    @Test
    fun `toFhirInstant with ZonedDateTime produces InstantType`() {
        val zdt = ZonedDateTime.of(2024, 3, 15, 10, 30, 0, 0, ZoneOffset.UTC)
        val result = FhirDateTimeConverter.toFhirInstant(zdt)
        assertThat(result, instanceOf(InstantType::class.java))
        assertTrue(result.valueAsString.isNotBlank())
    }

    @Test
    fun `toFhirInstant with OffsetDateTime produces InstantType`() {
        val odt = OffsetDateTime.of(2024, 3, 15, 10, 30, 0, 0, ZoneOffset.UTC)
        val result = FhirDateTimeConverter.toFhirInstant(odt)
        assertThat(result, instanceOf(InstantType::class.java))
        assertTrue(result.valueAsString.isNotBlank())
    }

    @Test
    fun `toFhirInstant with java util Date produces InstantType`() {
        val date = Date()
        val result = FhirDateTimeConverter.toFhirInstant(date)
        assertThat(result, instanceOf(InstantType::class.java))
        assertTrue(result.valueAsString.isNotBlank())
    }

    @Test
    fun `toFhirInstant ZonedDateTime and Instant from same moment produce equal values`() {
        val instant = Instant.parse("2024-03-15T10:30:00.000Z")
        val zdt = ZonedDateTime.ofInstant(instant, ZoneOffset.UTC)
        val fromInstant = FhirDateTimeConverter.toFhirInstant(instant)
        val fromZdt = FhirDateTimeConverter.toFhirInstant(zdt)
        assertEquals(fromInstant.valueAsString, fromZdt.valueAsString)
    }

    // ── toFhirTime ────────────────────────────────────────────────────────────

    @Test
    fun `toFhirTime with LocalTime produces TimeType with correct value`() {
        val time = LocalTime.of(10, 30, 45)
        val result = FhirDateTimeConverter.toFhirTime(time)
        assertThat(result, instanceOf(TimeType::class.java))
        assertEquals("10:30:45", result.valueAsString)
    }

    @Test
    fun `toFhirTime with midnight produces correct TimeType`() {
        val time = LocalTime.MIDNIGHT
        val result = FhirDateTimeConverter.toFhirTime(time)
        assertThat(result, instanceOf(TimeType::class.java))
        assertEquals("00:00", result.valueAsString)
    }
}
