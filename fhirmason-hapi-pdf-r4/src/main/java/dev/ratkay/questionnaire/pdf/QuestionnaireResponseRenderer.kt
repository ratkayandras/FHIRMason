package dev.ratkay.questionnaire.pdf

import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.QuestionnaireResponse
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Renders a FHIR R4 [QuestionnaireResponse] to a human-readable PDF document.
 *
 * Rendering requires both the completed [QuestionnaireResponse] (the captured answers) and its
 * [Questionnaire] definition (which supplies item ordering, labels, grouping and answer-option
 * display text). Items are matched by `linkId`; groups become headings, questions become
 * label/answer rows, and nested groups are indented.
 *
 * The renderer is a stateless, standalone utility — it does not participate in an
 * `OperationResult` pipeline. Use [PdfRenderOptions] to control the title, metadata line,
 * unanswered-question handling, an optional logo and font sizing.
 *
 * ### Example
 * ```kotlin
 * val pdf: ByteArray = QuestionnaireResponseRenderer.render(questionnaire, response)
 * Files.write(Path.of("response.pdf"), pdf)
 * ```
 */
object QuestionnaireResponseRenderer {

    /**
     * Renders the response to a PDF and returns the document as a byte array.
     *
     * @param questionnaire the questionnaire definition providing structure and labels.
     * @param response the completed response whose answers are rendered.
     * @param options rendering options; defaults are applied when omitted.
     * @return the generated PDF as a byte array.
     * @throws IllegalArgumentException if the questionnaire has no items to render.
     */
    @JvmStatic
    @JvmOverloads
    fun render(
        questionnaire: Questionnaire,
        response: QuestionnaireResponse,
        options: PdfRenderOptions = PdfRenderOptions()
    ): ByteArray {
        val buffer = ByteArrayOutputStream()
        render(questionnaire, response, buffer, options)
        return buffer.toByteArray()
    }

    /**
     * Renders the response to a PDF written to [out]. The caller retains ownership of [out] and is
     * responsible for closing it; this method flushes but does not close the stream.
     *
     * @param questionnaire the questionnaire definition providing structure and labels.
     * @param response the completed response whose answers are rendered.
     * @param out the destination stream for the PDF bytes.
     * @param options rendering options; defaults are applied when omitted.
     * @throws IllegalArgumentException if the questionnaire has no items to render.
     */
    @JvmStatic
    @JvmOverloads
    fun render(
        questionnaire: Questionnaire,
        response: QuestionnaireResponse,
        out: OutputStream,
        options: PdfRenderOptions = PdfRenderOptions()
    ) {
        require(questionnaire.hasItem()) { "Questionnaire has no items to render" }
        val model = RenderModelBuilder.build(questionnaire, response, options)
        OpenPdfWriter.write(model, options, out)
    }
}
