package me.knivram.iamplugin

import me.knivram.iamplugin.catalog.CatalogResolution
import me.knivram.iamplugin.catalog.IamVersion
import me.knivram.iamplugin.catalog.VersionProvenance
import me.knivram.iamplugin.catalog.VersionedCatalog
import me.knivram.iamplugin.editor.BannerKind
import me.knivram.iamplugin.editor.VersionHint
import me.knivram.iamplugin.editor.bannerFor
import me.knivram.iamplugin.editor.isAutoDetect
import me.knivram.iamplugin.editor.versionHint
import junit.framework.TestCase

/**
 * The pure banner decision over [CatalogResolution]. [VersionedCatalog] loads lazily, so
 * constructing one here never touches the bundled resources.
 */
class IamVersionBannerTest : TestCase() {

    private fun resolution(raw: String?, provenance: VersionProvenance, resolved: String) =
        CatalogResolution(raw, IamVersion.parse(raw), provenance, VersionedCatalog(resolved))

    fun testExactMarkerMatchShowsNoBanner() {
        assertNull(bannerFor(resolution("8.6", VersionProvenance.MARKER, "8.6"), suppressNoVersion = false))
        // Patch suffix still resolves to an exact minor match.
        assertNull(bannerFor(resolution("8.6.1", VersionProvenance.MARKER, "8.6"), suppressNoVersion = false))
    }

    fun testUnsupportedMarkerWarns() {
        val spec = bannerFor(resolution("9.9", VersionProvenance.MARKER, "8.6"), suppressNoVersion = false)
        assertEquals(BannerKind.UNSUPPORTED_MARKER, spec!!.kind)
        assertEquals("9.9", spec.requestedRaw)
        assertEquals("8.6", spec.resolvedVersion)
    }

    fun testUnreadableMarkerWarnsAsUnsupported() {
        // Unparseable markers are the same user-facing case as unbundled ones.
        val spec = bannerFor(resolution("latest", VersionProvenance.MARKER, "8.6"), suppressNoVersion = false)
        assertEquals(BannerKind.UNSUPPORTED_MARKER, spec!!.kind)
        assertEquals("latest", spec.requestedRaw)
    }

    fun testNoMarkerShowsInfoBanner() {
        val spec = bannerFor(resolution(null, VersionProvenance.DEFAULT, "8.6"), suppressNoVersion = false)
        assertEquals(BannerKind.NO_MARKER, spec!!.kind)
        assertEquals("8.6", spec.resolvedVersion)
    }

    fun testNoMarkerBannerIsSuppressible() {
        assertNull(bannerFor(resolution(null, VersionProvenance.DEFAULT, "8.6"), suppressNoVersion = true))
    }

    fun testOverrideShowsSlimBanner() {
        val spec = bannerFor(resolution("8.5", VersionProvenance.OVERRIDE, "8.5"), suppressNoVersion = false)
        assertEquals(BannerKind.OVERRIDE_ACTIVE, spec!!.kind)
        assertEquals("8.5", spec.resolvedVersion)
    }

    fun testOverrideBannerIgnoresSuppressFlag() {
        // The banner is the only place an override is visible and clearable — never suppressed.
        val spec = bannerFor(resolution("8.5", VersionProvenance.OVERRIDE, "8.5"), suppressNoVersion = true)
        assertEquals(BannerKind.OVERRIDE_ACTIVE, spec!!.kind)
    }

    fun testWarningsIgnoreSuppressFlag() {
        assertNotNull(bannerFor(resolution("9.9", VersionProvenance.MARKER, "8.6"), suppressNoVersion = true))
        assertNotNull(bannerFor(resolution("latest", VersionProvenance.MARKER, "8.6"), suppressNoVersion = true))
    }

    fun testDropdownReadsAutoDetectUnlessOverridden() {
        // Markerless and unsupported-marker files are still auto-detecting → "Auto-detect".
        assertTrue(bannerFor(resolution(null, VersionProvenance.DEFAULT, "8.6"), false)!!.isAutoDetect)
        assertTrue(bannerFor(resolution("9.9", VersionProvenance.MARKER, "8.6"), false)!!.isAutoDetect)
        // A manual override names its version instead.
        assertFalse(bannerFor(resolution("8.5", VersionProvenance.OVERRIDE, "8.5"), false)!!.isAutoDetect)
    }

    fun testVersionHintNamesWhereAutoDetectLanded() {
        assertEquals(
            VersionHint.FALLBACK,
            bannerFor(resolution("9.9", VersionProvenance.MARKER, "8.6"), false)!!.versionHint,
        )
        assertEquals(
            VersionHint.DEFAULT,
            bannerFor(resolution(null, VersionProvenance.DEFAULT, "8.6"), false)!!.versionHint,
        )
        // A manual selection matches a bundled version exactly, so it needs no hint.
        assertEquals(
            VersionHint.NONE,
            bannerFor(resolution("8.5", VersionProvenance.OVERRIDE, "8.5"), false)!!.versionHint,
        )
    }
}
