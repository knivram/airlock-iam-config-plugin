package com.github.knivram.airlockiamconfigplugin.usage

import com.github.knivram.airlockiamconfigplugin.AirlockBundle
import com.github.knivram.airlockiamconfigplugin.psi.IamConfigPsi
import com.intellij.psi.PsiElement
import com.intellij.usages.impl.rules.UsageType
import com.intellij.usages.impl.rules.UsageTypeProvider
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Groups `ref:`/`parentRef:` usages under their own category in the Find Usages tool window,
 * so plugin references read as "Airlock plugin reference" rather than a generic YAML value.
 *
 * This is a composing (list) extension, so it only adds a label and never shadows YAML's own
 * find-usages behaviour.
 */
class IamConfigUsageTypeProvider : UsageTypeProvider {

    override fun getUsageType(element: PsiElement): UsageType? {
        val scalar = element as? YAMLScalar ?: element.parent as? YAMLScalar ?: return null
        if (!IamConfigPsi.isReferenceValueScalar(scalar)) return null
        val file = scalar.containingFile as? YAMLFile ?: return null
        return if (IamConfigPsi.isIamConfigFile(file)) REFERENCE else null
    }

    companion object {
        private val REFERENCE = UsageType { AirlockBundle.message("usage.type.pluginReference") }
    }
}
