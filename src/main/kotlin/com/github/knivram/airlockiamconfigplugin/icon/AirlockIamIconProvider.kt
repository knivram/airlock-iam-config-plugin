package com.github.knivram.airlockiamconfigplugin.icon

import com.github.knivram.airlockiamconfigplugin.AirlockIcons
import com.github.knivram.airlockiamconfigplugin.psi.IamConfigPsi
import com.intellij.ide.IconProvider
import com.intellij.psi.PsiElement
import org.jetbrains.yaml.psi.YAMLFile
import javax.swing.Icon

/**
 * Shows the Airlock IAM icon for IamConfig files (project view, editor tabs, navigation), leaving
 * every other file with its default icon.
 */
class AirlockIamIconProvider : IconProvider() {

    override fun getIcon(element: PsiElement, flags: Int): Icon? {
        val file = element as? YAMLFile ?: return null
        return if (IamConfigPsi.isIamConfigFile(file)) AirlockIcons.FILE else null
    }
}
