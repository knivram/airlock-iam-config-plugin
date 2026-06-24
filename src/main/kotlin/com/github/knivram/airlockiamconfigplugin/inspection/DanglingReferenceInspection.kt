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
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Flags a `ref:`/`parentRef:` whose id is not declared by any plugin in the same file.
 * Airlock IAM forbids dangling references, so this catches a real, otherwise-silent error.
 */
class DanglingReferenceInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file as? YAMLFile ?: return PsiElementVisitor.EMPTY_VISITOR
        if (!IamConfigPsi.isIamConfigFile(file)) return PsiElementVisitor.EMPTY_VISITOR
        val model = IamConfigFileModel.of(file)

        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element !is YAMLScalar || !IamConfigPsi.isReferenceValueScalar(element)) return
                val id = element.textValue
                if (id.isNotBlank() && id !in model.definitions) {
                    holder.registerProblem(
                        element,
                        AirlockBundle.message("inspection.danglingRef.message", id),
                        ProblemHighlightType.LIKE_UNKNOWN_SYMBOL,
                    )
                }
            }
        }
    }
}
