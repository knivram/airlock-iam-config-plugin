package me.knivram.iamplugin.inspection

import me.knivram.iamplugin.AirlockBundle
import me.knivram.iamplugin.catalog.AirlockPluginCatalog
import me.knivram.iamplugin.psi.IamConfigFileModel
import me.knivram.iamplugin.psi.IamConfigPsi
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLMapping

/**
 * Flags a child plugin that sits in a plugin-valued slot whose assignable list is non-empty and does
 * not contain the child's type.
 *
 * The child's type comes from its own `type:`, or from a same-file `ref:` resolved to the referenced
 * plugin's `type:`. Scoped to a KNOWN parent type with a non-empty assignable list, and only a KNOWN
 * child type that is provably not assignable is flagged (an unknown/custom child type is left alone to
 * avoid false positives).
 */
class WrongChildTypeInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file as? YAMLFile ?: return PsiElementVisitor.EMPTY_VISITOR
        if (!IamConfigPsi.isIamConfigFile(file)) return PsiElementVisitor.EMPTY_VISITOR
        val catalog = AirlockPluginCatalog.forFile(file)
        val model = IamConfigFileModel.of(file)

        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element !is YAMLMapping || !IamConfigPsi.isPluginMapping(element)) return
                val slot = IamConfigPsi.slotOf(element) ?: return
                if (!catalog.isKnownType(slot.parentType)) return

                val property = catalog.property(slot.parentType, slot.propertyName) ?: return
                if (!property.kind.isPluginValued || property.assignable.isEmpty()) return

                val childType = childTypeOf(element, model) ?: return
                if (childType in property.assignable) return
                if (!catalog.isKnownType(childType)) return

                val anchor = element.getKeyValueByKey(IamConfigPsi.TYPE_KEY)?.value
                    ?: IamConfigPsi.REF_KEYS.firstNotNullOfOrNull { element.getKeyValueByKey(it)?.value }
                    ?: element
                holder.registerProblem(
                    anchor,
                    AirlockBundle.message(
                        "inspection.wrongChildType.message",
                        childType,
                        slot.propertyName,
                        slot.parentType,
                    ),
                    ProblemHighlightType.WEAK_WARNING,
                )
            }
        }
    }

    /** The child plugin's type: its own `type:`, else a same-file `ref:` resolved to a `type:`. */
    private fun childTypeOf(mapping: YAMLMapping, model: IamConfigFileModel.Model): String? {
        mapping.getKeyValueByKey(IamConfigPsi.TYPE_KEY)?.valueText?.takeIf { it.isNotBlank() }
            ?.let { return it }
        val ref = IamConfigPsi.REF_KEYS
            .firstNotNullOfOrNull { mapping.getKeyValueByKey(it)?.valueText?.takeIf(String::isNotBlank) }
            ?: return null
        val idKv = model.definitions[ref]?.firstOrNull() ?: return null
        return idKv.parentMapping?.getKeyValueByKey(IamConfigPsi.TYPE_KEY)?.valueText
            ?.takeIf { it.isNotBlank() }
    }
}
