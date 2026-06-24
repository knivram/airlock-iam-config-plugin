package com.github.knivram.airlockiamconfigplugin.reference

import com.github.knivram.airlockiamconfigplugin.psi.IamConfigFileModel
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.ResolveResult
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * A reference from a `ref:`/`parentRef:` scalar to the plugin `id:` it points at, within the same file.
 *
 * Poly-variant + soft: if the same id is declared twice in the file, Ctrl+Click offers a chooser
 * instead of silently picking one; an undefined id is not flagged as an error by the platform
 * (the dangling-reference inspection handles that intentionally).
 *
 * The text range is left to the base class, which derives it from the YAML scalar element
 * manipulator (quotes already excluded) — do not hand-compute it.
 */
class PluginIdReference(scalar: YAMLScalar, private val id: String) :
    PsiReferenceBase.Poly<YAMLScalar>(scalar, /* soft = */ true) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        if (id.isBlank()) return ResolveResult.EMPTY_ARRAY
        val file = element.containingFile as? YAMLFile ?: return ResolveResult.EMPTY_ARRAY
        val definitions = IamConfigFileModel.of(file).definitions[id] ?: return ResolveResult.EMPTY_ARRAY
        return PsiElementResolveResult.createResults(definitions)
    }

    // Completion is provided by AirlockCompletionContributor, not via reference variants.
    override fun getVariants(): Array<Any> = emptyArray()
}
