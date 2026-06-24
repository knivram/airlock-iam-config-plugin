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
import com.intellij.util.ProcessingContext
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Completion inside IamConfig files, scoped to the current file:
 *  - value of `ref:`/`parentRef:` → plugins declared in this file (shown by displayName, inserts the id);
 *  - value of `type:` → Airlock plugin type names (bundled 8.6 catalog ∪ types used in this file).
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
                key == IamConfigPsi.TYPE_KEY -> addTypeCompletions(file, result)
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

        private fun addTypeCompletions(file: YAMLFile, result: CompletionResultSet) {
            val seen = HashSet<String>()
            for (entry in AirlockPluginCatalog.getInstance().entries) {
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
}
