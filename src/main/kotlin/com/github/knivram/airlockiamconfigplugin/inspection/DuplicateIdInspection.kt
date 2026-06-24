package com.github.knivram.airlockiamconfigplugin.inspection

import com.github.knivram.airlockiamconfigplugin.AirlockBundle
import com.github.knivram.airlockiamconfigplugin.psi.IamConfigFileModel
import com.github.knivram.airlockiamconfigplugin.psi.IamConfigPsi
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue

/**
 * Flags a plugin `id:` declared more than once within the same file. Duplicate ids in one file make
 * `ref:` resolution ambiguous.
 *
 * Scope is intentionally a single file: configs are commonly duplicated across files (e.g. a
 * production copy), where the same id legitimately appears in several files.
 */
class DuplicateIdInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file as? YAMLFile ?: return PsiElementVisitor.EMPTY_VISITOR
        if (!IamConfigPsi.isIamConfigFile(file)) return PsiElementVisitor.EMPTY_VISITOR
        val model = IamConfigFileModel.of(file)

        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element !is YAMLKeyValue || !IamConfigPsi.isPluginDefinitionId(element)) return
                val id = element.valueText
                if (id.isNotBlank() && (model.definitions[id]?.size ?: 0) > 1) {
                    holder.registerProblem(
                        element.value ?: element,
                        AirlockBundle.message("inspection.duplicateId.message", id),
                        ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                    )
                }
            }
        }
    }
}
