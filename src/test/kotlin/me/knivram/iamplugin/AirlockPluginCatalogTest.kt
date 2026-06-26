package me.knivram.iamplugin

import me.knivram.iamplugin.catalog.AirlockPluginCatalog
import me.knivram.iamplugin.catalog.PropertyKind
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class AirlockPluginCatalogTest : BasePlatformTestCase() {

    private val catalog get() = AirlockPluginCatalog.getInstance()

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
}
