package me.knivram.iamplugin.inspection

import me.knivram.iamplugin.AirlockBundle
import me.knivram.iamplugin.psi.IamConfigPsi
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence
import org.jetbrains.yaml.psi.YAMLSequenceItem

/**
 * Warns about misconfigured configuration variables (`var:`) and value entries (`- value:` /
 * `- valueList:` / `- plugin:` / `- pluginList:` / `- pluginMap:` plus `env`/`context`/`var`
 * qualifiers), per the Airlock YAML format and "Using environment variables" specifications.
 *
 * Scoped to the single file and to the *structural* shape of value lists; it never touches plugin
 * mappings, plain scalar values, `storageId`/`[FILE]` payloads or inner `pluginList`/`pluginMap`
 * items, so it cannot misfire on the full value grammar (Improvement #5). All value-entry and `var:`
 * anchoring is gated on the enclosing value-entry ([IamConfigPsi.isValueEntry]): a `var:` outside a
 * value-entry (e.g. a property literally named `var` on a custom type) is never validated.
 *
 * Checks:
 *  - V1 `var:` is missing a (non-blank) `name` (incl. a malformed scalar `var:`) → WARNING
 *  - V2 a key inside `var:` is not `name`/`sensitive`     → WEAK_WARNING
 *  - V3 `var.sensitive` is not a boolean                  → WEAK_WARNING
 *  - V5 two value entries share the same (env, context)   → WEAK_WARNING (only one applies)
 *  - V6 a sequence-item entry has an env/context/var qualifier but no value carrier → WARNING
 *  - V7 a value entry has a key that is neither a value carrier nor an env/context/var qualifier → WEAK_WARNING
 *  - V8 a value entry declares more than one value carrier → WARNING
 *
 * A value list whose entries all carry an `env`/`context` (no shared/default entry) is legal — the
 * shared environment fallback is optional — so it is deliberately not flagged.
 */
class VariableEnvInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file as? YAMLFile ?: return PsiElementVisitor.EMPTY_VISITOR
        if (!IamConfigPsi.isIamConfigFile(file)) return PsiElementVisitor.EMPTY_VISITOR

        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                when (element) {
                    is YAMLKeyValue ->
                        if (element.keyText == IamConfigPsi.VAR_KEY && isVarInValueEntry(element)) {
                            checkVarBlock(element)
                        }
                    is YAMLMapping -> when {
                        IamConfigPsi.isValueEntry(element) -> checkValueEntry(element)
                        isQualifierOnlyEntry(element) -> holder.registerProblem(
                            element.keyValues.firstOrNull { it.keyText in IamConfigPsi.ENTRY_QUALIFIER_KEYS }
                                ?.let { it.key ?: it } ?: element,
                            AirlockBundle.message("inspection.entry.missingValueForm.message"),
                            ProblemHighlightType.WARNING,
                        )
                    }
                    is YAMLSequence -> checkValueEntryDuplicates(element)
                }
            }

            /** True if [varKv] is a `var:` qualifier sitting inside a value-entry (the only legal position). */
            private fun isVarInValueEntry(varKv: YAMLKeyValue): Boolean =
                varKv.parentMapping?.let { IamConfigPsi.isValueEntry(it) } == true

            /**
             * A sequence-item mapping that carries an env/context/var qualifier but NO value carrier: a
             * value-entry missing its value form. Scoped to qualifier-bearing sequence items so it never
             * flags plain `pluginList`/`spec` items (type:/ref:) or `modifications:` items.
             */
            private fun isQualifierOnlyEntry(mapping: YAMLMapping): Boolean =
                mapping.parent is YAMLSequenceItem &&
                    mapping.keyValues.any { it.keyText in IamConfigPsi.ENTRY_QUALIFIER_KEYS } &&
                    mapping.keyValues.none { it.keyText in IamConfigPsi.VALUE_CARRIER_KEYS }

            /** V1/V2/V3: a `var:` block must have a `name` and only `name`/`sensitive` keys. */
            private fun checkVarBlock(varKv: YAMLKeyValue) {
                val mapping = varKv.value as? YAMLMapping
                if (mapping == null) {
                    // A `var:` with a non-blank scalar value (e.g. `var: SKIN_COLOR`) is malformed: it has no
                    // `name:` key. An empty/null `var:` (mid-typing) is left alone to avoid false positives.
                    if (varKv.value is YAMLScalar && varKv.valueText.isNotBlank()) {
                        holder.registerProblem(
                            varKv.key ?: varKv,
                            AirlockBundle.message("inspection.var.missingName.message"),
                            ProblemHighlightType.WARNING,
                        )
                    }
                    return
                }
                val nameKv = mapping.getKeyValueByKey(IamConfigPsi.VAR_NAME_KEY)
                if (nameKv == null || nameKv.valueText.isBlank()) {
                    holder.registerProblem(
                        varKv.key ?: varKv,
                        AirlockBundle.message("inspection.var.missingName.message"),
                        ProblemHighlightType.WARNING,
                    )
                }
                for (child in mapping.keyValues) {
                    val childKey = child.keyText
                    if (childKey.isBlank()) continue
                    if (childKey !in IamConfigPsi.VAR_KEYS) {
                        holder.registerProblem(
                            child.key ?: child,
                            AirlockBundle.message("inspection.var.unknownKey.message", childKey),
                            ProblemHighlightType.WEAK_WARNING,
                        )
                    } else if (childKey == IamConfigPsi.VAR_SENSITIVE_KEY && !isBoolean(child.valueText)) {
                        holder.registerProblem(
                            child.value ?: child.key ?: child,
                            AirlockBundle.message("inspection.var.sensitiveNotBool.message"),
                            ProblemHighlightType.WEAK_WARNING,
                        )
                    }
                }
            }

            /** V7/V8: a value entry holds exactly one value carrier plus only env/context/var qualifiers. */
            private fun checkValueEntry(entry: YAMLMapping) {
                val carriers = entry.keyValues.filter { it.keyText in IamConfigPsi.VALUE_CARRIER_KEYS }
                if (carriers.size > 1) {
                    for (carrier in carriers) {
                        holder.registerProblem(
                            carrier.key ?: carrier,
                            AirlockBundle.message("inspection.entry.multipleValueForms.message"),
                            ProblemHighlightType.WARNING,
                        )
                    }
                }
                for (child in entry.keyValues) {
                    val childKey = child.keyText
                    if (childKey.isBlank()) continue
                    if (childKey !in IamConfigPsi.VALUE_CARRIER_KEYS && childKey !in IamConfigPsi.ENTRY_QUALIFIER_KEYS) {
                        holder.registerProblem(
                            child.key ?: child,
                            AirlockBundle.message("inspection.entry.unknownKey.message", childKey),
                            ProblemHighlightType.WEAK_WARNING,
                        )
                    }
                }
            }

            /** V5: within one value list, two entries with the same (env, context) shadow each other. */
            private fun checkValueEntryDuplicates(sequence: YAMLSequence) {
                val entries = sequence.items
                    .mapNotNull { it.value as? YAMLMapping }
                    .filter { IamConfigPsi.isValueEntry(it) }
                if (entries.size < 2) return

                val byKey = LinkedHashMap<Pair<String, String>, MutableList<YAMLMapping>>()
                for (entry in entries) {
                    val env = entry.getKeyValueByKey(IamConfigPsi.ENV_KEY)?.valueText?.trim().orEmpty()
                    val context = entry.getKeyValueByKey(IamConfigPsi.CONTEXT_KEY)?.valueText?.trim().orEmpty()
                    byKey.getOrPut(env to context) { ArrayList() }.add(entry)
                }
                for ((key, group) in byKey) {
                    if (group.size < 2) continue
                    val label = key.first.ifBlank { "shared" }
                    for (entry in group) {
                        val anchor = entry.keyValues.firstOrNull { it.keyText in IamConfigPsi.VALUE_CARRIER_KEYS }
                            ?.let { it.key ?: it } ?: entry
                        holder.registerProblem(
                            anchor,
                            AirlockBundle.message("inspection.entry.duplicateEnvContext.message", label),
                            ProblemHighlightType.WEAK_WARNING,
                        )
                    }
                }
            }

            private fun isBoolean(text: String): Boolean = text.trim().trim('\'', '"').lowercase() in BOOLEANS
        }
    }

    private companion object {
        private val BOOLEANS = setOf("true", "false")
    }
}
