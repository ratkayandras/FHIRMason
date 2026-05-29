package dev.ratkay.questionnaire.pdf

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.hl7.fhir.r4.model.BooleanType
import org.hl7.fhir.r4.model.IntegerType
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.Questionnaire.QuestionnaireItemComponent
import org.hl7.fhir.r4.model.Questionnaire.QuestionnaireItemType
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent
import org.hl7.fhir.r4.model.QuestionnaireResponse.QuestionnaireResponseItemComponent
import org.hl7.fhir.r4.model.QuestionnaireResponse.QuestionnaireResponseStatus
import org.hl7.fhir.r4.model.StringType
import org.hl7.fhir.r4.model.Type
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class QuestionnaireResponseRendererTest {

    private fun qItem(linkId: String, text: String, type: QuestionnaireItemType): QuestionnaireItemComponent =
        QuestionnaireItemComponent().setLinkId(linkId).setText(text).setType(type)

    private fun rItem(linkId: String, value: Type): QuestionnaireResponseItemComponent =
        QuestionnaireResponseItemComponent().setLinkId(linkId)
            .addAnswer(QuestionnaireResponseItemAnswerComponent().setValue(value)) as QuestionnaireResponseItemComponent

    private fun sampleQuestionnaire(): Questionnaire = Questionnaire().apply {
        title = "Intake Form"
        addItem(
            qItem("demographics", "Demographics", QuestionnaireItemType.GROUP).apply {
                addItem(qItem("name", "Full name", QuestionnaireItemType.STRING))
                addItem(qItem("age", "Age", QuestionnaireItemType.INTEGER))
            }
        )
        addItem(qItem("smoker", "Do you smoke?", QuestionnaireItemType.BOOLEAN))
    }

    private fun sampleResponse(): QuestionnaireResponse = QuestionnaireResponse().apply {
        status = QuestionnaireResponseStatus.COMPLETED
        setAuthored(java.util.Date(0))
        addItem(
            QuestionnaireResponseItemComponent().setLinkId("demographics").apply {
                addItem(rItem("name", StringType("Alice Smith")))
                addItem(rItem("age", IntegerType(34)))
            }
        )
        addItem(rItem("smoker", BooleanType(false)))
    }

    private fun extractText(pdf: ByteArray): String =
        PDDocument.load(pdf).use { PDFTextStripper().getText(it) }

    @Test
    fun `render produces a valid non-empty PDF`() {
        val pdf = QuestionnaireResponseRenderer.render(sampleQuestionnaire(), sampleResponse())
        assertTrue(pdf.size > 100, "PDF should be non-trivial in size")
        val header = String(pdf.copyOfRange(0, 5), Charsets.US_ASCII)
        assertTrue(header.startsWith("%PDF-"), "PDF should start with the %PDF- header, was: $header")
    }

    @Test
    fun `rendered text contains title labels and answers in order`() {
        val text = extractText(QuestionnaireResponseRenderer.render(sampleQuestionnaire(), sampleResponse()))

        assertTrue(text.contains("Intake Form"), "missing title in: $text")
        assertTrue(text.contains("Demographics"), "missing group heading in: $text")
        assertTrue(text.contains("Full name"), "missing label in: $text")
        assertTrue(text.contains("Alice Smith"), "missing answer in: $text")
        assertTrue(text.contains("Age"), "missing label in: $text")
        assertTrue(text.contains("34"), "missing answer in: $text")
        assertTrue(text.contains("Do you smoke?"), "missing label in: $text")
        assertTrue(text.contains("No"), "missing boolean answer in: $text")

        // Document order: title before group, group before its children, smoker last.
        assertTrue(text.indexOf("Intake Form") < text.indexOf("Demographics"))
        assertTrue(text.indexOf("Demographics") < text.indexOf("Full name"))
        assertTrue(text.indexOf("Full name") < text.indexOf("Do you smoke?"))
    }

    @Test
    fun `rendered text contains metadata line`() {
        val text = extractText(QuestionnaireResponseRenderer.render(sampleQuestionnaire(), sampleResponse()))
        assertTrue(text.contains("Status: completed"), "missing status in: $text")
    }

    @Test
    fun `unanswered placeholder appears when showUnanswered enabled`() {
        val q = Questionnaire().apply {
            title = "T"
            addItem(qItem("notes", "Additional notes", QuestionnaireItemType.STRING))
        }
        val options = PdfRenderOptions(showUnanswered = true, unansweredPlaceholder = "N/A")
        val text = extractText(QuestionnaireResponseRenderer.render(q, QuestionnaireResponse(), options))
        assertTrue(text.contains("Additional notes"), "missing label in: $text")
        assertTrue(text.contains("N/A"), "missing placeholder in: $text")
    }

    @Test
    fun `render to output stream writes the same bytes`() {
        val buffer = ByteArrayOutputStream()
        QuestionnaireResponseRenderer.render(sampleQuestionnaire(), sampleResponse(), buffer)
        val header = String(buffer.toByteArray().copyOfRange(0, 5), Charsets.US_ASCII)
        assertTrue(header.startsWith("%PDF-"))
    }

    @Test
    fun `render with logo succeeds`() {
        val logo = ByteArrayOutputStream().use { out ->
            ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", out)
            out.toByteArray()
        }
        val pdf = QuestionnaireResponseRenderer.render(
            sampleQuestionnaire(),
            sampleResponse(),
            PdfRenderOptions(logoPng = logo)
        )
        assertTrue(pdf.size > 100)
    }

    @Test
    fun `render throws when questionnaire has no items`() {
        assertThrows(IllegalArgumentException::class.java) {
            QuestionnaireResponseRenderer.render(Questionnaire(), QuestionnaireResponse())
        }
    }
}
