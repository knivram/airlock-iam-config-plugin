package com.github.knivram.airlockiamconfigplugin.reference

import com.github.knivram.airlockiamconfigplugin.psi.IamConfigPsi
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.util.ProcessingContext
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Attaches a [PluginIdReference] to every `ref:`/`parentRef:` scalar in an IamConfig file.
 *
 * Registered for `language="yaml"`; plain YAML scalars already route through the
 * contributed-reference machinery, so a provider is all that is needed.
 */
class RefReferenceContributor : PsiReferenceContributor() {

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(YAMLScalar::class.java),
            object : PsiReferenceProvider() {
                override fun getReferencesByElement(
                    element: PsiElement,
                    context: ProcessingContext,
                ): Array<PsiReference> {
                    val scalar = element as? YAMLScalar ?: return PsiReference.EMPTY_ARRAY
                    if (!IamConfigPsi.isReferenceValueScalar(scalar)) return PsiReference.EMPTY_ARRAY
                    if ((scalar.containingFile as? YAMLFile)?.let(IamConfigPsi::isIamConfigFile) != true) {
                        return PsiReference.EMPTY_ARRAY
                    }
                    val id = scalar.textValue
                    if (id.isBlank()) return PsiReference.EMPTY_ARRAY
                    return arrayOf(PluginIdReference(scalar, id))
                }
            },
        )
    }
}
