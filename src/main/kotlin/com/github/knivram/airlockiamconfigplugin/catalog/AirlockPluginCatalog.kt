package com.github.knivram.airlockiamconfigplugin.catalog

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/** One plugin type from the bundled Airlock IAM catalog. */
data class CatalogEntry(val type: String, val title: String?)

/**
 * The bundled, version-pinned Airlock IAM plugin-type catalog (IAM 8.6).
 *
 * Generated offline from the official IAM plugin documentation by `tools/generate_catalog.py`
 * into `airlock/iam-8.6-plugins.json`. Loaded once, lazily.
 */
@Service(Service.Level.APP)
class AirlockPluginCatalog {

    val entries: List<CatalogEntry> by lazy { load() }

    private fun load(): List<CatalogEntry> {
        val stream = javaClass.classLoader.getResourceAsStream(RESOURCE)
        if (stream == null) {
            thisLogger().warn("Airlock plugin catalog resource not found: $RESOURCE")
            return emptyList()
        }
        return try {
            InputStreamReader(stream, StandardCharsets.UTF_8).use { reader ->
                val listType = object : TypeToken<List<RawEntry>>() {}.type
                val raw: List<RawEntry> = Gson().fromJson(reader, listType) ?: emptyList()
                raw.mapNotNull { it.type?.takeIf(String::isNotBlank)?.let { type -> CatalogEntry(type, it.title) } }
            }
        } catch (e: Exception) {
            thisLogger().warn("Failed to read Airlock plugin catalog", e)
            emptyList()
        }
    }

    private data class RawEntry(val type: String?, val title: String?)

    companion object {
        private const val RESOURCE = "airlock/iam-8.6-plugins.json"

        fun getInstance(): AirlockPluginCatalog = service()
    }
}
