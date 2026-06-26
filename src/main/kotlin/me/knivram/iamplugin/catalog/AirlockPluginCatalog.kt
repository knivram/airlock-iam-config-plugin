package me.knivram.iamplugin.catalog

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream

/** The documented value-kind of a plugin property (the KIND `property-tag` in the IAM docs). */
enum class PropertyKind {
    STRING, BOOLEAN, INTEGER, LONG, DOUBLE, ENUM, REGEX, MULTILINE_TEXT, FILE_PATH,
    STRING_LIST, REGEX_LIST, PLUGIN_LINK, PLUGIN_LIST, PLUGIN_MAP, UNKNOWN;

    /** True for the three kinds whose value is itself a plugin (and that carry `assignableTypes`). */
    val isPluginValued: Boolean
        get() = this == PLUGIN_LINK || this == PLUGIN_LIST || this == PLUGIN_MAP

    companion object {
        fun from(raw: String?): PropertyKind = when (raw) {
            "String" -> STRING
            "Boolean" -> BOOLEAN
            "Integer" -> INTEGER
            "Long" -> LONG
            "Double" -> DOUBLE
            "Enum" -> ENUM
            "RegEx" -> REGEX
            "Multi-line-text" -> MULTILINE_TEXT
            "File/Path" -> FILE_PATH
            "String-List" -> STRING_LIST
            "RegEx-List" -> REGEX_LIST
            "Plugin-Link" -> PLUGIN_LINK
            "Plugin-List" -> PLUGIN_LIST
            "Plugin-Map" -> PLUGIN_MAP
            else -> UNKNOWN
        }
    }
}

/** One declared property of a plugin type. */
data class CatalogProperty(
    val name: String,
    val kind: PropertyKind,
    val mandatory: Boolean,
    val sensitive: Boolean,
    /** Type TOKENs assignable into this slot; empty unless the kind is plugin-valued. */
    val assignable: List<String>,
    /** Render-ready sanitized HTML description from the IAM docs; null when absent. */
    val description: String? = null,
)

/** One plugin type from the bundled Airlock IAM catalog. */
data class CatalogEntry(
    val type: String,
    val title: String?,
    val properties: List<CatalogProperty> = emptyList(),
    /** Render-ready sanitized HTML description from the IAM docs; null when absent. */
    val description: String? = null,
) {
    val propertiesByName: Map<String, CatalogProperty> by lazy { properties.associateBy { it.name } }
}

/**
 * The bundled, version-pinned Airlock IAM plugin-type catalog (IAM 8.6).
 *
 * Generated offline from the official IAM plugin documentation by `tools/generate_catalog.py`
 * into `airlock/iam-8.6-plugins.json` (gzipped to `…json.gz` when large). Loaded once, lazily.
 */
@Service(Service.Level.APP)
class AirlockPluginCatalog {

    val entries: List<CatalogEntry> by lazy { load() }

    /** Per-type index; the generator dedupes by type so this is unambiguous. */
    private val byType: Map<String, CatalogEntry> by lazy { entries.associateBy { it.type } }

    fun entry(type: String): CatalogEntry? = byType[type]

    fun isKnownType(type: String): Boolean = byType.containsKey(type)

    fun propertiesOf(type: String): List<CatalogProperty> = byType[type]?.properties ?: emptyList()

    fun propertyNames(type: String): Set<String> = byType[type]?.propertiesByName?.keys ?: emptySet()

    fun property(type: String, name: String): CatalogProperty? = byType[type]?.propertiesByName?.get(name)

    /** Type TOKENs assignable into (type, propertyName), or empty if none / unknown. */
    fun assignableFor(type: String, propertyName: String): List<String> =
        property(type, propertyName)?.assignable ?: emptyList()

    private fun load(): List<CatalogEntry> {
        val raw = openCatalogStream(javaClass.classLoader)
        if (raw == null) {
            thisLogger().warn("Airlock plugin catalog resource not found: $RESOURCE(.gz)")
            return emptyList()
        }
        return try {
            // Wrap gzip inside the try so a corrupt/truncated .gz header degrades to emptyList()
            // instead of throwing out of the lazy initializer on every catalog access.
            val stream = if (raw.gzipped) GZIPInputStream(raw.input) else raw.input
            InputStreamReader(stream, StandardCharsets.UTF_8).use { reader ->
                val listType = object : TypeToken<List<RawEntry>>() {}.type
                val rawEntries: List<RawEntry> = Gson().fromJson(reader, listType) ?: emptyList()
                rawEntries.mapNotNull { it.toEntry() }
            }
        } catch (e: Exception) {
            thisLogger().warn("Failed to read Airlock plugin catalog", e)
            emptyList()
        }
    }

    private data class RawStream(val input: InputStream, val gzipped: Boolean)

    /** Prefer the gzipped resource; fall back to the plain JSON for either packaging. */
    private fun openCatalogStream(cl: ClassLoader): RawStream? =
        cl.getResourceAsStream("$RESOURCE.gz")?.let { RawStream(it, gzipped = true) }
            ?: cl.getResourceAsStream(RESOURCE)?.let { RawStream(it, gzipped = false) }

    private data class RawEntry(
        val type: String?,
        val title: String?,
        val properties: List<RawProperty>?,
        val description: String?,
    ) {
        fun toEntry(): CatalogEntry? {
            val t = type?.takeIf(String::isNotBlank) ?: return null
            val props = properties.orEmpty().mapNotNull { it.toProperty() }
            return CatalogEntry(t, title, props, description = description?.takeIf(String::isNotBlank))
        }
    }

    private data class RawProperty(
        val key: String?,
        val kind: String?,
        val mandatory: Boolean = false,
        val sensitive: Boolean = false,
        val assignableTypes: List<String>?,
        val description: String?,
    ) {
        fun toProperty(): CatalogProperty? {
            val n = key?.takeIf(String::isNotBlank) ?: return null
            return CatalogProperty(
                name = n,
                kind = PropertyKind.from(kind),
                mandatory = mandatory,
                sensitive = sensitive,
                assignable = assignableTypes.orEmpty(),
                description = description?.takeIf(String::isNotBlank),
            )
        }
    }

    companion object {
        private const val RESOURCE = "airlock/iam-8.6-plugins.json"

        fun getInstance(): AirlockPluginCatalog = service()
    }
}
