package com.github.knivram.airlockiamconfigplugin.completion

import com.github.knivram.airlockiamconfigplugin.catalog.AirlockPluginCatalog
import com.github.knivram.airlockiamconfigplugin.catalog.PropertyKind
import com.github.knivram.airlockiamconfigplugin.psi.IamConfigFileModel
import com.github.knivram.airlockiamconfigplugin.psi.IamConfigPsi
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequenceItem

/**
 * Completion inside IamConfig files, scoped to the current file:
 *  - value of `ref:`/`parentRef:` → plugins declared in this file (shown by displayName, inserts the id),
 *    slot-scoped to the enclosing slot's assignable types when known, else all file plugins;
 *  - value of `type:` → Airlock plugin type names: slot-scoped to the enclosing slot's assignable
 *    types when known, else the bundled 8.6 catalog ∪ types used in this file;
 *  - value of a plugin-valued property → scaffolds for the `plugin`/`pluginList`/`pluginMap` wrapper
 *    forms, inserting the wrapper skeleton ready to author (Improvement #3);
 *  - key inside a plugin's `properties:` → that plugin type's declared property names (catalog);
 *  - key inside a plugin definition/link mapping (slot value, `pluginList` item, `pluginMap` value,
 *    `plugin` wrapper, or `spec:` item) → the plugin root keys `type`/`id`/`displayName`/`comment`/
 *    `parentRef`/`properties` (and `ref`) not already present (Improvement #2).
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
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement().inside(PlatformPatterns.psiElement(YAMLKeyValue::class.java)),
            PluginRootKeyProvider,
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
                key in IamConfigPsi.REF_KEYS -> addRefCompletions(file, keyValue, result)
                key == IamConfigPsi.TYPE_KEY -> addTypeCompletions(file, keyValue, result)
                key == IamConfigPsi.ENV_KEY -> addEnvCompletions(file, result)
                else -> addValueFormScaffolds(keyValue, result)
            }
        }

        /**
         * Value of `env:` → the configuration environment names already used in this file (e.g.
         * `intellij`/`tilt`). Free-form: environments are author-defined, so these are suggestions only
         * and the platform's other contributors are left to run.
         */
        private fun addEnvCompletions(file: YAMLFile, result: CompletionResultSet) {
            for (name in IamConfigFileModel.of(file).envNames) {
                result.addElement(LookupElementBuilder.create(name).withIcon(AllIcons.Nodes.Variable))
            }
        }

        /**
         * When the caret is at the value of a plugin-valued property (`Plugin-Link`/`Plugin-List`/
         * `Plugin-Map`) that is a direct child of a `properties:` mapping, offer a scaffold for each
         * wrapper form documented in the YAML spec, inserting a ready-to-author skeleton:
         *
         *  - `plugin`     → `- plugin:` then a nested `type: ` (the direct mapping form is reached by
         *                   typing `type:` / via root-key completion);
         *  - `pluginList` → `- pluginList:` then a list item `- type: `;
         *  - `pluginMap`  → `- pluginMap:` then a `key:` with a nested `type: `.
         *
         * Only fires for catalog-known parent types so unknown/custom plugins never get spurious offers.
         */
        private fun addValueFormScaffolds(keyValue: YAMLKeyValue, result: CompletionResultSet) {
            if (!IamConfigPsi.isDirectChildOfProperties(keyValue)) return
            val propertiesKv = keyValue.parentMapping?.parent as? YAMLKeyValue ?: return
            val parentType = IamConfigPsi.enclosingPluginType(propertiesKv) ?: return

            val catalog = AirlockPluginCatalog.getInstance()
            if (!catalog.isKnownType(parentType)) return
            val prop = catalog.property(parentType, keyValue.keyText) ?: return
            if (!prop.kind.isPluginValued) return

            // Match the IAM config editor's compact layout: the sequence dash sits at the SAME column as
            // the property key (not one level past it). The wrapper body is one level (2 spaces) past the
            // dash, and a pluginMap's nested mapping one level further. The body opens with a newline so
            // the value moves onto its own line under `key:`.
            val keyIndent = columnOf(keyValue)
            val itemIndent = " ".repeat(keyIndent)
            val bodyIndent = " ".repeat(keyIndent + 2)
            val deepIndent = " ".repeat(keyIndent + 4)

            when (prop.kind) {
                PropertyKind.PLUGIN_LINK ->
                    result.addElement(
                        scaffold("plugin", "\n$itemIndent- plugin:\n$bodyIndent" + TYPE_STUB),
                    )

                PropertyKind.PLUGIN_LIST ->
                    result.addElement(
                        scaffold("pluginList", "\n$itemIndent- pluginList:\n$bodyIndent- " + TYPE_STUB),
                    )

                PropertyKind.PLUGIN_MAP ->
                    result.addElement(
                        scaffold("pluginMap", "\n$itemIndent- pluginMap:\n${bodyIndent}key:\n$deepIndent" + TYPE_STUB),
                    )

                else -> {}
            }
        }

        /** The 0-based column at which [keyValue] starts (its indentation in the document). */
        private fun columnOf(keyValue: YAMLKeyValue): Int {
            val text = keyValue.containingFile.viewProvider.contents
            val start = keyValue.textRange.startOffset
            var col = 0
            var i = start - 1
            while (i >= 0 && text[i] != '\n') {
                col++
                i--
            }
            return col
        }

        /**
         * A scaffold lookup matched/typed as [formName] (`plugin`/`pluginList`/`pluginMap`) whose
         * [ScaffoldInsertHandler] rewrites the property value with the multi-line wrapper [body],
         * leaving the caret just after the trailing `type: `. High priority so it sorts above noise.
         */
        private fun scaffold(formName: String, body: String): LookupElement {
            val element = LookupElementBuilder.create(Scaffold(formName, body), formName)
                .withPresentableText(formName)
                .withTailText("  (scaffold)", true)
                .withIcon(AllIcons.Nodes.Plugin)
                .withInsertHandler(ScaffoldInsertHandler)
            return PrioritizedLookupElement.withPriority(element, 200.0)
        }

        /**
         * Value of `ref:`/`parentRef:` → plugins declared in this file. When the enclosing slot is a
         * catalog-known plugin-valued property with a non-empty assignable list, offer ONLY plugins whose
         * type is assignable to that slot; otherwise (unknown slot, e.g. a `spec:`/`parentRef` item, or an
         * empty assignable list) fall back to every plugin in the file.
         */
        private fun addRefCompletions(file: YAMLFile, refKv: YAMLKeyValue, result: CompletionResultSet) {
            val catalog = AirlockPluginCatalog.getInstance()
            val slot = refKv.parentMapping?.let { IamConfigPsi.slotOf(it) }
            val assignable = slot
                ?.let { catalog.property(it.parentType, it.propertyName) }
                ?.takeIf { it.kind.isPluginValued }
                ?.assignable
                ?.takeIf { it.isNotEmpty() }
                ?.toSet()

            val seen = HashSet<String>()
            for (info in IamConfigFileModel.of(file).definitionInfos) {
                if (assignable != null && info.type !in assignable) continue
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

    /**
     * Plugin-definition root-key completion (Improvement #2): when the caret is on a KEY inside a plugin
     * definition/link mapping — a slot value, a `pluginList` item, a `pluginMap` value, the inner mapping
     * of a `plugin:` wrapper, or a top-level `spec:` item — offer the plugin root keys
     * (`type`/`id`/`displayName`/`comment`/`parentRef`/`properties`, plus `ref` as the link alternative)
     * not already present. Distinct from [PropertyNameProvider], which owns keys inside `properties:`.
     */
    private object PluginRootKeyProvider : CompletionProvider<CompletionParameters>() {
        override fun addCompletions(
            parameters: CompletionParameters,
            context: ProcessingContext,
            result: CompletionResultSet,
        ) {
            val file = parameters.position.containingFile as? YAMLFile ?: return
            if (!IamConfigPsi.isIamConfigFile(file)) return

            // The completion dummy is a bare scalar leaf; `valueHost` is the scalar/plain-text node and
            // `structuralParent` is what directly contains it — a mapping (existing plugin body), a
            // sequence item (fresh list element) or a key-value (the empty value of a slot/map entry).
            val valueHost = parameters.position.parent ?: return
            when (val structuralParent = valueHost.parent) {
                is YAMLMapping -> handleExistingMapping(structuralParent, result)
                is YAMLSequenceItem -> handleFreshSequenceItem(structuralParent, result)
                is YAMLKeyValue -> handleEmptyValueOf(structuralParent, result)
                else -> return
            }
        }

        /** Caret typing a new key inside an existing plugin mapping (it already holds e.g. `type:`). */
        private fun handleExistingMapping(mapping: YAMLMapping, result: CompletionResultSet) {
            if (IamConfigPsi.isPropertiesMapping(mapping)) return // PropertyNameProvider owns this.
            // Inside a `var:` block: offer the configuration-variable keys (`name`/`sensitive`).
            if ((mapping.parent as? YAMLKeyValue)?.keyText == IamConfigPsi.VAR_KEY) {
                offerVarKeys(mapping.keyValues.mapNotNull { it.keyText.takeIf(String::isNotBlank) }.toSet(), result)
                return
            }
            if (!IamConfigPsi.isPluginMappingContext(mapping)) return
            if (mapping.getKeyValueByKey(IamConfigPsi.REF_KEY) != null) return // a link has no body keys.
            val present = mapping.keyValues.mapNotNull { it.keyText.takeIf(String::isNotBlank) }.toSet()
            offerRootKeys(present, allowRef = IamConfigPsi.TYPE_KEY !in present, result = result)
        }

        /** Caret is a fresh `- ` item: a new plugin in a `spec:` or `pluginList:` sequence. */
        private fun handleFreshSequenceItem(item: YAMLSequenceItem, result: CompletionResultSet) {
            val sequenceKv = item.parent?.parent as? YAMLKeyValue ?: return
            if (sequenceKv.keyText != IamConfigPsi.SPEC_KEY && sequenceKv.keyText !in IamConfigPsi.WRAPPER_KEYS) return
            offerRootKeys(present = emptySet(), allowRef = true, result = result)
        }

        /** Caret is the still-empty value of a key-value: an empty `var:` block, plugin-valued slot or `pluginMap` entry. */
        private fun handleEmptyValueOf(kv: YAMLKeyValue, result: CompletionResultSet) {
            if (kv.keyText == IamConfigPsi.VAR_KEY) {
                offerVarKeys(present = emptySet(), result = result)
                return
            }
            val startsAPlugin = when {
                // `slot:\n  <caret>` where the slot is a plugin-valued catalog property.
                IamConfigPsi.isDirectChildOfProperties(kv) -> isPluginValuedSlot(kv)
                // `plugin:\n  <caret>` — an empty `plugin` wrapper's inner mapping is always a plugin.
                kv.keyText == "plugin" -> true
                // `pluginMap:\n  key:\n    <caret>` — a map entry value is always a plugin.
                (kv.parentMapping?.parent as? YAMLKeyValue)?.keyText == "pluginMap" -> true
                else -> false
            }
            if (startsAPlugin) offerRootKeys(present = emptySet(), allowRef = true, result = result)
        }

        /** True if [slotKv] names a plugin-valued property of its enclosing (catalog-known) plugin type. */
        private fun isPluginValuedSlot(slotKv: YAMLKeyValue): Boolean {
            val propertiesKv = slotKv.parentMapping?.parent as? YAMLKeyValue ?: return false
            val parentType = IamConfigPsi.enclosingPluginType(propertiesKv) ?: return false
            val catalog = AirlockPluginCatalog.getInstance()
            if (!catalog.isKnownType(parentType)) return false
            return catalog.property(parentType, slotKv.keyText)?.kind?.isPluginValued == true
        }

        private fun offerRootKeys(present: Set<String>, allowRef: Boolean, result: CompletionResultSet) {
            // We own root-key completion in these positions: suppress the platform's generic YAML keys.
            result.stopHere()
            if (allowRef && IamConfigPsi.REF_KEY !in present) {
                result.addElement(rootKeyElement(IamConfigPsi.REF_KEY, 150.0))
            }
            for (key in IamConfigPsi.PLUGIN_ROOT_KEYS) {
                if (key in present) continue
                val priority = if (key == IamConfigPsi.TYPE_KEY) 200.0 else 50.0
                result.addElement(rootKeyElement(key, priority))
            }
        }

        /** Offer the `var:` block keys (`name`/`sensitive`) not already present; `name` first. */
        private fun offerVarKeys(present: Set<String>, result: CompletionResultSet) {
            result.stopHere()
            if (IamConfigPsi.VAR_NAME_KEY !in present) {
                result.addElement(rootKeyElement(IamConfigPsi.VAR_NAME_KEY, 200.0))
            }
            if (IamConfigPsi.VAR_SENSITIVE_KEY !in present) {
                result.addElement(rootKeyElement(IamConfigPsi.VAR_SENSITIVE_KEY, 50.0))
            }
        }

        private fun rootKeyElement(key: String, priority: Double): LookupElement {
            val element = LookupElementBuilder.create(key).withIcon(AllIcons.Nodes.Property)
            return PrioritizedLookupElement.withPriority(element, priority)
        }
    }

    /** A plugin-value wrapper scaffold: matched as [name], inserting the multi-line [body]. */
    private data class Scaffold(val name: String, val body: String)

    /**
     * Replaces the property value the caret sits in with the scaffold body. The default lookup insertion
     * has already written the form name (e.g. `pluginList`) over the caret token; we delete that token,
     * everything after it on the line, AND any whitespace between it and the property's `:` (so the value
     * moves cleanly onto its own indented lines from the body's leading newline). The caret lands at the
     * end (just after the trailing `type: `), ready to type the plugin type.
     */
    private object ScaffoldInsertHandler : InsertHandler<LookupElement> {
        override fun handleInsert(context: InsertionContext, item: LookupElement) {
            val scaffold = item.`object` as? Scaffold ?: return
            val document = context.document
            val text = document.charsSequence
            // Back up over the indentation/space(s) before the typed token (after `key:` on the same line,
            // or the leading indent of a fresh line the completion was triggered on).
            var from = context.startOffset
            while (from > 0 && text[from - 1] == ' ') from--
            // The body opens with a newline to push the value onto its own line under `key:`. If the caret
            // is already at the start of a fresh line (preceded by a newline / start of file), that leading
            // newline would leave a blank line between `key:` and the wrapper — drop it and reuse the line.
            val atLineStart = from == 0 || text[from - 1] == '\n'
            val body = if (atLineStart) scaffold.body.removePrefix("\n") else scaffold.body
            val lineEnd = document.getLineEndOffset(document.getLineNumber(context.startOffset))
            document.replaceString(from, maxOf(context.tailOffset, lineEnd), body)
            context.editor.caretModel.moveToOffset(from + body.length)
            context.commitDocument()
        }
    }

    private companion object {
        /** Trailing stub a scaffold ends with; caret lands right after it. */
        private const val TYPE_STUB = "type: "
    }
}
