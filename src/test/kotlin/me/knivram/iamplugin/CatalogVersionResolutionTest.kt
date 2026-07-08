package me.knivram.iamplugin

import me.knivram.iamplugin.catalog.AirlockPluginCatalog
import me.knivram.iamplugin.catalog.VersionProvenance
import me.knivram.iamplugin.settings.IamVersionOverrides
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.yaml.psi.YAMLFile

/**
 * Per-file IAM version resolution: banner override > `metadata.iamVersion` marker > manifest
 * default, with nearest-match fallback for unbundled versions. Expectations are expressed
 * relative to the manifest (defaultVersion / first / last bundled) so version rotations don't
 * require edits here.
 */
class CatalogVersionResolutionTest : BasePlatformTestCase() {

    private val registry get() = AirlockPluginCatalog.getInstance()

    override fun tearDown() {
        try {
            // Light fixtures reuse the Project across test classes: leak no override/suppress state.
            val overrides = IamVersionOverrides.getInstance(project)
            overrides.state.overrides.clear()
            overrides.suppressNoVersionBanner = false
        } finally {
            super.tearDown()
        }
    }

    private fun configWithMetadata(metadataLine: String): YAMLFile =
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            $metadataLine
            spec: []
            """.trimIndent(),
        ) as YAMLFile

    fun testExactMarkerResolvesToThatCatalog() {
        val file = configWithMetadata("metadata: {iamVersion: '8.5'}")
        val resolution = AirlockPluginCatalog.resolve(file)
        assertEquals("8.5", resolution.catalog.version)
        assertEquals(VersionProvenance.MARKER, resolution.provenance)
        assertTrue(resolution.exactMatch)
        assertSame(registry.catalog("8.5"), AirlockPluginCatalog.forFile(file))
    }

    fun testQuotingAndPatchSuffixAreTolerated() {
        val file = configWithMetadata("metadata: {iamVersion: '8.6.1'}")
        val resolution = AirlockPluginCatalog.resolve(file)
        assertEquals("8.6", resolution.catalog.version)
        assertEquals("8.6.1", resolution.requestedRaw)
    }

    fun testEmptyMetadataFallsBackToDefault() {
        val file = configWithMetadata("metadata: {}")
        val resolution = AirlockPluginCatalog.resolve(file)
        assertEquals(VersionProvenance.DEFAULT, resolution.provenance)
        assertNull(resolution.requestedRaw)
        assertSame(registry.catalog(registry.defaultVersion), resolution.catalog)
    }

    fun testMissingMetadataFallsBackToDefault() {
        val file = myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            spec: []
            """.trimIndent(),
        ) as YAMLFile
        val resolution = AirlockPluginCatalog.resolve(file)
        assertEquals(VersionProvenance.DEFAULT, resolution.provenance)
        assertSame(registry.catalog(registry.defaultVersion), resolution.catalog)
    }

    fun testFutureVersionFallsBackToNewestBundled() {
        val file = configWithMetadata("metadata: {iamVersion: '9.9'}")
        val resolution = AirlockPluginCatalog.resolve(file)
        assertEquals(registry.bundledVersions.last(), resolution.catalog.version)
        assertEquals(VersionProvenance.MARKER, resolution.provenance)
        assertFalse(resolution.exactMatch)
        assertEquals("9.9", resolution.requestedRaw)
    }

    fun testAncientVersionFallsBackToOldestBundled() {
        val file = configWithMetadata("metadata: {iamVersion: '8.0'}")
        val resolution = AirlockPluginCatalog.resolve(file)
        assertEquals(registry.bundledVersions.first(), resolution.catalog.version)
        assertFalse(resolution.exactMatch)
    }

    fun testMalformedMarkerFallsBackToDefault() {
        val file = configWithMetadata("metadata: {iamVersion: latest}")
        val resolution = AirlockPluginCatalog.resolve(file)
        assertEquals(VersionProvenance.MARKER, resolution.provenance)
        assertEquals("latest", resolution.requestedRaw)
        assertNull(resolution.requested)
        assertSame(registry.catalog(registry.defaultVersion), resolution.catalog)
    }

    fun testOverrideBeatsMarker() {
        val file = configWithMetadata("metadata: {iamVersion: '8.6'}")
        IamVersionOverrides.getInstance(project).set(file.virtualFile, "8.5")
        val resolution = AirlockPluginCatalog.resolve(file)
        assertEquals(VersionProvenance.OVERRIDE, resolution.provenance)
        assertEquals("8.5", resolution.requestedRaw)
        assertSame(registry.catalog("8.5"), AirlockPluginCatalog.forFile(file))
    }

    fun testClearingOverrideRestoresMarkerResolution() {
        val file = configWithMetadata("metadata: {iamVersion: '8.6'}")
        val overrides = IamVersionOverrides.getInstance(project)
        overrides.set(file.virtualFile, "8.5")
        overrides.set(file.virtualFile, null)
        val resolution = AirlockPluginCatalog.resolve(file)
        assertEquals(VersionProvenance.MARKER, resolution.provenance)
        assertEquals("8.6", resolution.catalog.version)
    }
}
