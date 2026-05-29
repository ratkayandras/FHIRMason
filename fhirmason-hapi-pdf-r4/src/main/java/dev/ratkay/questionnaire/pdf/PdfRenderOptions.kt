package dev.ratkay.questionnaire.pdf

/**
 * Tuning options for [QuestionnaireResponseRenderer].
 *
 * All values have sensible defaults, so Java and Kotlin callers can omit any subset. The
 * constructor is annotated with [JvmOverloads] so Java callers can use the trailing-default
 * overloads instead of always passing every argument.
 *
 * @property title overrides the document title. When `null`, the renderer falls back to the
 *   `Questionnaire.title` (then `Questionnaire.name`, then a generic label).
 * @property showUnanswered when `true`, questions with no answer are still rendered (with
 *   [unansweredPlaceholder]); when `false`, unanswered questions are omitted entirely.
 * @property includeMetadata when `true`, a metadata line (response status and authored date) is
 *   rendered beneath the title.
 * @property logoPng optional PNG/JPEG image bytes rendered at the top of the document; `null`
 *   omits the logo. Invalid image bytes cause [QuestionnaireResponseRenderer.render] to throw.
 * @property titleFontSize point size of the document title.
 * @property sectionFontSize point size of the top-level group headings (nested headings shrink
 *   by one point per level, never below [bodyFontSize]).
 * @property bodyFontSize point size of question labels and answer values.
 * @property unansweredPlaceholder text shown in place of a missing answer when [showUnanswered] is `true`.
 */
class PdfRenderOptions @JvmOverloads constructor(
    val title: String? = null,
    val showUnanswered: Boolean = true,
    val includeMetadata: Boolean = true,
    val logoPng: ByteArray? = null,
    val titleFontSize: Float = 18f,
    val sectionFontSize: Float = 13f,
    val bodyFontSize: Float = 11f,
    val unansweredPlaceholder: String = "—"
)
