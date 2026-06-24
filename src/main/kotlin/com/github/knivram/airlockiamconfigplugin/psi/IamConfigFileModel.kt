package com.github.knivram.airlockiamconfigplugin.psi

import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Everything the features need about a single IamConfig file: plugin definitions, references and
 * type names. Built in one PSI pass and cached per file (invalidated when that file is edited).
 *
 * Scope is deliberately one file: configs are commonly duplicated (e.g. a production copy), so the
 * same ids legitimately appear in several files. Resolving, finding usages and validating across
 * files would then be wrong (duplicate-id false positives, ambiguous navigation).
 */
object IamConfigFileModel {

    /** Per-file index. All PSI elements belong to [file] and stay valid until it is modified. */
    class Model(
        /** id value -> the `id:` key-values declaring it in this file (>1 means a same-file duplicate). */
        val definitions: Map<String, List<YAMLKeyValue>>,
        /** Plugin definitions in this file, for completion. */
        val definitionInfos: List<IamConfigPsi.DefinitionInfo>,
        /** id value -> the `ref:`/`parentRef:` scalars pointing at it in this file. */
        val references: Map<String, List<YAMLScalar>>,
        /** Every `type:` value used in this file. */
        val types: Set<String>,
    )

    private val EMPTY = Model(emptyMap(), emptyList(), emptyMap(), emptySet())

    fun of(file: YAMLFile): Model {
        if (!IamConfigPsi.isIamConfigFile(file)) return EMPTY
        return CachedValuesManager.getCachedValue(file) {
            CachedValueProvider.Result.create(build(file), file)
        }
    }

    private fun build(file: YAMLFile): Model {
        val definitions = HashMap<String, MutableList<YAMLKeyValue>>()
        val definitionInfos = ArrayList<IamConfigPsi.DefinitionInfo>()
        val references = HashMap<String, MutableList<YAMLScalar>>()
        val types = HashSet<String>()

        for (mapping in PsiTreeUtil.collectElementsOfType(file, YAMLMapping::class.java)) {
            val typeKv = mapping.getKeyValueByKey(IamConfigPsi.TYPE_KEY) ?: continue
            typeKv.valueText.takeIf { it.isNotBlank() }?.let(types::add)
            val idKv = mapping.getKeyValueByKey(IamConfigPsi.ID_KEY) ?: continue
            val id = idKv.valueText.takeIf { it.isNotBlank() } ?: continue
            definitions.getOrPut(id) { ArrayList() }.add(idKv)
            IamConfigPsi.definitionInfo(mapping)?.let(definitionInfos::add)
        }

        for (scalar in PsiTreeUtil.collectElementsOfType(file, YAMLScalar::class.java)) {
            if (IamConfigPsi.isReferenceValueScalar(scalar)) {
                scalar.textValue.takeIf { it.isNotBlank() }
                    ?.let { references.getOrPut(it) { ArrayList() }.add(scalar) }
            }
        }

        return Model(definitions, definitionInfos, references, types)
    }
}
