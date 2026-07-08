package me.knivram.iamplugin.catalog

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import me.knivram.iamplugin.psi.IamConfigPsi
import me.knivram.iamplugin.settings.IamVersionOverrides
import org.jetbrains.yaml.psi.YAMLFile
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
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

/** How a file's IAM version was determined. */
enum class VersionProvenance {
    /** Explicit per-file selection made in the editor banner (stored IDE-side, per developer). */
    OVERRIDE,

    /** The file's own `metadata.iamVersion` marker. */
    MARKER,

    /** Neither override nor marker present: the manifest's default version. */
    DEFAULT,
}

/**
 * The outcome of resolving a file's IAM version to a bundled catalog: which version was asked
 * for (raw and parsed), where the request came from, and the catalog that answers it. Kept
 * provenance-rich so UI (the editor banner) can explain fallbacks without re-deriving them.
 */
data class CatalogResolution(
    /** The raw override/marker text, or null when the file carries neither. */
    val requestedRaw: String?,
    /** Parsed form of [requestedRaw]; null when absent or unparseable. */
    val requested: IamVersion?,
    val provenance: VersionProvenance,
    val catalog: VersionedCatalog,
) {
    /** True when the resolved catalog is exactly the requested version. */
    val exactMatch: Boolean
        get() = requested != null && requested == IamVersion.parse(catalog.version)
}

/**
 * The bundled plugin-type catalog for ONE IAM version, generated offline from the official IAM
 * plugin documentation by `tools/generate_catalog.py` into `airlock/iam-<version>-plugins.json`
 * (gzipped to `…json.gz` when large). Loaded once, lazily, on first query.
 *
 * Obtain instances via [AirlockPluginCatalog] — this data layer is deliberately psi-free; the
 * psi/settings-aware version resolution lives on [AirlockPluginCatalog]'s companion.
 */
class VersionedCatalog internal constructor(val version: String) {

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

    /**
     * The plugin types configured ONCE in the global config and auto-linked by IAM wherever a consuming
     * plugin declares a Mandatory Plugin-Link to that type but omits it.
     *
     * The global config is a two-level structure: `GlobalConfiguration` ("Main Settings") links a handful
     * of *settings containers* — `GlobalAuthenticationSettings`, `GlobalPersisterSettings` ("Data sources"),
     * `PasswordSettings`, the state repositories, `CorrelationIdSettings` — and the auto-linkable defaults
     * are the plugin types occupying the single-Plugin-Link slots of those containers (one level below
     * each). So this is the union of the `assignable` lists of the Plugin-Link properties of
     * `GlobalConfiguration` and of every type it links directly.
     *
     * Stopping at this depth is deliberate: it captures e.g. the `GlobalPersisterSettings` data sources
     * (`DatabaseUserStoreProvider`, `PersistentAcceptedSsoTicketRepository`, `DefaultTokenDataProvider`, …)
     * while keeping deeper infrastructure leaves out — a `JdbcConnectionPool` that is merely some default's
     * `sqlDataSource` must NOT be mistaken for an auto-linkable global default. An absent entry contributes
     * nothing (the safe fallback, meaning "no suppression").
     */
    val globalDefaultTypes: Set<String> by lazy {
        val containers = buildSet {
            add(GLOBAL_CONFIG_TYPE)
            addAll(linkAssignables(GLOBAL_CONFIG_TYPE))
        }
        containers.flatMap(::linkAssignables).toSet()
    }

    /** Types assignable into the single-Plugin-Link slots of [type] (empty for unknown/leaf types). */
    private fun linkAssignables(type: String): List<String> =
        propertiesOf(type).filter { it.kind == PropertyKind.PLUGIN_LINK }.flatMap { it.assignable }

    private fun load(): List<CatalogEntry> {
        val resource = "airlock/iam-$version-plugins.json"
        val raw = openCatalogStream(javaClass.classLoader, resource)
        if (raw == null) {
            thisLogger().warn("Airlock plugin catalog resource not found: $resource(.gz)")
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
            thisLogger().warn("Failed to read Airlock plugin catalog for IAM $version", e)
            emptyList()
        }
    }

    private data class RawStream(val input: InputStream, val gzipped: Boolean)

    /** Prefer the gzipped resource; fall back to the plain JSON for either packaging. */
    private fun openCatalogStream(cl: ClassLoader, resource: String): RawStream? =
        cl.getResourceAsStream("$resource.gz")?.let { RawStream(it, gzipped = true) }
            ?: cl.getResourceAsStream(resource)?.let { RawStream(it, gzipped = false) }

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
        /**
         * Root of the global-config settings tree. Kept as a literal here (rather than reusing the psi
         * module's `IamConfigPsi.GLOBAL_CONFIG_TYPE`) so the catalog data layer stays free of a psi
         * dependency.
         */
        private const val GLOBAL_CONFIG_TYPE = "GlobalConfiguration"
    }
}

