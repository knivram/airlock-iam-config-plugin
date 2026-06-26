package me.knivram.iamplugin.usage

import me.knivram.iamplugin.psi.IamConfigPsi
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.find.findUsages.PsiElement2UsageTargetAdapter
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.usages.UsageTarget
import com.intellij.usages.UsageTargetProvider
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Makes Find Usages available when the caret is on a plugin's `id` *value* (the id itself), not only
 * on the literal `id` key.
 *
 * The platform's default target resolution maps a caret to a [YAMLKeyValue] only via its name (the
 * key text), so a caret on the value yields no target. This provider surfaces the declaring `id`
 * key-value as the target, which the [IamConfigFindUsagesHandlerFactory] then handles.
 */
class IamConfigUsageTargetProvider : UsageTargetProvider {

    override fun getTargets(editor: Editor, file: PsiFile): Array<UsageTarget>? {
        if (file !is YAMLFile || !IamConfigPsi.isIamConfigFile(file)) return null
        val offset = editor.caretModel.offset
        val element = file.findElementAt(offset) ?: file.findElementAt(offset - 1) ?: return null
        return targetsFor(element)
    }

    override fun getTargets(psiElement: PsiElement): Array<UsageTarget>? = targetsFor(psiElement)

    private fun targetsFor(element: PsiElement): Array<UsageTarget>? {
        val scalar = PsiTreeUtil.getParentOfType(element, YAMLScalar::class.java, false) ?: return null
        val keyValue = scalar.parent as? YAMLKeyValue ?: return null
        if (keyValue.value !== scalar || !IamConfigPsi.isPluginDefinitionId(keyValue)) return null
        return arrayOf(PsiElement2UsageTargetAdapter(keyValue, true))
    }
}
