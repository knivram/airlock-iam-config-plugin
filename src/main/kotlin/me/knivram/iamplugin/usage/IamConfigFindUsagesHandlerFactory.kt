package me.knivram.iamplugin.usage

import me.knivram.iamplugin.psi.IamConfigFileModel
import me.knivram.iamplugin.psi.IamConfigPsi
import me.knivram.iamplugin.reference.PluginIdReference
import com.intellij.find.findUsages.FindUsagesHandler
import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.search.SearchScope
import com.intellij.usageView.UsageInfo
import com.intellij.util.Processor
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Provides Find Usages for a plugin `id:` by listing every `ref:`/`parentRef:` pointing at it in the
 * same file.
 *
 * Scoped to one file on purpose (configs are commonly duplicated across files), and uses the file
 * model rather than the platform's default name-based reference search, which keys on the element
 * name (`"id"`) and never inspects the value positions where references live.
 */
class IamConfigFindUsagesHandlerFactory : FindUsagesHandlerFactory() {

    override fun canFindUsages(element: PsiElement): Boolean =
        element is YAMLKeyValue && IamConfigPsi.isPluginDefinitionId(element)

    override fun createFindUsagesHandler(element: PsiElement, forHighlightUsages: Boolean): FindUsagesHandler? =
        if (canFindUsages(element)) Handler(element as YAMLKeyValue) else null

    private class Handler(private val idKeyValue: YAMLKeyValue) : FindUsagesHandler(idKeyValue) {

        private val id: String = idKeyValue.valueText

        override fun processElementUsages(
            element: PsiElement,
            processor: Processor<in UsageInfo>,
            options: FindUsagesOptions,
        ): Boolean {
            for (scalar in referencingScalars()) {
                if (!processor.process(UsageInfo(scalar))) return false
            }
            return true
        }

        // Highlight Usages in File / caret-on-id highlighting goes through this, NOT
        // processElementUsages; the default impl uses name-based search which cannot find
        // hyphen-split ids. Mirror the same file-local lookup so highlighting works too.
        override fun findReferencesToHighlight(target: PsiElement, searchScope: SearchScope): Collection<PsiReference> =
            referencingScalars().mapNotNull { scalar ->
                scalar.references.firstOrNull { it is PluginIdReference }
            }

        private fun referencingScalars(): List<YAMLScalar> {
            if (id.isBlank()) return emptyList()
            val file = idKeyValue.containingFile as? YAMLFile ?: return emptyList()
            return IamConfigFileModel.of(file).references[id] ?: emptyList()
        }
    }
}
