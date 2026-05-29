package dev.ratkay.questionnaire.pdf

import org.hl7.fhir.r4.model.Attachment
import org.hl7.fhir.r4.model.BooleanType
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.DateType
import org.hl7.fhir.r4.model.DecimalType
import org.hl7.fhir.r4.model.IntegerType
import org.hl7.fhir.r4.model.Quantity
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.Questionnaire.QuestionnaireItemAnswerOptionComponent
import org.hl7.fhir.r4.model.Questionnaire.QuestionnaireItemComponent
import org.hl7.fhir.r4.model.Questionnaire.QuestionnaireItemType
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent
import org.hl7.fhir.r4.model.QuestionnaireResponse.QuestionnaireResponseItemComponent
import org.hl7.fhir.r4.model.QuestionnaireResponse.QuestionnaireResponseStatus
import org.hl7.fhir.r4.model.Reference
import org.hl7.fhir.r4.model.StringType
import org.hl7.fhir.r4.model.Type
import org.hl7.fhir.r4.model.UriType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class RenderModelBuilderTest {

    private val options = PdfRenderOptions()

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun qItem(linkId: String, text: String?, type: QuestionnaireItemType): QuestionnaireItemComponent =
        QuestionnaireItemComponent().setLinkId(linkId).setText(text).setType(type)

    private fun answer(value: Type): QuestionnaireResponseItemAnswerComponent =
        QuestionnaireResponseItemAnswerComponent().setValue(value)

    private fun rItem(linkId: String, vararg values: Type): QuestionnaireResponseItemComponent {
        val item = QuestionnaireResponseItemComponent().setLinkId(linkId)
        values.forEach { item.addAnswer(answer(it)) }
        return item
    }

    private fun fields(model: RenderModel): List<RenderNode.Field> =
        model.nodes.filterIsInstance<RenderNode.Field>()

    private fun sections(model: RenderModel): List<RenderNode.Section> =
        model.nodes.filterIsInstance<RenderNode.Section>()

    // ── title ─────────────────────────────────────────────────────────────────

    @Test
    fun `title falls back to questionnaire title then name then generic`() {
        val q = Questionnaire().apply { addItem(qItem("1", "Q", QuestionnaireItemType.STRING)) }
        val qr = QuestionnaireResponse()

        q.title = "My Form"
        assertEquals("My Form", RenderModelBuilder.build(q, qr, options).title)

        q.title = null
        q.name = "form-name"
        assertEquals("form-name", RenderModelBuilder.build(q, qr, options).title)

        q.name = null
        assertEquals("Questionnaire Response", RenderModelBuilder.build(q, qr, options).title)
    }

    @Test
    fun `explicit option title overrides questionnaire title`() {
        val q = Questionnaire().apply {
            title = "Ignored"
            addItem(qItem("1", "Q", QuestionnaireItemType.STRING))
        }
        val model = RenderModelBuilder.build(q, QuestionnaireResponse(), PdfRenderOptions(title = "Override"))
        assertEquals("Override", model.title)
    }

    // ── metadata ────────────────────────────────────────────────────────────────

    @Test
    fun `metadata line contains status and authored date`() {
        val q = Questionnaire().apply { addItem(qItem("1", "Q", QuestionnaireItemType.STRING)) }
        val qr = QuestionnaireResponse().apply {
            status = QuestionnaireResponseStatus.COMPLETED
            setAuthored(java.util.Date(0))
        }
        val line = RenderModelBuilder.build(q, qr, options).metadataLine
        assertTrue(line!!.contains("Status: completed"), "was: $line")
        assertTrue(line.contains("Authored:"), "was: $line")
    }

    @Test
    fun `metadata line omitted when includeMetadata is false`() {
        val q = Questionnaire().apply { addItem(qItem("1", "Q", QuestionnaireItemType.STRING)) }
        val qr = QuestionnaireResponse().apply { status = QuestionnaireResponseStatus.COMPLETED }
        val model = RenderModelBuilder.build(q, qr, PdfRenderOptions(includeMetadata = false))
        assertEquals(null, model.metadataLine)
    }

    // ── answer value types ──────────────────────────────────────────────────────

    @Test
    fun `boolean answers render as Yes and No`() {
        val q = Questionnaire().apply {
            addItem(qItem("yes", "Smoker", QuestionnaireItemType.BOOLEAN))
            addItem(qItem("no", "Diabetic", QuestionnaireItemType.BOOLEAN))
        }
        val qr = QuestionnaireResponse().apply {
            addItem(rItem("yes", BooleanType(true)))
            addItem(rItem("no", BooleanType(false)))
        }
        val f = fields(RenderModelBuilder.build(q, qr, options))
        assertEquals(listOf("Yes"), f[0].values)
        assertEquals(listOf("No"), f[1].values)
    }

    @Test
    fun `primitive answers render via valueAsString`() {
        val q = Questionnaire().apply {
            addItem(qItem("s", "Name", QuestionnaireItemType.STRING))
            addItem(qItem("d", "Score", QuestionnaireItemType.DECIMAL))
            addItem(qItem("i", "Count", QuestionnaireItemType.INTEGER))
            addItem(qItem("dt", "DOB", QuestionnaireItemType.DATE))
            addItem(qItem("u", "Site", QuestionnaireItemType.URL))
        }
        val qr = QuestionnaireResponse().apply {
            addItem(rItem("s", StringType("Alice")))
            addItem(rItem("d", DecimalType(BigDecimal("3.50"))))
            addItem(rItem("i", IntegerType(42)))
            addItem(rItem("dt", DateType("2024-03-15")))
            addItem(rItem("u", UriType("http://example.org")))
        }
        val f = fields(RenderModelBuilder.build(q, qr, options))
        assertEquals(listOf("Alice"), f[0].values)
        assertEquals(listOf("3.50"), f[1].values)
        assertEquals(listOf("42"), f[2].values)
        assertEquals(listOf("2024-03-15"), f[3].values)
        assertEquals(listOf("http://example.org"), f[4].values)
    }

    @Test
    fun `coding answer prefers explicit display`() {
        val q = Questionnaire().apply { addItem(qItem("c", "Sex", QuestionnaireItemType.CHOICE)) }
        val qr = QuestionnaireResponse().apply {
            addItem(rItem("c", Coding("http://sys", "F", "Female")))
        }
        assertEquals(listOf("Female"), fields(RenderModelBuilder.build(q, qr, options))[0].values)
    }

    @Test
    fun `coding answer without display resolves from answerOption`() {
        val q = Questionnaire().apply {
            addItem(
                qItem("c", "Sex", QuestionnaireItemType.CHOICE).apply {
                    addAnswerOption(
                        QuestionnaireItemAnswerOptionComponent().setValue(Coding("http://sys", "F", "Female"))
                    )
                }
            )
        }
        val qr = QuestionnaireResponse().apply {
            addItem(rItem("c", Coding("http://sys", "F", null)))
        }
        assertEquals(listOf("Female"), fields(RenderModelBuilder.build(q, qr, options))[0].values)
    }

    @Test
    fun `coding answer falls back to code when no display available`() {
        val q = Questionnaire().apply { addItem(qItem("c", "Sex", QuestionnaireItemType.CHOICE)) }
        val qr = QuestionnaireResponse().apply { addItem(rItem("c", Coding("http://sys", "F", null))) }
        assertEquals(listOf("F"), fields(RenderModelBuilder.build(q, qr, options))[0].values)
    }

    @Test
    fun `quantity answer renders value and unit`() {
        val q = Questionnaire().apply { addItem(qItem("w", "Weight", QuestionnaireItemType.QUANTITY)) }
        val qr = QuestionnaireResponse().apply {
            addItem(rItem("w", Quantity().setValue(BigDecimal("72.5")).setUnit("kg")))
        }
        assertEquals(listOf("72.5 kg"), fields(RenderModelBuilder.build(q, qr, options))[0].values)
    }

    @Test
    fun `attachment answer renders title`() {
        val q = Questionnaire().apply { addItem(qItem("a", "Scan", QuestionnaireItemType.ATTACHMENT)) }
        val qr = QuestionnaireResponse().apply {
            addItem(rItem("a", Attachment().setTitle("xray.png")))
        }
        assertEquals(listOf("xray.png"), fields(RenderModelBuilder.build(q, qr, options))[0].values)
    }

    @Test
    fun `reference answer prefers display then reference`() {
        val q = Questionnaire().apply {
            addItem(qItem("r1", "Doctor", QuestionnaireItemType.REFERENCE))
            addItem(qItem("r2", "Patient", QuestionnaireItemType.REFERENCE))
        }
        val qr = QuestionnaireResponse().apply {
            addItem(rItem("r1", Reference().setDisplay("Dr. House")))
            addItem(rItem("r2", Reference("Patient/123")))
        }
        val f = fields(RenderModelBuilder.build(q, qr, options))
        assertEquals(listOf("Dr. House"), f[0].values)
        assertEquals(listOf("Patient/123"), f[1].values)
    }

    @Test
    fun `repeating question collects multiple answers`() {
        val q = Questionnaire().apply {
            addItem(qItem("lang", "Languages", QuestionnaireItemType.STRING).setRepeats(true))
        }
        val qr = QuestionnaireResponse().apply {
            addItem(rItem("lang", StringType("English"), StringType("Hungarian")))
        }
        assertEquals(listOf("English", "Hungarian"), fields(RenderModelBuilder.build(q, qr, options))[0].values)
    }

    // ── groups & nesting ─────────────────────────────────────────────────────────

    @Test
    fun `group becomes a section with nested fields at increasing depth`() {
        val q = Questionnaire().apply {
            addItem(
                qItem("g", "Demographics", QuestionnaireItemType.GROUP).apply {
                    addItem(qItem("g.name", "Name", QuestionnaireItemType.STRING))
                    addItem(qItem("g.age", "Age", QuestionnaireItemType.INTEGER))
                }
            )
        }
        val qr = QuestionnaireResponse().apply {
            addItem(
                QuestionnaireResponseItemComponent().setLinkId("g").apply {
                    addItem(rItem("g.name", StringType("Alice")))
                    addItem(rItem("g.age", IntegerType(30)))
                }
            )
        }
        val model = RenderModelBuilder.build(q, qr, options)
        val section = sections(model).single()
        assertEquals("Demographics", section.title)
        assertEquals(0, section.depth)
        val f = fields(model)
        assertEquals(listOf("Alice"), f[0].values)
        assertEquals(1, f[0].depth)
        assertEquals(listOf("30"), f[1].values)
        assertEquals(1, f[1].depth)
    }

    @Test
    fun `display item becomes a section`() {
        val q = Questionnaire().apply {
            addItem(qItem("info", "Please answer truthfully.", QuestionnaireItemType.DISPLAY))
        }
        val model = RenderModelBuilder.build(q, QuestionnaireResponse(), options)
        assertEquals("Please answer truthfully.", sections(model).single().title)
    }

    // ── unanswered handling ───────────────────────────────────────────────────────

    @Test
    fun `unanswered question rendered with empty values when showUnanswered true`() {
        val q = Questionnaire().apply { addItem(qItem("x", "Notes", QuestionnaireItemType.STRING)) }
        val model = RenderModelBuilder.build(q, QuestionnaireResponse(), PdfRenderOptions(showUnanswered = true))
        assertTrue(fields(model).single().values.isEmpty())
    }

    @Test
    fun `unanswered question omitted when showUnanswered false`() {
        val q = Questionnaire().apply { addItem(qItem("x", "Notes", QuestionnaireItemType.STRING)) }
        val model = RenderModelBuilder.build(q, QuestionnaireResponse(), PdfRenderOptions(showUnanswered = false))
        assertTrue(fields(model).isEmpty())
    }

    @Test
    fun `label falls back to linkId when text is absent`() {
        val q = Questionnaire().apply { addItem(qItem("link-42", null, QuestionnaireItemType.STRING)) }
        val qr = QuestionnaireResponse().apply { addItem(rItem("link-42", StringType("v"))) }
        assertEquals("link-42", fields(RenderModelBuilder.build(q, qr, options)).single().label)
    }
}
