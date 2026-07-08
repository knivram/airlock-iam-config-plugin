package me.knivram.iamplugin

import me.knivram.iamplugin.catalog.AirlockPluginCatalog
import me.knivram.iamplugin.catalog.IamVersion
import me.knivram.iamplugin.catalog.PropertyKind
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class AirlockPluginCatalogTest : BasePlatformTestCase() {

    private val registry get() = AirlockPluginCatalog.getInstance()

    // The 8.6-specific assertions below are deliberately PINNED to the 8.6 catalog so they keep
    // testing exactly what they test today even after the manifest default moves on.
    private val catalog get() = registry.catalog("8.6")

    fun testGzipResourceLoadsFullCatalog() {
        // The bundled resource is gzipped; loading it must yield the full IAM 8.6 type set.
        assertEquals(1587, catalog.entries.size)
    }

    fun testKnownAndUnknownTypes() {
        assertTrue(catalog.isKnownType("JdbcConnectionPool"))
        assertTrue(catalog.isKnownType("SimplePasswordPolicy"))
        assertFalse(catalog.isKnownType("ZzzDefinitelyNotARealType"))
        assertNull(catalog.entry("ZzzDefinitelyNotARealType"))
        assertEmpty(catalog.propertiesOf("ZzzDefinitelyNotARealType"))
    }

    fun testPropertiesAndKinds() {
        val props = catalog.propertyNames("JdbcConnectionPool")
        assertContainsElements(props, "url", "maximumPoolSize", "driverClass")

        val url = catalog.property("JdbcConnectionPool", "url")
        assertNotNull(url)
        assertEquals(PropertyKind.STRING, url!!.kind)
        assertTrue("url is documented mandatory", url.mandatory)

        val poolSize = catalog.property("JdbcConnectionPool", "maximumPoolSize")
        assertEquals(PropertyKind.INTEGER, poolSize!!.kind)
        assertFalse(poolSize.mandatory)
    }

    fun testAssignableForPluginLinkSlot() {
        val policy = catalog.property("PasswordSettings", "passwordPolicy")
        assertNotNull(policy)
        assertEquals(PropertyKind.PLUGIN_LINK, policy!!.kind)
        assertTrue(policy.kind.isPluginValued)

        val assignable = catalog.assignableFor("PasswordSettings", "passwordPolicy")
        // Resolved to type TOKENs (not FQCNs).
        assertContainsElements(assignable, "SimplePasswordPolicy", "NullPasswordPolicy")
        assertFalse(
            "an unrelated type must not be assignable to passwordPolicy",
            assignable.contains("JdbcConnectionPool"),
        )
        assignable.forEach { assertFalse("token, not FQCN: $it", it.contains(".")) }
    }

    fun testMandatoryPluginLinkSlotExists() {
        // passwordService is a mandatory Plugin-Link; used by the MissingMandatory inspection test.
        val svc = catalog.property("PasswordSettings", "passwordService")
        assertNotNull(svc)
        assertTrue(svc!!.mandatory)
    }

    fun testGlobalDefaultTypes() {
        // The global-default types IAM auto-links into consumers, derived from the two-level settings tree.
        val globalDefaults = catalog.globalDefaultTypes
        assertFalse("the real catalog must expose global-default types", globalDefaults.isEmpty())
        // Level-1 containers' settings (GlobalAuthenticationSettings children).
        assertContainsElements(globalDefaults, "Airlock2FASettings", "FidoSettings", "MtanSettings")
        // Level-2 must also reach the GlobalPersisterSettings ("Data sources") defaults.
        assertContainsElements(
            globalDefaults,
            "DatabaseUserStoreProvider",
            "PersistentAcceptedSsoTicketRepository",
            "DefaultTokenDataProvider",
            "UserTrailLogRepository",
        )
        // ...but must stop before deep infrastructure leaves: a JdbcConnectionPool that is merely some
        // default's sqlDataSource is NOT an auto-linkable global default.
        assertFalse(
            "deep infrastructure leaves must not be treated as global defaults",
            globalDefaults.contains("JdbcConnectionPool"),
        )
    }

    // ---- multi-version registry ------------------------------------------------------------------

    // Invariants, not exact-list equality: adding/dropping a bundled version must not break these.
    fun testManifestInvariants() {
        val versions = registry.bundledVersions
        assertFalse("at least one catalog must be bundled", versions.isEmpty())
        assertTrue(
            "default ${registry.defaultVersion} must be a bundled version $versions",
            registry.defaultVersion in versions,
        )
        val parsed = versions.map { assertNotNull(IamVersion.parse(it)); IamVersion.parse(it)!! }
        assertEquals("bundled versions must be strictly ascending", parsed.sorted().distinct(), parsed)
    }

    fun testEveryBundledCatalogIsRealAndDistinct() {
        val sizes = registry.bundledVersions.map { version ->
            val c = registry.catalog(version)
            assertEquals(version, c.version)
            assertTrue("catalog $version suspiciously small: ${c.entries.size} types", c.entries.size >= 1400)
            assertTrue(c.isKnownType("JdbcConnectionPool"))
            assertTrue(c.isKnownType("PasswordSettings"))
            assertFalse("catalog $version must derive global defaults", c.globalDefaultTypes.isEmpty())
            c.entries.size
        }
        if (sizes.size > 1) {
            // A copied .gz shipped under two version names would slip through everything above.
            assertTrue("bundled catalogs must differ in content", sizes.distinct().size > 1)
        }
    }

    fun testVersionFallbackMatrix() {
        val versions = registry.bundledVersions
        // Null/garbage/unparseable -> the default catalog (same cached instance).
        assertSame(registry.catalog(registry.defaultVersion), registry.catalog(null))
        assertSame(registry.catalog(registry.defaultVersion), registry.catalog("not-a-version"))
        // Exact match for every bundled version.
        for (version in versions) assertEquals(version, registry.catalog(version).version)
        // Patch components are dropped.
        assertEquals("8.6", registry.catalog("8.6.1").version)
        // Below the oldest bundled -> oldest; above the newest -> newest (largest bundled <= requested).
        assertEquals(versions.first(), registry.catalog("1.0").version)
        assertEquals(versions.last(), registry.catalog("99.9").version)
    }
}
