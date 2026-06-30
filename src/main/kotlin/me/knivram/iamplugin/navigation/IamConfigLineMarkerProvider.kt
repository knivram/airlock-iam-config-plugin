package me.knivram.iamplugin.navigation

import me.knivram.iamplugin.psi.IamConfigFileModel
import me.knivram.iamplugin.psi.IamConfigPsi
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Gutter icons that let the user jump between a plugin `id:` definition and its `ref:`/`parentRef:`
 * usages, both directions, within the same file (matching the single-file scoping of every feature).
 *
 *  - On a plugin-definition `id:` value that is referenced somewhere: an "overriding" icon that
 *    navigates to the `ref:`/`parentRef:` scalars pointing at it.
 *  - On a `ref:`/`parentRef:` value whose target id is defined in this file: an "implementing" icon
 *    that navigates to the declaring `id:` key-value(s).
 *
 * Per the platform contract for line-marker providers, markers are produced only for LEAF elements
 * (so the daemon never reports duplicate/grayed markers); the enclosing [YAMLScalar] supplies the
 * semantic value, and the marker is anchored back on the leaf.
 */
class IamConfigLineMarkerProvider : RelatedItemLineMarkerProvider() {

    override fun collectNavigationMarkers(
        element: PsiElement,
        result: MutableCollection<in RelatedItemLineMarkerInfo<*>>,
    ) {
        // Act on leaves only; everything below derives from the enclosing scalar.
        if (element.firstChild != null) return
        val file = element.containingFile as? YAMLFile ?: return
        if (!IamConfigPsi.isIamConfigFile(file)) return
        val scalar = PsiTreeUtil.getParentOfType(element, YAMLScalar::class.java, false) ?: return

        val keyValue = scalar.parent as? YAMLKeyValue ?: return
        if (keyValue.value !== scalar) return
        val id = scalar.textValue.takeIf { it.isNotBlank() } ?: return
        val model = IamConfigFileModel.of(file)

        if (IamConfigPsi.isPluginDefinitionId(keyValue)) {
            val refs = model.references[id]?.takeIf { it.isNotEmpty() } ?: return
            val marker = NavigationGutterIconBuilder.create(AllIcons.Gutter.OverridingMethod)
                .setTargets(refs)
                .setTooltipText("Navigate to references")
                .createLineMarkerInfo(element)
            result.add(marker)
            return
        }

        if (IamConfigPsi.referenceKeyOf(scalar) != null) {
            val definitions = model.definitions[id]?.takeIf { it.isNotEmpty() } ?: return
            val marker = NavigationGutterIconBuilder.create(AllIcons.Gutter.ImplementingMethod)
                .setTargets(definitions)
                .setTooltipText("Navigate to definition")
                .createLineMarkerInfo(element)
            result.add(marker)
        }
    }
}
