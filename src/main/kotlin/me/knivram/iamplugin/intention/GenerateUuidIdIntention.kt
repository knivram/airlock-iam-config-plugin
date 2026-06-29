package me.knivram.iamplugin.intention

import me.knivram.iamplugin.AirlockBundle
import me.knivram.iamplugin.psi.IamConfigPsi
import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.YAMLElementGenerator
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import java.util.UUID

/**
 * Alt+Enter on a plugin definition's empty `id:` offers to fill it with a fresh random UUID.
 *
 * Plugin ids are opaque (usually UUIDs) and hand-typing them is tedious and error-prone, so this just
 * inserts `UUID.randomUUID()`. Scoped like the rest of the plugin: only in IamConfig files, and only on a
 * *plugin-definition* id — an `id:` whose mapping also has a sibling `type:` and which is not a property
 * literally named `id` (e.g. `StepId`'s `properties.id`). Offered only when the id has no value yet.
 */
class GenerateUuidIdIntention : PsiElementBaseIntentionAction(), DumbAware {

    override fun getFamilyName(): String = AirlockBundle.message("intention.generateUuid.text")

    override fun getText(): String = familyName

    override fun startInWriteAction(): Boolean = true

    override fun isAvailable(project: Project, editor: Editor?, element: PsiElement): Boolean {
        val file = element.containingFile as? YAMLFile ?: return false
        if (!IamConfigPsi.isIamConfigFile(file)) return false
        val idKv = idKeyValueAt(element) ?: return false
        return IamConfigPsi.isPluginDefinitionId(idKv) && idKv.valueText.isBlank()
    }

    override fun invoke(project: Project, editor: Editor?, element: PsiElement) {
        val idKv = idKeyValueAt(element) ?: return
        // A freshly generated UUID matches the opaque ids used throughout these configs.
        val newValue = YAMLElementGenerator.getInstance(project)
            .createYamlKeyValue(IamConfigPsi.ID_KEY, UUID.randomUUID().toString())
            .value ?: return
        idKv.setValue(newValue)
    }

    /**
     * The `id:` key-value the caret sits on. The caret on an empty `id:` usually lands in the trailing
     * whitespace/newline, so walk back a few leaves and take the nearest enclosing `id` key-value; the
     * [isAvailable] gates (plugin-definition id + blank value) keep this from matching anything else.
     */
    private fun idKeyValueAt(element: PsiElement): YAMLKeyValue? =
        generateSequence(element as PsiElement?) { PsiTreeUtil.prevLeaf(it) }
            .take(5)
            .mapNotNull { PsiTreeUtil.getParentOfType(it, YAMLKeyValue::class.java, false) }
            .firstOrNull { it.keyText == IamConfigPsi.ID_KEY }
}
