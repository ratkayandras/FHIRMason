package dev.ratkay.operation

import org.hl7.fhir.r4.model.*
import kotlin.reflect.KClass

/**
 * A typed, immutable pipeline builder that accumulates FHIR resources keyed by name and
 * carries the type of the most recently added resource through the generic parameter [T].
 *
 * The internal parameter map is always `Map<String, List<Base>>`; [T] only tracks the
 * "head" of the pipeline so that [getResult] returns the correct type without casting.
 * When the last step produced a list, [T] becomes `List<R>` and the result can be
 * retrieved via [getResult] or via the [getResultList] extension.
 */
class OperationResult<T> private constructor(
    private val parameters: MutableMap<String, MutableList<Base>>,
    private val result: T?,
    private val outcomes: MutableList<OperationOutcome>,
    private val errorStrategy: ErrorStrategy,
    private val failedTasks: MutableMap<String, OperationOutcome>
) {

    // Error state

    fun hasErrors(): Boolean = outcomes.isNotEmpty()

    fun isSuccessful(): Boolean = outcomes.isEmpty()

    fun getOutcomes(): List<OperationOutcome> = outcomes.toList()

    fun getFailedTasks(): Map<String, OperationOutcome> = failedTasks.toMap()

    fun toOperationOutcome(): OperationOutcome = OperationOutcome().apply {
        this@OperationResult.outcomes.forEach { oo ->
            oo.issue.forEach { issue ->
                addIssue().apply {
                    severity = issue.severity
                    code = issue.code
                    diagnostics = issue.diagnostics
                }
            }
        }
    }

    // Builder helpers

    private fun shouldSkip() = errorStrategy == ErrorStrategy.FAIL_FAST && hasErrors()

    private fun <R> skippedResult(): OperationResult<R> {
        @Suppress("UNCHECKED_CAST")
        return OperationResult(parameters, null, outcomes, errorStrategy, failedTasks) as OperationResult<R>
    }

    // Builder methods - single item

    fun <R : Base> add(name: String? = null, builder: () -> R): OperationResult<R> {
        if (shouldSkip()) return skippedResult()
        return try {
            val value = builder()
            val key = name ?: value.fhirType().lowercase()
            parameters.getOrPut(key) { mutableListOf() }.add(value)
            OperationResult(parameters, value, outcomes, errorStrategy, failedTasks)
        } catch (e: Exception) {
            outcomes.add(e.toOperationOutcome())
            skippedResult()
        }
    }

    fun <R : Base> addUsing(name: String? = null, builder: (T) -> R): OperationResult<R> {
        if (shouldSkip()) return skippedResult()
        return try {
            val value = builder(getResult())
            val key = name ?: value.fhirType().lowercase()
            parameters.getOrPut(key) { mutableListOf() }.add(value)
            OperationResult(parameters, value, outcomes, errorStrategy, failedTasks)
        } catch (e: Exception) {
            outcomes.add(e.toOperationOutcome())
            skippedResult()
        }
    }

    // Builder methods - list
    // addAll/addAllUsing return OperationResult<List<R>>; use getResultList() or getResult()
    // to retrieve the typed list without casting.

    fun <R : Base> addAll(name: String? = null, builder: () -> List<R>): OperationResult<List<R>> {
        if (shouldSkip()) return skippedResult()
        return try {
            val values = builder()
            addToParameters(values, name)
            OperationResult(parameters, values, outcomes, errorStrategy, failedTasks)
        } catch (e: Exception) {
            outcomes.add(e.toOperationOutcome())
            skippedResult()
        }
    }

    fun <R : Base> addAllUsing(name: String? = null, builder: (T) -> List<R>): OperationResult<List<R>> {
        if (shouldSkip()) return skippedResult()
        return try {
            val values = builder(getResult())
            addToParameters(values, name)
            OperationResult(parameters, values, outcomes, errorStrategy, failedTasks)
        } catch (e: Exception) {
            outcomes.add(e.toOperationOutcome())
            skippedResult()
        }
    }

    // Builder methods - from existing parameters

    fun <I : Base, R : Base> addFrom(name: String, type: KClass<I>, builder: (List<I>) -> R): OperationResult<R> {
        if (shouldSkip()) return skippedResult()
        return try {
            val filtered = parameters[name]?.filterIsInstance(type.java) ?: emptyList()
            val value = builder(filtered)
            parameters.getOrPut(name) { mutableListOf() }.add(value)
            OperationResult(parameters, value, outcomes, errorStrategy, failedTasks)
        } catch (e: Exception) {
            outcomes.add(e.toOperationOutcome())
            skippedResult()
        }
    }

    fun <I : Base, R : Base> addAllFrom(name: String, type: KClass<I>, builder: (List<I>) -> List<R>): OperationResult<List<R>> {
        if (shouldSkip()) return skippedResult()
        return try {
            val filtered = parameters[name]?.filterIsInstance(type.java) ?: emptyList()
            val values = builder(filtered)
            addToParameters(values, name)
            OperationResult(parameters, values, outcomes, errorStrategy, failedTasks)
        } catch (e: Exception) {
            outcomes.add(e.toOperationOutcome())
            skippedResult()
        }
    }

    // Builder variants with explicit error handling

    fun <R : Base> addOrSkip(name: String? = null, builder: () -> R): OperationResult<T> {
        return try {
            val value = builder()
            val key = name ?: value.fhirType().lowercase()
            parameters.getOrPut(key) { mutableListOf() }.add(value)
            OperationResult(parameters, result, outcomes, errorStrategy, failedTasks)
        } catch (e: Exception) {
            outcomes.add(warningOutcome(e))
            OperationResult(parameters, result, outcomes, errorStrategy, failedTasks)
        }
    }

    fun <R : Base> addOrDefault(name: String? = null, default: R, builder: () -> R): OperationResult<R> {
        return try {
            val value = builder()
            val key = name ?: value.fhirType().lowercase()
            parameters.getOrPut(key) { mutableListOf() }.add(value)
            OperationResult(parameters, value, outcomes, errorStrategy, failedTasks)
        } catch (e: Exception) {
            outcomes.add(warningOutcome(e))
            val key = name ?: default.fhirType().lowercase()
            parameters.getOrPut(key) { mutableListOf() }.add(default)
            OperationResult(parameters, default, outcomes, errorStrategy, failedTasks)
        }
    }

    // Query methods

    fun getAllParameters(): Map<String, List<Base>> =
        parameters.mapValues { it.value.toList() }

    fun getAll(name: String): List<Base> =
        parameters[name]?.toList() ?: emptyList()

    /**
     * Returns all accumulated values that are instances of [type], across all keys.
     * Prefer the inline reified overload [getByType] where the type can be inferred.
     */
    fun <R : Base> getByType(type: KClass<R>): List<R> =
        parameters.values.flatten().filterIsInstance(type.java)

    /** Reified overload — no [KClass] argument needed at call sites. */
    inline fun <reified R : Base> getByType(): List<R> = getByType(R::class)

    fun containsKey(name: String): Boolean =
        parameters.containsKey(name)

    fun getKeys(): Set<String> =
        parameters.keys.toSet()

    fun count(name: String): Int =
        parameters[name]?.size ?: 0

    fun totalCount(): Int =
        parameters.values.sumOf { it.size }

    fun isEmpty(): Boolean =
        parameters.isEmpty()

    fun isNotEmpty(): Boolean =
        !isEmpty()

    // Functional transformations

    /**
     * Returns a new result containing only entries whose values match [type].
     * Prefer the inline reified overload [filterByType] where the type can be inferred.
     */
    fun <R : Base> filterByType(type: KClass<R>): OperationResult<T> {
        val filtered = mutableMapOf<String, MutableList<Base>>()
        parameters.forEach { (name, values) ->
            val matchingValues = values.filterIsInstance(type.java)
            if (matchingValues.isNotEmpty()) {
                filtered[name] = matchingValues.toMutableList()
            }
        }
        return OperationResult(filtered, result, outcomes, errorStrategy, failedTasks)
    }

    /** Reified overload — no [KClass] argument needed at call sites. */
    inline fun <reified R : Base> filterByType(): OperationResult<T> = filterByType(R::class)

    fun filterByName(name: String): OperationResult<T> {
        val filtered = mutableMapOf<String, MutableList<Base>>()
        parameters[name]?.let { values ->
            filtered[name] = values.toMutableList()
        }
        return OperationResult(filtered, result, outcomes, errorStrategy, failedTasks)
    }

    fun mapValues(transform: (Base) -> Base): OperationResult<T> {
        val transformed = parameters.mapValues { (_, values) ->
            values.map(transform).toMutableList()
        }.toMutableMap()
        return OperationResult(transformed, result, outcomes, errorStrategy, failedTasks)
    }

    // Reference linking

    /**
     * Wires FHIR references between accumulated resources using explicit [rules].
     *
     * For each rule, every resource of [ReferenceLinkRule.sourceType] is paired with every
     * resource of [ReferenceLinkRule.targetType] (cross-product, skipping self-pairs) and
     * the rule's setter is invoked to assign the reference.
     *
     * Returns a new [OperationResult] with the same parameters; mutations happen in-place
     * on the already-accumulated resource objects (HAPI FHIR resources are mutable).
     */
    fun linkReferences(vararg rules: ReferenceLinkRule<*, *>): OperationResult<T> {
        val allResources = parameters.values.flatten().filterIsInstance<Resource>()
        rules.forEach { rule ->
            val sources = allResources.filter { rule.sourceType.java.isInstance(it) }
            val targets = allResources.filter { rule.targetType.java.isInstance(it) }
            require(targets.size <= 1) {
                "Ambiguous reference target: ${targets.size} resources of type " +
                "'${rule.targetType.simpleName}' found; exactly one is required per rule"
            }
            val target = targets.singleOrNull() ?: return@forEach
            sources.forEach { source ->
                if (source !== target) rule.applyTo(source, target)
            }
        }
        return OperationResult(parameters, result, outcomes, errorStrategy, failedTasks)
    }

    /**
     * Automatically wires FHIR references between accumulated resources by introspecting
     * each resource's HAPI child properties.
     *
     * Algorithm:
     * 1. Build an index of ID-bearing resources keyed by `fhirType().lowercase()`.
     * 2. For every accumulated resource, iterate its [Base.children] properties.
     * 3. For each unset property whose typeCode starts with `"Reference("`, parse the
     *    allowed target types from the typeCode (e.g. `"Reference(Patient|Group)"`).
     * 4. If exactly one matching resource exists in the index for one of those types,
     *    assign a `Reference("<Type>/<id>")` via [Base.setProperty].
     *
     * Only unset reference properties are touched; already-populated references are left
     * unchanged.  Ambiguous cases (multiple candidates for the same reference property)
     * are silently skipped to avoid incorrect wiring.
     *
     * Returns a new [OperationResult] wrapping the same (mutated) parameter map.
     */
    fun linkReferences(): OperationResult<T> {
        val allResources = parameters.values.flatten().filterIsInstance<Resource>()

        // Index resources that have an id, by their fhirType (lowercase)
        val resourceIndex: Map<String, List<Resource>> = allResources
            .filter { it.hasId() }
            .groupBy { it.fhirType().lowercase() }

        allResources.forEach { source ->
            source.children().forEach { property ->
                val typeCode = property.typeCode ?: return@forEach
                if (!typeCode.startsWith("Reference(")) return@forEach
                // Only wire unset (empty) reference slots
                if (property.hasValues()) return@forEach

                // Parse allowed types: "Reference(Patient|Group)" → ["patient", "group"]
                val allowedTypes = typeCode
                    .removePrefix("Reference(")
                    .removeSuffix(")")
                    .split("|")
                    .map { it.trim().lowercase() }

                // Find all candidates across allowed types
                val candidates: List<Resource> = allowedTypes.flatMap { type ->
                    resourceIndex[type]?.filter { it !== source } ?: emptyList()
                }

                // Wire only when unambiguous (exactly one candidate)
                if (candidates.size == 1) {
                    val target = candidates.single()
                    val ref = Reference("${target.fhirType()}/${target.idPart}")
                    runCatching { source.setProperty(property.name, ref) }
                }
            }
        }
        return OperationResult(parameters, result, outcomes, errorStrategy, failedTasks)
    }

    // Core methods

    /**
     * Serialises the accumulated resources to a FHIR [Parameters] resource.
     *
     * Keys that contain a dot are treated as hierarchical paths, matching the composite
     * `"parent.child"` keys produced by [fromParameters] when it flattens nested
     * [Parameters.ParametersParameterComponent.part] entries.  This ensures a full
     * round-trip: `fromParameters(p).toParameters()` reconstructs the original nested
     * structure rather than emitting flat `"parent.child"` parameter names.
     *
     * Keys without dots are emitted as ordinary top-level parameters (one entry per value),
     * exactly as before.
     */
    fun toParameters(): Parameters = Parameters().apply {
        val fhirParams = this
        buildParameterComponents(parameters) { name ->
            fhirParams.addParameter().also { it.name = name }
        }
    }

    fun toBundleEntry(resource: Base): Bundle.BundleEntryComponent =
        Bundle.BundleEntryComponent().apply {
            if (resource is Resource) setResource(resource)
        }

    fun toBundle(
        type: Bundle.BundleType,
        configBlock: ((Bundle.BundleEntryComponent) -> Unit)? = null
    ): Bundle = Bundle().apply {
        this.type = type
        val allResources = parameters.values.flatten().filterIsInstance<Resource>()
        allResources.forEach { resource ->
            val entry = toBundleEntry(resource)
            when (type) {
                Bundle.BundleType.TRANSACTION, Bundle.BundleType.BATCH -> {
                    entry.request = Bundle.BundleEntryRequestComponent().apply {
                        if (resource.hasId()) {
                            method = Bundle.HTTPVerb.PUT
                            url = "${resource.resourceType}/${resource.idPart}"
                        } else {
                            method = Bundle.HTTPVerb.POST
                            url = resource.resourceType.toString()
                        }
                    }
                }
                Bundle.BundleType.SEARCHSET -> {
                    entry.search = Bundle.BundleEntrySearchComponent().apply {
                        mode = Bundle.SearchEntryMode.MATCH
                    }
                }
                else -> {}
            }
            configBlock?.invoke(entry)
            addEntry(entry)
        }
        if (type == Bundle.BundleType.SEARCHSET) {
            total = allResources.size
        }
    }

    fun toTransactionBundle(): Bundle = toBundle(Bundle.BundleType.TRANSACTION)

    fun toBatchBundle(): Bundle = toBundle(Bundle.BundleType.BATCH)

    /**
     * Returns the most recently added value, typed as [T].
     * No cast is needed because [T] is tracked through the pipeline generics.
     * For list-producing steps use the [getResultList] extension.
     */
    fun getResult(): T = result ?: throw IllegalStateException("No result set")

    // Private helpers

    /**
     * Recursively builds [Parameters.ParametersParameterComponent] entries from [entries].
     *
     * [addComponent] abstracts the difference between adding to a [Parameters] root
     * (`parameters.addParameter()`) and adding to a parent part (`component.addPart()`),
     * so the same logic handles both levels.
     *
     * Algorithm:
     * - Collect all unique top-level segments (the portion of each key before the first dot),
     *   preserving map insertion order.
     * - For a segment whose values live directly under that key (no dot sub-keys):
     *   emit one component per value (leaf node).
     * - For a segment whose values all live under dot-qualified sub-keys:
     *   emit a single parent component with no value/resource, then recurse for its children.
     *
     * This means `"address.city"` and `"address.country"` together produce:
     * ```
     * name="address"
     *   part: name="city",    value=…
     *   part: name="country", value=…
     * ```
     * and `"outer.inner.leaf"` produces three levels of nesting.
     */
    private fun buildParameterComponents(
        entries: Map<String, List<Base>>,
        addComponent: (String) -> Parameters.ParametersParameterComponent
    ) {
        // Unique top-level segments in insertion order
        val topSegments = entries.keys.mapTo(linkedSetOf()) { it.substringBefore('.') }

        topSegments.forEach { segment ->
            val directValues = entries[segment]
            // Sub-entries for keys like "segment.rest" — strip the "segment." prefix
            val childEntries: Map<String, List<Base>> = entries
                .filterKeys { it.startsWith("$segment.") }
                .mapKeys { (key, _) -> key.removePrefix("$segment.") }

            if (childEntries.isEmpty()) {
                // Leaf: one component per value
                directValues?.forEach { value ->
                    addComponent(segment).apply {
                        when (value) {
                            is Type     -> setValue(value)
                            is Resource -> setResource(value)
                        }
                    }
                }
            } else {
                // Branch: single parent component whose children are built recursively
                val parent = addComponent(segment)
                buildParameterComponents(childEntries) { childName ->
                    parent.addPart().also { it.name = childName }
                }
            }
        }
    }

    private fun warningOutcome(e: Exception): OperationOutcome = OperationOutcome().apply {
        addIssue().apply {
            severity = OperationOutcome.IssueSeverity.WARNING
            code = OperationOutcome.IssueType.EXCEPTION
            diagnostics = e.message ?: e.javaClass.simpleName
        }
    }

    private fun addToParameters(values: List<Base>, name: String?) {
        if (name != null) {
            values.forEach { value ->
                parameters.getOrPut(name) { mutableListOf() }.add(value)
            }
        } else {
            values.forEach { value ->
                val key = value.fhirType().lowercase()
                parameters.getOrPut(key) { mutableListOf() }.add(value)
            }
        }
    }

    // Factory methods

    companion object {
        internal fun fromMap(
            params: Map<String, List<Base>>,
            outcomes: List<OperationOutcome> = emptyList(),
            failedTasks: Map<String, OperationOutcome> = emptyMap()
        ): OperationResult<Base> {
            val mutable = params.mapValues { it.value.toMutableList() }.toMutableMap()
            return OperationResult(mutable, null, outcomes.toMutableList(), ErrorStrategy.FAIL_FAST, failedTasks.toMutableMap())
        }

        fun <T : Base> of(value: T, name: String? = null, errorStrategy: ErrorStrategy = ErrorStrategy.FAIL_FAST): OperationResult<T> {
            val key = name ?: value.fhirType().lowercase()
            val params = mutableMapOf<String, MutableList<Base>>()
            params.getOrPut(key) { mutableListOf() }.add(value)
            return OperationResult(params, value, mutableListOf(), errorStrategy, mutableMapOf())
        }

        fun <T : Base> of(values: List<T>, name: String? = null, errorStrategy: ErrorStrategy = ErrorStrategy.FAIL_FAST): OperationResult<List<T>> {
            val params = mutableMapOf<String, MutableList<Base>>()
            val instance = OperationResult(params, values, mutableListOf(), errorStrategy, mutableMapOf())
            instance.addToParameters(values, name)
            return instance
        }

        /**
         * Builds an [OperationResult] from an existing FHIR [Parameters] resource.
         *
         * Each top-level [Parameters.parameter] entry is stored using its [Parameters.ParametersParameterComponent.name]
         * as the map key. Both resource parameters and primitive-type parameters (e.g. [StringType],
         * [BooleanType]) are supported.
         *
         * Parameters that contain nested [Parameters.ParametersParameterComponent.part] entries (but no
         * direct value or resource) are flattened recursively: each part is stored under the composite
         * key `"<parentName>.<partName>"`, preserving the hierarchy in the key name.
         *
         * The returned result has no "current" typed head ([getResult] will throw); use
         * [fromParametersTyped] when a typed head is required.
         */
        fun fromParameters(parameters: Parameters): OperationResult<Base> {
            val params = mutableMapOf<String, MutableList<Base>>()
            parameters.parameter.forEach { param -> populateFromParam(params, param) }
            return OperationResult(params, null, mutableListOf(), ErrorStrategy.FAIL_FAST, mutableMapOf())
        }

        /**
         * Like [fromParameters] but also sets the typed pipeline head to the first value stored
         * under [primaryKey] that is an instance of [type].
         *
         * @throws IllegalArgumentException if no value of [type] exists under [primaryKey].
         */
        fun <T : Base> fromParametersTyped(
            parameters: Parameters,
            primaryKey: String,
            type: KClass<T>
        ): OperationResult<T> {
            val params = mutableMapOf<String, MutableList<Base>>()
            parameters.parameter.forEach { param -> populateFromParam(params, param) }
            val primary = params[primaryKey]
                ?.filterIsInstance(type.java)
                ?.firstOrNull()
                ?: throw IllegalArgumentException(
                    "No value of type '${type.simpleName}' found under key '$primaryKey'"
                )
            return OperationResult(params, primary, mutableListOf(), ErrorStrategy.FAIL_FAST, mutableMapOf())
        }

        /** Reified overload of [fromParametersTyped] — no [KClass] argument needed at call sites. */
        inline fun <reified T : Base> fromParametersTyped(
            parameters: Parameters,
            primaryKey: String
        ): OperationResult<T> = fromParametersTyped(parameters, primaryKey, T::class)

        /**
         * Builds an [OperationResult] from a FHIR [Bundle].
         *
         * Each entry that carries a resource is stored using `resource.fhirType().lowercase()` as
         * the key. Entries without a resource (e.g. bare transaction-response entries) are silently
         * skipped. Resources of the same FHIR type accumulate under the same key.
         *
         * The returned result has no typed head; use [fromParametersTyped] pattern via
         * [fromBundle] with a custom [keyStrategy] or cast after the fact if a head is needed.
         *
         * @see fromBundle overload with [keyStrategy] for custom key extraction.
         */
        fun fromBundle(bundle: Bundle): OperationResult<Base> =
            fromBundle(bundle) { entry -> entry.resource.fhirType().lowercase() }

        /**
         * Builds an [OperationResult] from a FHIR [Bundle] with a custom key extractor.
         *
         * [keyStrategy] receives each [Bundle.BundleEntryComponent] that has a resource and must
         * return the map key for that resource.  Returning an empty string causes the entry to be
         * skipped, which is useful when the caller wants to filter entries conditionally.
         *
         * Example — key by `fullUrl`:
         * ```kotlin
         * OperationResult.fromBundle(bundle) { entry -> entry.fullUrl ?: entry.resource.fhirType() }
         * ```
         */
        fun fromBundle(
            bundle: Bundle,
            keyStrategy: (Bundle.BundleEntryComponent) -> String
        ): OperationResult<Base> {
            val params = mutableMapOf<String, MutableList<Base>>()
            bundle.entry
                .filter { it.hasResource() }
                .forEach { entry ->
                    val key = keyStrategy(entry)
                    if (key.isNotEmpty()) {
                        params.getOrPut(key) { mutableListOf() }.add(entry.resource)
                    }
                }
            return OperationResult(params, null, mutableListOf(), ErrorStrategy.FAIL_FAST, mutableMapOf())
        }

        // ── Private helpers ───────────────────────────────────────────────────

        /**
         * Recursively populates [params] from a single [Parameters.ParametersParameterComponent].
         *
         * - Resource parameter  → stored under [keyPrefix]`.<name>` (or just `<name>` at root)
         * - Value parameter     → stored under [keyPrefix]`.<name>`
         * - Parts-only entry    → each part is processed recursively with the current key as prefix
         */
        private fun populateFromParam(
            params: MutableMap<String, MutableList<Base>>,
            param: Parameters.ParametersParameterComponent,
            keyPrefix: String = ""
        ) {
            val key = if (keyPrefix.isEmpty()) param.name else "$keyPrefix.${param.name}"
            when {
                param.hasResource() -> params.getOrPut(key) { mutableListOf() }.add(param.resource)
                param.hasValue()    -> params.getOrPut(key) { mutableListOf() }.add(param.value)
                param.hasPart()     -> param.part.forEach { part ->
                    populateFromParam(params, part, key)
                }
            }
        }
    }
}

/**
 * Type-safe accessor for pipeline stages that produced a list (i.e. after [OperationResult.addAll]
 * or [OperationResult.addAllUsing]).  Because [T] is already `List<R>`, [OperationResult.getResult]
 * returns the correctly typed list — this extension is a readable alias for that call.
 *
 * ```kotlin
 * val appointments: List<Appointment> = OperationResult.of(patient())
 *     .addAll { listOf(Appointment(), Appointment()) }
 *     .getResultList()
 * ```
 */
fun <R : Base> OperationResult<List<R>>.getResultList(): List<R> = getResult()
