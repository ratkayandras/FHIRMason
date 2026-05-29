package dev.ratkay.questionnaire.pdf

/**
 * A version-agnostic, PDF-library-agnostic intermediate representation of a rendered
 * questionnaire response.
 *
 * The model is produced by [RenderModelBuilder] from a `Questionnaire` + `QuestionnaireResponse`
 * pair and consumed by [OpenPdfWriter]. Keeping this layer free of any FHIR or PDF dependency
 * makes the (non-trivial) traversal/matching logic deterministic and unit-testable in isolation.
 *
 * @property title the document title shown at the top of the PDF.
 * @property metadataLine optional single line of metadata (status / authored date); `null` when omitted.
 * @property nodes the ordered, flattened list of content nodes to render top-to-bottom.
 */
internal data class RenderModel(
    val title: String,
    val metadataLine: String?,
    val nodes: List<RenderNode>
)

/**
 * A single renderable element in a [RenderModel]. Carries its own nesting [depth] so the writer
 * can indent without reconstructing the tree.
 */
internal sealed class RenderNode {
    /** Nesting depth: `0` for top-level items, incremented for each enclosing group. */
    abstract val depth: Int

    /**
     * A group heading or a standalone display item.
     *
     * @property title the heading text.
     */
    data class Section(val title: String, override val depth: Int) : RenderNode()

    /**
     * A question and its answer(s).
     *
     * @property label the question text (falls back to the linkId when no text is present).
     * @property values the rendered answer values; empty when the question was left unanswered.
     */
    data class Field(val label: String, val values: List<String>, override val depth: Int) : RenderNode()
}
