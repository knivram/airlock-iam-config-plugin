package com.github.knivram.airlockiamconfigplugin.completion

import com.github.knivram.airlockiamconfigplugin.catalog.AirlockPluginCatalog
import com.github.knivram.airlockiamconfigplugin.psi.IamConfigFileModel
import com.github.knivram.airlockiamconfigplugin.psi.IamConfigPsi
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Completion inside IamConfig files, scoped to the current file:
 *  - value of `ref:`/`parentRef:` → plugins declared in this file (shown by displayName, inserts the id);
 *  - value of `type:` → Airlock plugin type names: slot-scoped to the enclosing slot's assignable
 *    types when known, else the bundled 8.6 catalog ∪ types used in this file;
 *  - key inside a plugin's `properties:` → that plugin type's declared property names (catalog).
 */
class AirlockCompletionContributor : CompletionContributor() {

    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement().withParent(
                PlatformPatterns.psiElement(YAMLScalar::class.java).withParent(
                    PlatformPatterns.psiElement(YAMLKeyValue::class.java),
                ),
            ),
            Provider,
        )
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement().inside(PlatformPatterns.psiElement(YAMLKeyValue::class.java)),
            PropertyNameProvider,
        )
    }

    private object Provider : CompletionProvider<CompletionParameters>() {
        override fun addCompletions(
            parameters: CompletionParameters,
            context: ProcessingContext,
            result: CompletionResultSet,
        ) {
            val scalar = parameters.position.parent as? YAMLScalar ?: return
            val keyValue = scalar.parent as? YAMLKeyValue ?: return
            if (keyValue.value !== scalar) return
            val file = parameters.position.containingFile as? YAMLFile ?: return
            if (!IamConfigPsi.isIamConfigFile(file)) return

            val key = keyValue.keyText
            when {
                key in IamConfigPsi.REF_KEYS -> addRefCompletions(file, result)
                key == IamConfigPsi.TYPE_KEY -> addTypeCompletions(file, keyValue, result)
            }
        }

        private fun addRefCompletions(file: YAMLFile, result: CompletionResultSet) {
            val seen = HashSet<String>()
            for (info in IamConfigFileModel.of(file).definitionInfos) {
                if (!seen.add(info.id)) continue
                val element = LookupElementBuilder.create(info.id)
                    .withPresentableText(info.displayName ?: info.id)
                    .withTailText("  ${info.type}", true)
                    .withTypeText(info.id, true)
                    .withIcon(AllIcons.Nodes.Plugin)
                    .withLookupStrings(listOfNotNull(info.id, info.type, info.displayName))
                // Prefer plugins that have a human-readable name.
                val priority = if (info.displayName != null) 100.0 else 50.0
                result.addElement(PrioritizedLookupElement.withPriority(element, priority))
            }
        }

        private fun addTypeCompletions(file: YAMLFile, typeKv: YAMLKeyValue, result: CompletionResultSet) {
            val catalog = AirlockPluginCatalog.getInstance()
            val childMapping = typeKv.parentMapping
            val slot = childMapping?.let { IamConfigPsi.slotOf(it) }
            val assignable = slot
                ?.let { catalog.property(it.parentType, it.propertyName) }
                ?.takeIf { it.kind.isPluginValued }
                ?.assignable
                ?.takeIf { it.isNotEmpty() }

            // Slot-scoped: when the slot declares a non-empty assignable list, offer ONLY those types.
            if (assignable != null) {
                for (type in assignable.distinct().sorted()) {
                    var element = LookupElementBuilder.create(type).withIcon(AllIcons.Nodes.Plugin)
                    catalog.entry(type)?.title?.takeIf { it.isNotBlank() }?.let {
                        element = element.withTailText("  $it", true)
                    }
                    result.addElement(element)
                }
                return
            }

            // Fallback: full bundled catalog ∪ types used in this file.
            val seen = HashSet<String>()
            for (entry in catalog.entries) {
                if (!seen.add(entry.type)) continue
                var element = LookupElementBuilder.create(entry.type).withIcon(AllIcons.Nodes.Class)
                if (!entry.title.isNullOrBlank()) {
                    element = element.withTailText("  ${entry.title}", true)
                }
                result.addElement(element)
            }
            for (type in IamConfigFileModel.of(file).types) {
                if (!seen.add(type)) continue
                result.addElement(LookupElementBuilder.create(type).withIcon(AllIcons.Nodes.Class))
            }
        }
    }

    /**
     * Property-name completion: when the caret sits on a KEY inside a plugin's `properties:` mapping,
     * offer that plugin type's declared property names (from the catalog) not already present in the
     * mapping — mandatory ones first. Only fires for catalog-known parent types.
     */
    private object PropertyNameProvider : CompletionProvider<CompletionParameters>() {
        override fun addCompletions(
            parameters: CompletionParameters,
            context: ProcessingContext,
            result: CompletionResultSet,
        ) {
            val file = parameters.position.containingFile as? YAMLFile ?: return
            if (!IamConfigPsi.isIamConfigFile(file)) return

            // Only complete keys inside a plugin's `properties:` block.
            val propertiesKv = propertiesKeyForKeyPosition(parameters) ?: return
            val parentType = IamConfigPsi.enclosingPluginType(propertiesKv) ?: return

            val catalog = AirlockPluginCatalog.getInstance()
            if (!catalog.isKnownType(parentType)) return

            val existing = (propertiesKv.value as? YAMLMapping)
                ?.keyValues
                ?.mapNotNull { it.keyText.takeIf(String::isNotBlank) }
                ?.toSet()
                ?: emptySet()

            // We own property-name completion for known plugin types: suppress the platform's default
            // YAML key completion (which would otherwise re-offer sibling keys already in the mapping).
            result.stopHere()

            for (prop in catalog.propertiesOf(parentType)) {
                if (prop.name in existing) continue // already present
                var element = LookupElementBuilder.create(prop.name)
                    .withIcon(AllIcons.Nodes.Property)
                    .withTypeText(prop.kind.name.lowercase(), true)
                if (prop.mandatory) {
                    element = element.withTailText("  (mandatory)", true)
                }
                val priority = if (prop.mandatory) 100.0 else 50.0
                result.addElement(PrioritizedLookupElement.withPriority(element, priority))
            }
        }

        /**
         * When the caret is on a KEY being typed inside a plugin's `properties:` mapping, returns that
         * `properties:` key-value; null otherwise (including value positions like `type: <caret>`).
         *
         * The completion dummy makes a fresh key parse in one of two shapes: as a new key-value inside
         * the `properties:` value mapping (sibling keys already present), or — for an otherwise-empty
         * block — as the lone scalar value of the `properties:` key itself. Both are accepted; a caret
         * inside a real value scalar (e.g. `type:`/`ref:`) is rejected because its enclosing key-value
         * is that property, not `properties:`.
         */
        private fun propertiesKeyForKeyPosition(parameters: CompletionParameters): YAMLKeyValue? {
            val kv = PsiTreeUtil.getParentOfType(parameters.position, YAMLKeyValue::class.java) ?: return null
            // Empty block: the dummy is the scalar value of `properties:` itself.
            if (kv.keyText == IamConfigPsi.PROPERTIES_KEY) return kv
            // Typing a new key: the dummy plain-text sits directly in the `properties:` value mapping,
            // so the enclosing key-value is `properties:` — already handled above. Anything else (the
            // caret is the value of some property key) is not a key position.
            return null
        }
    }
}
