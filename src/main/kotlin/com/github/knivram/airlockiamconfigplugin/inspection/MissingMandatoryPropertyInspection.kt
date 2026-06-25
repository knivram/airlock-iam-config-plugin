package com.github.knivram.airlockiamconfigplugin.inspection

import com.github.knivram.airlockiamconfigplugin.AirlockBundle
import com.github.knivram.airlockiamconfigplugin.catalog.AirlockPluginCatalog
import com.github.knivram.airlockiamconfigplugin.psi.IamConfigPsi
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping

/**
 * Flags a known-type plugin that omits a property the catalog marks Mandatory.
 *
 * Disabled by default: in Airlock IAM mandatory-ness is frequently conditional (a property is only
 * required given some other setting), so unconditionally requiring every documented-mandatory property
 * is noisy. Offered as an opt-in for authors who want the stricter check.
 *
 * Scoped to KNOWN types; the present keys are read from the plugin's own `properties:` mapping.
 */
class MissingMandatoryPropertyInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file as? YAMLFile ?: return PsiElementVisitor.EMPTY_VISITOR
        if (!IamConfigPsi.isIamConfigFile(file)) return PsiElementVisitor.EMPTY_VISITOR
        val catalog = AirlockPluginCatalog.getInstance()

        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element !is YAMLMapping) return
                // Only a plugin definition (has a type:) carries declarable properties.
                val type = element.getKeyValueByKey(IamConfigPsi.TYPE_KEY)?.valueText
                    ?.takeIf { it.isNotBlank() } ?: return
                if (!catalog.isKnownType(type)) return

                val mandatory = catalog.propertiesOf(type).filter { it.mandatory }
                if (mandatory.isEmpty()) return

                val propertiesMapping = (element.getKeyValueByKey(IamConfigPsi.PROPERTIES_KEY)?.value
                    as? YAMLMapping)
                val present: Set<String> = propertiesMapping
                    ?.keyValues
                    ?.mapNotNull { it.keyText.takeIf(String::isNotBlank) }
                    ?.toSet()
                    ?: emptySet()

                val missing = mandatory.map { it.name }.filter { it !in present }
                if (missing.isEmpty()) return

                val anchor: PsiElement = element.getKeyValueByKey(IamConfigPsi.TYPE_KEY) ?: element
                holder.registerProblem(
                    anchor,
                    AirlockBundle.message(
                        "inspection.missingMandatory.message",
                        type,
                        missing.joinToString(", "),
                    ),
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                )
            }
        }
    }
}
