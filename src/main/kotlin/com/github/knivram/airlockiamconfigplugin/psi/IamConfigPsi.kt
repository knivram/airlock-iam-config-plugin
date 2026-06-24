package com.github.knivram.airlockiamconfigplugin.psi

import com.intellij.psi.PsiElement
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Structural facts about Airlock IAM config YAML, shared by every feature.
 *
 * Data model (verified against real configs):
 *  - A *plugin definition* is a mapping with sibling keys `type:` and `id:` (+ optional
 *    `displayName`, `comment`, `properties`). The `id` value is the navigation target.
 *  - A *reference* is the scalar value of a key named `ref` or `parentRef`; its value is
 *    the `id` of another plugin.
 *  - Ids are matched by *value*, never by UUID shape: hand-authored ids may be plain words
 *    (e.g. `register-mtan`). A property named `id` that is NOT a sibling of `type:`
 *    (e.g. `properties.id: register-mtan`) is deliberately not a definition.
 */
object IamConfigPsi {

    /** Keys whose scalar value points at a plugin `id`. */
    val REF_KEYS: Set<String> = setOf("ref", "parentRef")

    const val ID_KEY: String = "id"
    const val TYPE_KEY: String = "type"
    const val DISPLAY_NAME_KEY: String = "displayName"

    private const val ROOT_TYPE = "IamConfig"
    private const val SCHEMA_KEY = "schemaVersion"
    private const val SCHEMA_PREFIX = "iam.airlock.com"

    /** True if [file] is an Airlock IAM config (`type: IamConfig` + `schemaVersion: iam.airlock.com…`). */
    fun isIamConfigFile(file: YAMLFile): Boolean {
        val root = file.documents.firstOrNull()?.topLevelValue as? YAMLMapping ?: return false
        val type = root.getKeyValueByKey(TYPE_KEY)?.valueText
        if (type != ROOT_TYPE) return false
        val schema = root.getKeyValueByKey(SCHEMA_KEY)?.valueText ?: return false
        return schema.startsWith(SCHEMA_PREFIX)
    }

    /** True if [scalar] is the value of a `ref:`/`parentRef:` key. */
    fun isReferenceValueScalar(scalar: YAMLScalar): Boolean {
        val keyValue = scalar.parent as? YAMLKeyValue ?: return false
        if (keyValue.keyText !in REF_KEYS) return false
        return keyValue.value === scalar
    }

    /** True if [keyValue] is a plugin definition's `id` (an `id` whose mapping also has a `type`). */
    fun isPluginDefinitionId(keyValue: YAMLKeyValue): Boolean {
        if (keyValue.keyText != ID_KEY) return false
        return keyValue.parentMapping?.getKeyValueByKey(TYPE_KEY) != null
    }

    /** The reference key (`ref`/`parentRef`) a scalar belongs to, or null. */
    fun referenceKeyOf(element: PsiElement): String? {
        val scalar = element as? YAMLScalar ?: return null
        val keyValue = scalar.parent as? YAMLKeyValue ?: return null
        return keyValue.keyText.takeIf { it in REF_KEYS && keyValue.value === scalar }
    }

    /** For a plugin definition mapping, the `(type, displayName)` shown to the user. */
    fun definitionInfo(mapping: YAMLMapping): DefinitionInfo? {
        val type = mapping.getKeyValueByKey(TYPE_KEY)?.valueText?.takeIf { it.isNotBlank() } ?: return null
        val id = mapping.getKeyValueByKey(ID_KEY)?.valueText?.takeIf { it.isNotBlank() } ?: return null
        val displayName = mapping.getKeyValueByKey(DISPLAY_NAME_KEY)?.valueText?.takeIf { it.isNotBlank() }
        return DefinitionInfo(id, type, displayName)
    }

    data class DefinitionInfo(val id: String, val type: String, val displayName: String?)
}
