package com.github.knivram.airlockiamconfigplugin.psi

import com.intellij.psi.PsiElement
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequenceItem

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
    const val PROPERTIES_KEY: String = "properties"
    const val COMMENT_KEY: String = "comment"
    const val PARENT_REF_KEY: String = "parentRef"
    const val REF_KEY: String = "ref"
    const val SPEC_KEY: String = "spec"

    /** Sequence/value wrapper keys that sit between a property and the child plugin mapping. */
    val WRAPPER_KEYS: Set<String> = setOf("pluginList", "plugin", "pluginMap")

    /**
     * Keys allowed at the root of a plugin-definition mapping (the mapping holding `type:`/`id:`).
     * Ordered so `type` is offered first by completion.
     */
    val PLUGIN_ROOT_KEYS: List<String> =
        listOf(TYPE_KEY, ID_KEY, DISPLAY_NAME_KEY, COMMENT_KEY, PARENT_REF_KEY, PROPERTIES_KEY)

    /**
     * The value-carrier key inside a value-entry: exactly one of these is expected per entry.
     * (`plugin`/`pluginList`/`pluginMap` are also [WRAPPER_KEYS]; `value`/`valueList` are scalar carriers.)
     */
    val VALUE_CARRIER_KEYS: Set<String> = setOf("value", "valueList", "plugin", "pluginList", "pluginMap")

    /** Storage-id key inside a `value:` mapping; a value-override that lives elsewhere, never a property. */
    const val STORAGE_ID_KEY: String = "storageId"

    /** Entry-level qualifier keys that decorate a value-entry alongside its value carrier. */
    const val ENV_KEY: String = "env"
    const val CONTEXT_KEY: String = "context"
    const val VAR_KEY: String = "var"
    val ENTRY_QUALIFIER_KEYS: Set<String> = setOf(ENV_KEY, CONTEXT_KEY, VAR_KEY)

    /** Keys legal inside a `var:` block. */
    const val VAR_NAME_KEY: String = "name"
    const val VAR_SENSITIVE_KEY: String = "sensitive"
    val VAR_KEYS: Set<String> = setOf(VAR_NAME_KEY, VAR_SENSITIVE_KEY)

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
        if (isDirectChildOfProperties(keyValue)) return false
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

    /** The (parentType, propertyName) a child plugin mapping occupies inside a parent's `properties:`. */
    data class Slot(val parentType: String, val propertyName: String)

    /** A mapping that represents a plugin instance: has a `type:` (definition) or a `ref:` (link). */
    fun isPluginMapping(mapping: YAMLMapping): Boolean =
        mapping.getKeyValueByKey(TYPE_KEY) != null || REF_KEYS.any { mapping.getKeyValueByKey(it) != null }

    /** True if [keyValue] is a direct child of a `properties:` mapping (i.e. a plugin property). */
    fun isDirectChildOfProperties(keyValue: YAMLKeyValue): Boolean {
        val owner = keyValue.parentMapping ?: return false
        val ownerKv = owner.parent as? YAMLKeyValue ?: return false
        return ownerKv.keyText == PROPERTIES_KEY
    }

    /**
     * The `type:` of the plugin mapping that directly owns the given `properties:` key-value
     * (i.e. the enclosing plugin's type), or null.
     */
    fun enclosingPluginType(propertiesKv: YAMLKeyValue): String? {
        if (propertiesKv.keyText != PROPERTIES_KEY) return null
        val ownerMapping = propertiesKv.parentMapping ?: return null
        return ownerMapping.getKeyValueByKey(TYPE_KEY)?.valueText?.takeIf { it.isNotBlank() }
    }

    /**
     * The (parentType, propertyName) slot a child plugin mapping occupies, or null if it is a
     * top-level `spec:` item (no slot) or the structure is unrecognised.
     *
     * Collapses every container form (direct, ref, pluginList, pluginMap, plugin wrapper) by climbing
     * the PSI parent chain until the first [YAMLKeyValue] that is a direct child of a `properties:`
     * mapping; wrapper key-values (`pluginList`/`plugin`/`pluginMap`) and the `pluginMap` inner-key
     * level are naturally skipped because they fail the properties-direct test.
     */
    fun slotOf(childMapping: YAMLMapping): Slot? {
        val propertyKv = enclosingPropertyKeyValue(childMapping) ?: return null
        val propertiesKv = propertyKv.parentMapping?.parent as? YAMLKeyValue ?: return null
        val parentType = enclosingPluginType(propertiesKv) ?: return null
        return Slot(parentType, propertyKv.keyText)
    }

    private fun enclosingPropertyKeyValue(childMapping: YAMLMapping): YAMLKeyValue? {
        var node: PsiElement? = childMapping
        while (node != null) {
            if (node is YAMLKeyValue && node.keyText !in WRAPPER_KEYS && isDirectChildOfProperties(node)) {
                return node
            }
            node = node.parent
        }
        return null
    }

    /** True if [mapping] is the value-mapping of a `properties:` key, so its keys are property names. */
    fun isPropertiesMapping(mapping: YAMLMapping): Boolean =
        (mapping.parent as? YAMLKeyValue)?.keyText == PROPERTIES_KEY

    /**
     * A *value-entry*: a [YAMLMapping] that is a sequence item and carries exactly the value-entry shape
     * (a [VALUE_CARRIER_KEYS] key). This distinguishes the outer entries of a property value list
     * (`- value: …` / `- plugin: …` / `- pluginList: …`) from inner `pluginList`/`pluginMap` items, which
     * carry `type:`/`ref:` directly and have no value-carrier key.
     */
    fun isValueEntry(mapping: YAMLMapping): Boolean =
        mapping.parent is YAMLSequenceItem &&
            mapping.keyValues.any { it.keyText in VALUE_CARRIER_KEYS }

    /**
     * True if [mapping] sits in a position where plugin-definition/-link root keys
     * (`type`/`id`/`displayName`/`comment`/`parentRef`/`properties`, or `ref`) are expected, i.e.:
     *  - a slot value (the value-mapping of a key that is a direct child of `properties:`), or
     *  - a `spec:` sequence item, or
     *  - a wrapper item (inside `pluginList:`/`plugin:`, or a value-mapping under `pluginMap:`).
     *
     * Returns false for a `properties:` mapping, a `var:` mapping, and a value-entry mapping. Conservative:
     * when the surrounding structure is not recognised, returns false (suppress rather than misfire).
     */
    fun isPluginMappingContext(mapping: YAMLMapping): Boolean {
        if (isPropertiesMapping(mapping)) return false
        val parent = mapping.parent
        if (parent is YAMLKeyValue) {
            // Value-mapping of some key.
            when (parent.keyText) {
                VAR_KEY -> return false
                "plugin" -> return true // plugin wrapper inner mapping
                "pluginList", "pluginMap" -> return false // these hold sequences/inner keys, not a plugin directly
            }
            // pluginMap value: parent KV -> pluginMap value-mapping -> pluginMap KV.
            val grandParentKv = parent.parentMapping?.parent as? YAMLKeyValue
            if (grandParentKv?.keyText == "pluginMap") return true
            // slot value: parent KV is a direct child of properties:.
            return isDirectChildOfProperties(parent)
        }
        if (parent is YAMLSequenceItem) {
            if (isValueEntry(mapping)) return false
            val sequence = parent.parent
            val sequenceKv = sequence?.parent as? YAMLKeyValue ?: return false
            return sequenceKv.keyText == SPEC_KEY || sequenceKv.keyText in WRAPPER_KEYS
        }
        return false
    }

    /** The plugin `type:` that a `ref`/`parentRef` [id] resolves to within [model], or null. */
    fun refTargetType(id: String, model: IamConfigFileModel.Model): String? =
        model.definitions[id]?.firstOrNull()
            ?.parentMapping?.getKeyValueByKey(TYPE_KEY)?.valueText?.takeIf { it.isNotBlank() }
}
