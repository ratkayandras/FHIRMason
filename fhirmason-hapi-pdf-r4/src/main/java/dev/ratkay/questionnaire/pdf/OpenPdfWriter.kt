package dev.ratkay.questionnaire.pdf

import com.lowagie.text.Chunk
import com.lowagie.text.Document
import com.lowagie.text.Element
import com.lowagie.text.Font
import com.lowagie.text.FontFactory
import com.lowagie.text.Image
import com.lowagie.text.Paragraph
import com.lowagie.text.pdf.PdfWriter
import java.io.OutputStream

/**
 * Writes a [RenderModel] to a PDF document using OpenPDF.
 *
 * This layer is intentionally thin: all questionnaire traversal and answer formatting happens in
 * [RenderModelBuilder], leaving this object responsible only for fonts, spacing, indentation and
 * stream handling.
 */
internal object OpenPdfWriter {

    /** Points of left indentation applied per nesting level. */
    private const val INDENT_PER_LEVEL = 18f

    /**
     * Renders [model] to [out]. The stream is flushed but not closed — ownership of the stream
     * stays with the caller.
     */
    fun write(model: RenderModel, options: PdfRenderOptions, out: OutputStream) {
        val document = Document()
        try {
            PdfWriter.getInstance(document, out)
            document.open()

            options.logoPng?.let { bytes ->
                val image = Image.getInstance(bytes)
                image.scaleToFit(120f, 80f)
                document.add(image)
            }

            document.add(titleParagraph(model.title, options))
            model.metadataLine?.let { document.add(metadataParagraph(it, options)) }
            document.add(Paragraph(" "))

            for (node in model.nodes) {
                when (node) {
                    is RenderNode.Section -> document.add(sectionParagraph(node, options))
                    is RenderNode.Field -> document.add(fieldParagraph(node, options))
                }
            }
        } finally {
            // Closing the Document finalises the PDF structure onto the (still-open) stream.
            if (document.isOpen) document.close()
            out.flush()
        }
    }

    private fun titleParagraph(title: String, options: PdfRenderOptions): Paragraph {
        val font = FontFactory.getFont(FontFactory.HELVETICA_BOLD, options.titleFontSize)
        return Paragraph(title, font).apply { spacingAfter = 4f }
    }

    private fun metadataParagraph(text: String, options: PdfRenderOptions): Paragraph {
        val font = FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, options.bodyFontSize)
        return Paragraph(text, font)
    }

    private fun sectionParagraph(node: RenderNode.Section, options: PdfRenderOptions): Paragraph {
        val size = (options.sectionFontSize - node.depth).coerceAtLeast(options.bodyFontSize)
        val font = FontFactory.getFont(FontFactory.HELVETICA_BOLD, size)
        return Paragraph(node.title, font).apply {
            indentationLeft = node.depth * INDENT_PER_LEVEL
            spacingBefore = 8f
            spacingAfter = 2f
        }
    }

    private fun fieldParagraph(node: RenderNode.Field, options: PdfRenderOptions): Paragraph {
        val labelFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, options.bodyFontSize)
        val valueFont = FontFactory.getFont(FontFactory.HELVETICA, options.bodyFontSize)
        val valueText = if (node.values.isEmpty()) {
            options.unansweredPlaceholder
        } else {
            node.values.joinToString("; ")
        }
        return Paragraph().apply {
            alignment = Element.ALIGN_LEFT
            indentationLeft = node.depth * INDENT_PER_LEVEL
            spacingAfter = 2f
            add(Chunk("${node.label}: ", labelFont))
            add(Chunk(valueText, valueFont))
        }
    }
}
