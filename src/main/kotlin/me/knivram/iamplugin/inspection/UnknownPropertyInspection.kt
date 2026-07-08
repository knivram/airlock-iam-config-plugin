package me.knivram.iamplugin.inspection

import me.knivram.iamplugin.AirlockBundle
import me.knivram.iamplugin.catalog.AirlockPluginCatalog
import me.knivram.iamplugin.psi.IamConfigPsi
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue

/**
 * Flags a key under a plugin's `properties:` mapping that the plugin type does not declare in the
 * bundled catalog.
 *
 * Scoped to types KNOWN in the catalog (custom/unknown types are skipped, so a custom plugin never
 * yields false positives), and to plugins whose catalog schema actually lists properties (a type
 * documented with no properties is skipped rather than flagging everything). Only direct children of
 * a `properties:` mapping are considered; structural/wrapper keys (`pluginList`/`pluginMap`/`plugin`)
 * and value-override keys (`value`/`env`/`var`/`storageId`) live elsewhere and are never flagged.
 */
class UnknownPropertyInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file as? YAMLFile ?: return PsiElementVisitor.EMPTY_VISITOR
        if (!IamConfigPsi.isIamConfigFile(file)) return PsiElementVisitor.EMPTY_VISITOR
        val catalog = AirlockPluginCatalog.forFile(file)

        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element !is YAMLKeyValue) return
                if (element.keyText in IamConfigPsi.WRAPPER_KEYS) return
                // Defensive: value-entry structural keys (value/valueList/plugin*/env/context/var) are
                // never legitimate direct children of a `properties:` mapping, but if malformed YAML ever
                // surfaces one here it is a structural key, not an unknown property — never flag it.
                if (element.keyText in IamConfigPsi.VALUE_CARRIER_KEYS) return
                if (element.keyText in IamConfigPsi.ENTRY_QUALIFIER_KEYS) return
                if (element.keyText == IamConfigPsi.STORAGE_ID_KEY) return
                if (!IamConfigPsi.isDirectChildOfProperties(element)) return

                val propertiesKv = element.parentMapping?.parent as? YAMLKeyValue ?: return
                val parentType = IamConfigPsi.enclosingPluginType(propertiesKv) ?: return
                if (!catalog.isKnownType(parentType)) return

                val known = catalog.propertyNames(parentType)
                if (known.isEmpty()) return
                if (element.keyText in known) return

                holder.registerProblem(
                    element.key ?: element,
                    AirlockBundle.message("inspection.unknownProperty.message", element.keyText, parentType),
                    ProblemHighlightType.WEAK_WARNING,
                )
            }
        }
    }
}
