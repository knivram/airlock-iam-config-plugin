package me.knivram.iamplugin.documentation

import me.knivram.iamplugin.catalog.AirlockPluginCatalog
import me.knivram.iamplugin.catalog.CatalogEntry
import me.knivram.iamplugin.catalog.CatalogProperty
import me.knivram.iamplugin.psi.IamConfigFileModel
import me.knivram.iamplugin.psi.IamConfigPsi
import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Quick Documentation / hover docs for Airlock IAM config YAML.
 *
 * Recognises three hover targets and renders the bundled catalog's render-ready (pre-sanitized)
 * docs for them:
 *  1. a `type:` VALUE          -> the plugin type's doc (title, type token, description, properties).
 *  2. a property KEY under a   -> that property's doc (kind, mandatory, assignable types, description).
 *     plugin's `properties:`
 *  3. a plugin `id:` value      -> the plugin instance's doc (type, displayName, type description).
 *     (a `ref:`/`parentRef:` value is covered automatically: PluginIdReference resolves it to the
 *      target `id:` YAMLKeyValue, on which the platform then calls generateDoc.)
 *
 * Every catalog miss (unknown/custom type, unknown property, non-definition id, non-IamConfig file)
 * yields null -> no popup, never an error.
 */
class AirlockDocumentationProvider : AbstractDocumentationProvider() {

    override fun getCustomDocumentationElement(
        editor: Editor,
        file: PsiFile,
        contextElement: PsiElement?,
        targetOffset: Int,
    ): PsiElement? {
        val ctx = contextElement ?: return null
        val yamlFile = file as? YAMLFile ?: return null
        if (!IamConfigPsi.isIamConfigFile(yamlFile)) return null

        val kv = PsiTreeUtil.getParentOfType(ctx, YAMLKeyValue::class.java, false) ?: return null
        val scalar = PsiTreeUtil.getParentOfType(ctx, YAMLScalar::class.java, false)

        // Target 1: caret on a plugin-definition `type:` VALUE (not a property literally named `type`).
        if (kv.keyText == IamConfigPsi.TYPE_KEY && !IamConfigPsi.isDirectChildOfProperties(kv) &&
            scalar != null && kv.value === scalar
        ) {
            return kv
        }

        // Target 3 (hovering the id itself): caret on a plugin definition `id:` VALUE.
        if (IamConfigPsi.isPluginDefinitionId(kv) && scalar != null && kv.value === scalar) {
            return kv
        }

        // Target 2: caret on a property KEY directly under a plugin's `properties:` mapping.
        if (IamConfigPsi.isDirectChildOfProperties(kv) && kv.keyText !in IamConfigPsi.WRAPPER_KEYS) {
            val keyElem = kv.key
            val onKey = keyElem != null &&
                targetOffset in keyElem.textRange.startOffset..keyElem.textRange.endOffset
            if (onKey) {
                val propertiesKv = kv.parentMapping?.parent as? YAMLKeyValue ?: return null
                val parentType = IamConfigPsi.enclosingPluginType(propertiesKv) ?: return null
                if (AirlockPluginCatalog.getInstance().property(parentType, kv.keyText) == null) return null
                return kv
            }
        }

        // Target 4: caret on a `ref:`/`parentRef:` VALUE -> document the plugin it points at. Resolve the
        // target definition HERE and return its `id:` key-value, so Quick Doc renders via the same robust
        // path as Target 3. (Relying on the platform to document the resolved reference is unreliable: a
        // UUID-shaped value gets claimed by the platform's built-in UUID support after edits, and the ref
        // doc falls back to the generic popup.) getCustomDocumentationElement is consulted before the
        // platform's reference/symbol target, so returning the definition here wins.
        if (scalar != null && IamConfigPsi.isReferenceValueScalar(scalar)) {
            val id = scalar.textValue
            if (id.isNotBlank()) {
                IamConfigFileModel.of(yamlFile).definitions[id]?.firstOrNull { it.isValid }?.let { return it }
            }
        }

        return null
    }

    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        val kv = element as? YAMLKeyValue ?: return null
        val file = kv.containingFile as? YAMLFile ?: return null
        if (!IamConfigPsi.isIamConfigFile(file)) return null

        val catalog = AirlockPluginCatalog.getInstance()

        // Plugin definition `id:` value (also the resolve target of ref:/parentRef:).
        if (IamConfigPsi.isPluginDefinitionId(kv)) {
            val mapping = kv.parentMapping ?: return null
            val info = IamConfigPsi.definitionInfo(mapping) ?: return null
            val entry = catalog.entry(info.type) ?: return null
            return renderInstanceDoc(info, entry)
        }

        // `type:` value (plugin-definition level only, not a property named `type`).
        if (kv.keyText == IamConfigPsi.TYPE_KEY && !IamConfigPsi.isDirectChildOfProperties(kv)) {
            val type = kv.valueText
            val entry = catalog.entry(type) ?: return null
            return renderTypeDoc(entry)
        }

