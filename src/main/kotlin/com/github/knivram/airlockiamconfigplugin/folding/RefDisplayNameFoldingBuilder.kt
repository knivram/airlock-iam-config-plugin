package com.github.knivram.airlockiamconfigplugin.folding

import com.github.knivram.airlockiamconfigplugin.psi.IamConfigFileModel
import com.github.knivram.airlockiamconfigplugin.psi.IamConfigPsi
import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Display-only code folding for plugin references: a `ref:`/`parentRef:` whose id resolves (within the
 * same file) to a plugin definition that has a non-blank `displayName` is folded so the id value shows the
 * target's display name instead. Analogous to IntelliJ folding a Spring `@Value` to its resolved value.
 *
 * The fold is purely visual — the underlying text stays the id, so navigation, reference resolution,
 * find-usages and inspections are unaffected. Collapsed by default; placing the caret on the fold expands
 * it back to the id.
 *
 * Scope mirrors the rest of the plugin: a single file, only known IamConfig files, only resolvable
 * targets. No display name, an unresolved/dangling id, or a non-IamConfig file produce no fold.
 */
class RefDisplayNameFoldingBuilder : FoldingBuilderEx(), DumbAware {

    override fun buildFoldRegions(
        root: PsiElement,
        document: Document,
        quick: Boolean,
    ): Array<FoldingDescriptor> {
        val file = root.containingFile as? YAMLFile ?: return FoldingDescriptor.EMPTY_ARRAY
        if (!IamConfigPsi.isIamConfigFile(file)) return FoldingDescriptor.EMPTY_ARRAY
        val model = IamConfigFileModel.of(file)

        val descriptors = ArrayList<FoldingDescriptor>()
        for (scalar in PsiTreeUtil.collectElementsOfType(root, YAMLScalar::class.java)) {
            if (!IamConfigPsi.isReferenceValueScalar(scalar)) continue
            val id = scalar.textValue.takeIf { it.isNotBlank() } ?: continue
            // Same-file target: id KV -> its plugin mapping -> the definition's display name.
            val idKv = model.definitions[id]?.firstOrNull() ?: continue
            val mapping = idKv.parentMapping ?: continue
            // definitionInfo only returns a non-blank displayName (or null).
            val displayName = IamConfigPsi.definitionInfo(mapping)?.displayName ?: continue
            // Quote-stripped value range, made absolute. Folds the id only (never the `ref:` key);
            // for a quoted scalar the quotes stay visible and only the inner id is folded.
            val valueRange = ElementManipulators.getValueTextRange(scalar)
                .shiftRight(scalar.textRange.startOffset)
            if (valueRange.isEmpty) continue
            descriptors += FoldingDescriptor(scalar.node, valueRange, null, displayName)
        }
        return descriptors.toTypedArray()
    }

    // The placeholder text travels on each FoldingDescriptor (built with the placeholder-carrying
    // constructor), so the node-based lookups are never the source of truth here.
    override fun getPlaceholderText(node: ASTNode, range: TextRange): String? = null

    override fun getPlaceholderText(node: ASTNode): String? = null

    // Show the display name by default; entering the caret expands it back to the id.
    override fun isCollapsedByDefault(node: ASTNode): Boolean = true
}