/**
 * Registry of the bundled, version-keyed Airlock IAM plugin-type catalogs.
 *
 * The bundled set is enumerated by the `airlock/catalog-index.json` manifest (maintained by
 * `tools/generate_catalog.py`), which also pins the [defaultVersion] used when a file requests
 * nothing. Catalogs are handed out as [VersionedCatalog] objects, cached per version, each
 * parsing its ~4 MB JSON lazily — only versions actually resolved pay that cost.
 *
 * Per-file resolution (the [resolve]/[forFile] facade) is: banner override > the file's
 * `metadata.iamVersion` marker > [defaultVersion], with nearest-match fallback for versions
 * that are not bundled (largest bundled ≤ requested, else the oldest bundled — an older
 * catalog under-reports rather than demanding properties the user's IAM lacks).
 */
@Service(Service.Level.APP)
class AirlockPluginCatalog {

    private data class Manifest(val default: String?, val versions: List<String>?)

    private val manifest: Manifest by lazy { loadManifest() }

    /** The bundled versions, ascending; empty when the manifest is missing/corrupt (degraded mode). */
    val bundledVersions: List<String> by lazy {
        manifest.versions.orEmpty()
            .filter { IamVersion.parse(it) != null }
            .sortedBy { IamVersion.parse(it) }
    }

    /** The version used when a file requests nothing; the newest bundled if the pin is invalid. */
    val defaultVersion: String by lazy {
        val pinned = manifest.default
        when {
            pinned != null && pinned in bundledVersions -> pinned
            bundledVersions.isNotEmpty() -> bundledVersions.last().also {
                thisLogger().warn("catalog-index.json default '$pinned' is not bundled; using newest ($it)")
            }
            else -> pinned.orEmpty()
        }
    }

    private val cache = ConcurrentHashMap<String, VersionedCatalog>()

    /**
     * The catalog for [requested] (a raw version string, e.g. a `metadata.iamVersion` value):
     * exact bundled match, else largest bundled ≤ requested, else the oldest bundled;
     * null/unparseable requests get the [defaultVersion] catalog.
     */
    fun catalog(requested: String?): VersionedCatalog {
        val req = IamVersion.parse(requested) ?: return catalogFor(defaultVersion)
        if (bundledVersions.isEmpty()) return catalogFor(defaultVersion)
        val exact = bundledVersions.firstOrNull { IamVersion.parse(it) == req }
        if (exact != null) return catalogFor(exact)
        val below = bundledVersions.lastOrNull { IamVersion.parse(it)!! <= req }
        return catalogFor(below ?: bundledVersions.first())
    }

    private fun catalogFor(version: String): VersionedCatalog =
        cache.computeIfAbsent(version, ::VersionedCatalog)

    private fun loadManifest(): Manifest {
        val stream = javaClass.classLoader.getResourceAsStream(MANIFEST_RESOURCE)
        if (stream == null) {
            thisLogger().warn("Airlock catalog manifest resource not found: $MANIFEST_RESOURCE")
            return Manifest(null, null)
        }
        return try {
            InputStreamReader(stream, StandardCharsets.UTF_8).use { reader ->
                Gson().fromJson(reader, Manifest::class.java) ?: Manifest(null, null)
            }
        } catch (e: Exception) {
            thisLogger().warn("Failed to read Airlock catalog manifest", e)
            Manifest(null, null)
        }
    }

    companion object {
        private const val MANIFEST_RESOURCE = "airlock/catalog-index.json"

        fun getInstance(): AirlockPluginCatalog = service()

        /**
         * Resolve [file]'s IAM version to a bundled catalog with full provenance:
         * banner override > `metadata.iamVersion` marker > manifest default.
         */
        fun resolve(file: YAMLFile): CatalogResolution {
            val registry = getInstance()
            val override = IamVersionOverrides.getInstance(file.project).versionFor(file)
            if (override != null) {
                return CatalogResolution(
                    override, IamVersion.parse(override), VersionProvenance.OVERRIDE, registry.catalog(override),
                )
            }
            val marker = IamConfigPsi.declaredIamVersion(file)
            if (marker != null) {
                return CatalogResolution(
                    marker, IamVersion.parse(marker), VersionProvenance.MARKER, registry.catalog(marker),
                )
            }
            return CatalogResolution(null, null, VersionProvenance.DEFAULT, registry.catalog(null))
        }

        /** The catalog answering for [file] — the one-line seam every feature call site uses. */
        fun forFile(file: YAMLFile): VersionedCatalog = resolve(file).catalog
    }
}