        // Property key under a plugin's `properties:` mapping.
        if (IamConfigPsi.isDirectChildOfProperties(kv) && kv.keyText !in IamConfigPsi.WRAPPER_KEYS) {
            val propertiesKv = kv.parentMapping?.parent as? YAMLKeyValue ?: return null
            val parentType = IamConfigPsi.enclosingPluginType(propertiesKv) ?: return null
            val prop = catalog.property(parentType, kv.keyText) ?: return null
            return renderPropertyDoc(prop, parentType)
        }

        return null
    }

    // ---- renderers -----------------------------------------------------------------------------

    /** Target 1: `type:` value. */
    private fun renderTypeDoc(entry: CatalogEntry): String = buildString {
        append(DocumentationMarkup.DEFINITION_START)
        append(escape(entry.title ?: entry.type))
        append(DocumentationMarkup.DEFINITION_END)

        append(DocumentationMarkup.CONTENT_START)
        append(DocumentationMarkup.GRAYED_START)
        append(escape(entry.type))
        append(DocumentationMarkup.GRAYED_END)
        append(DocumentationMarkup.CONTENT_END)

        entry.description?.let {
            append(DocumentationMarkup.CONTENT_START)
            append(it)
            append(DocumentationMarkup.CONTENT_END)
        }

        if (entry.properties.isNotEmpty()) {
            append(DocumentationMarkup.SECTIONS_START)
            entry.properties.forEach { prop ->
                val value = buildString {
                    append(escape(prop.kind.name.lowercase()))
                    if (prop.mandatory) append(" &middot; mandatory")
                }
                section(prop.name, value)
            }
            append(DocumentationMarkup.SECTIONS_END)
        }
    }

    /** Target 2: property KEY. */
    private fun renderPropertyDoc(prop: CatalogProperty, parentType: String): String = buildString {
        append(DocumentationMarkup.DEFINITION_START)
        append(escape(prop.name))
        append(DocumentationMarkup.DEFINITION_END)

        append(DocumentationMarkup.CONTENT_START)
        append(DocumentationMarkup.GRAYED_START)
        append("in ").append(escape(parentType))
        append(DocumentationMarkup.GRAYED_END)
        append(DocumentationMarkup.CONTENT_END)

        append(DocumentationMarkup.SECTIONS_START)
        section("Kind", escape(prop.kind.name.lowercase()))
        section("Mandatory", if (prop.mandatory) "yes" else "no")
        if (prop.kind.isPluginValued && prop.assignable.isNotEmpty()) {
            val sorted = prop.assignable.sorted()
            val shown = sorted.take(MAX_ASSIGNABLE).joinToString(", ") { escape(it) }
            val value = if (sorted.size > MAX_ASSIGNABLE) "$shown, &hellip;" else shown
            section("Assignable types", value)
        }
        append(DocumentationMarkup.SECTIONS_END)

        prop.description?.let {
            append(DocumentationMarkup.CONTENT_START)
            append(it)
            append(DocumentationMarkup.CONTENT_END)
        }
    }

    /** Target 3: plugin `id:` value (or ref:/parentRef: resolving to it). */
    private fun renderInstanceDoc(info: IamConfigPsi.DefinitionInfo, entry: CatalogEntry): String = buildString {
        append(DocumentationMarkup.DEFINITION_START)
        append(escape(info.displayName ?: info.id))
        append(DocumentationMarkup.DEFINITION_END)

        append(DocumentationMarkup.CONTENT_START)
        append(DocumentationMarkup.GRAYED_START)
        append(escape(info.type))
        append(DocumentationMarkup.GRAYED_END)
        append(DocumentationMarkup.CONTENT_END)

        append(DocumentationMarkup.SECTIONS_START)
        section("Type", escape(info.type))
        info.displayName?.let { section("Display name", escape(it)) }
        section("Id", escape(info.id))
        append(DocumentationMarkup.SECTIONS_END)

        entry.description?.let {
            append(DocumentationMarkup.CONTENT_START)
            append(it)
            append(DocumentationMarkup.CONTENT_END)
        }
    }

    /** A section row: header cell + separator + value cell. There is no SECTION_HEADER_END in 2025.2. */
    private fun StringBuilder.section(header: String, valueHtml: String) {
        append(DocumentationMarkup.SECTION_HEADER_START)
        append(escape(header))
        append(DocumentationMarkup.SECTION_SEPARATOR)
        append("<p>")
        append(valueHtml)
        append(DocumentationMarkup.SECTION_END)
    }

    private fun escape(s: String): String = StringUtil.escapeXmlEntities(s)

    companion object {
        private const val MAX_ASSIGNABLE = 15
    }
}
