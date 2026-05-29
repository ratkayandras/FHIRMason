package dev.ratkay.questionnaire.pdf

import org.hl7.fhir.r4.model.Attachment
import org.hl7.fhir.r4.model.BooleanType
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.PrimitiveType
import org.hl7.fhir.r4.model.Quantity
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.Questionnaire.QuestionnaireItemComponent
import org.hl7.fhir.r4.model.Questionnaire.QuestionnaireItemType
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.QuestionnaireResponse.QuestionnaireResponseItemComponent
import org.hl7.fhir.r4.model.Reference
import org.hl7.fhir.r4.model.Type

/**
 * Builds a [RenderModel] from a `Questionnaire` (structure, labels, ordering, answer-option
 * display text) and a `QuestionnaireResponse` (the captured answers).
 *
 * The traversal is driven by the `Questionnaire.item` tree — the source of truth for ordering and
 * labels — and matches `QuestionnaireResponse` items/answers by `linkId`. The builder is pure and
 * has no PDF dependency, so its behaviour can be asserted directly in unit tests.
 */
internal object RenderModelBuilder {

    /** Builds the intermediate model. See class docs for the traversal contract. */
    fun build(
        questionnaire: Questionnaire,
        response: QuestionnaireResponse,
        options: PdfRenderOptions
    ): RenderModel {
        val title = options.title
            ?: questionnaire.title?.takeIf { it.isNotBlank() }
            ?: questionnaire.name?.takeIf { it.isNotBlank() }
            ?: "Questionnaire Response"

        val metadataLine = if (options.includeMetadata) buildMetadataLine(response) else null

        val nodes = mutableListOf<RenderNode>()
        val responseByLinkId = groupByLinkId(response.item)
        for (item in questionnaire.item) {
            renderItem(item, responseByLinkId, depth = 0, options = options, out = nodes)
        }
        return RenderModel(title, metadataLine, nodes)
    }

    /** Composes the "Status / Authored" metadata line, omitting absent parts. */
    private fun buildMetadataLine(response: QuestionnaireResponse): String? {
        val parts = mutableListOf<String>()
        response.status?.let { parts.add("Status: ${it.toCode()}") }
        if (response.hasAuthored()) parts.add("Authored: ${response.authoredElement.valueAsString}")
        return parts.joinToString("  |  ").takeIf { it.isNotBlank() }
    }

    /**
     * Renders a single questionnaire item (and its descendants) into [out].
     *
     * Groups/displays emit a [RenderNode.Section]; every other type emits a [RenderNode.Field]
     * carrying the rendered answers found under matching response items.
     */
    private fun renderItem(
        item: QuestionnaireItemComponent,
        responseByLinkId: Map<String, List<QuestionnaireResponseItemComponent>>,
        depth: Int,
        options: PdfRenderOptions,
        out: MutableList<RenderNode>
    ) {
        val matches = responseByLinkId[item.linkId].orEmpty()
        val label = item.text?.takeIf { it.isNotBlank() } ?: item.linkId ?: ""

        when (item.type) {
            QuestionnaireItemType.GROUP -> {
                val hasContent = matches.isNotEmpty() || options.showUnanswered
                if (!hasContent) return
                out.add(RenderNode.Section(label, depth))
                if (matches.isEmpty()) {
                    // Unanswered group: still descend so required structure is visible.
                    val empty = emptyMap<String, List<QuestionnaireResponseItemComponent>>()
                    item.item.forEach { renderItem(it, empty, depth + 1, options, out) }
                } else {
                    // Render each captured group instance (groups may repeat).
                    matches.forEach { instance ->
                        val childResponses = groupByLinkId(instance.item)
                        item.item.forEach { renderItem(it, childResponses, depth + 1, options, out) }
                    }
                }
            }

            QuestionnaireItemType.DISPLAY -> {
                // Display items carry instructional text only; render as a heading-style note.
                out.add(RenderNode.Section(label, depth))
            }

            else -> {
                val values = matches
                    .flatMap { it.answer }
                    .filter { it.hasValue() }
                    .map { renderAnswerValue(it.value, item) }
                if (values.isEmpty() && !options.showUnanswered) return
                out.add(RenderNode.Field(label, values, depth))

                // Nested items under a question (e.g. follow-up questions) are rendered as children.
                if (item.hasItem()) {
                    val childResponses = matches
                        .flatMap { it.item + it.answer.flatMap { ans -> ans.item } }
                        .let(::groupByLinkId)
                    item.item.forEach { renderItem(it, childResponses, depth + 1, options, out) }
                }
            }
        }
    }

    /** Indexes response items by their `linkId`, preserving document order within each key. */
    private fun groupByLinkId(
        items: List<QuestionnaireResponseItemComponent>
    ): Map<String, List<QuestionnaireResponseItemComponent>> {
        val map = LinkedHashMap<String, MutableList<QuestionnaireResponseItemComponent>>()
        for (item in items) {
            val key = item.linkId ?: continue
            map.getOrPut(key) { mutableListOf() }.add(item)
        }
        return map
    }

    /**
     * Renders a single answer value to display text.
     *
     * For `Coding` answers whose `display` is absent, the matching `answerOption` on [item] is
     * consulted so coded choices still render a human label rather than a bare code.
     */
    private fun renderAnswerValue(value: Type?, item: QuestionnaireItemComponent): String {
        return when (value) {
            null -> ""
            is BooleanType -> if (value.value == true) "Yes" else "No"
            is Coding -> codingDisplay(value, item)
            is Quantity -> quantityDisplay(value)
            is Attachment -> value.title?.takeIf { it.isNotBlank() }
                ?: value.contentType?.takeIf { it.isNotBlank() }
                ?: value.url?.takeIf { it.isNotBlank() }
                ?: "[attachment]"
            is Reference -> value.display?.takeIf { it.isNotBlank() }
                ?: value.reference?.takeIf { it.isNotBlank() }
                ?: "[reference]"
            is PrimitiveType<*> -> value.valueAsString ?: ""
            else -> value.fhirType()
        }
    }

    /** Resolves a coded value's label: explicit display, else answer-option display, else code. */
    private fun codingDisplay(coding: Coding, item: QuestionnaireItemComponent): String {
        coding.display?.takeIf { it.isNotBlank() }?.let { return it }
        val optionDisplay = item.answerOption
            .map { it.value }
            .filterIsInstance<Coding>()
            .firstOrNull { it.code == coding.code && (coding.system == null || it.system == coding.system) }
            ?.display
            ?.takeIf { it.isNotBlank() }
        return optionDisplay ?: coding.code?.takeIf { it.isNotBlank() } ?: "[coding]"
    }

    /** Formats a quantity as "value unit" (or "value code" when no unit is set). */
    private fun quantityDisplay(quantity: Quantity): String {
        val number = quantity.value?.toPlainString() ?: ""
        val unit = quantity.unit?.takeIf { it.isNotBlank() }
            ?: quantity.code?.takeIf { it.isNotBlank() }
        return listOfNotNull(number.takeIf { it.isNotBlank() }, unit).joinToString(" ").ifBlank { "[quantity]" }
    }
}
