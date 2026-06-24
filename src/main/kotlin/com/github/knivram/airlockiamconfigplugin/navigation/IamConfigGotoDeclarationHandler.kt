package com.github.knivram.airlockiamconfigplugin.navigation

import com.github.knivram.airlockiamconfigplugin.psi.IamConfigFileModel
import com.github.knivram.airlockiamconfigplugin.psi.IamConfigPsi
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Makes Go to Declaration (Ctrl/Cmd+B) useful on a plugin `id` value.
 *
 * On a `ref:`/`parentRef:` value the reference itself already navigates to the definition. On the
 * `id:` value — which is the declaration — there is nothing further to declare, so Cmd+B instead
 * navigates to the plugin's usages (jump if there is one, a chooser if there are several), mirroring
 * the IDE's "Declaration or Usages" behaviour for declarations.
 */
class IamConfigGotoDeclarationHandler : GotoDeclarationHandler {

    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor?,
    ): Array<PsiElement>? {
        val element = sourceElement ?: return null
        val scalar = PsiTreeUtil.getParentOfType(element, YAMLScalar::class.java, false) ?: return null
        val keyValue = scalar.parent as? YAMLKeyValue ?: return null
        if (keyValue.value !== scalar || !IamConfigPsi.isPluginDefinitionId(keyValue)) return null
        val file = scalar.containingFile as? YAMLFile ?: return null
        val usages = IamConfigFileModel.of(file).references[scalar.textValue] ?: return null
        return usages.toTypedArray<PsiElement>().takeIf { it.isNotEmpty() }
    }
}
