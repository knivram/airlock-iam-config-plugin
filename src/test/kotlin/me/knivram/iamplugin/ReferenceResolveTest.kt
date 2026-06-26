package me.knivram.iamplugin

import me.knivram.iamplugin.psi.IamConfigPsi
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.yaml.psi.YAMLKeyValue

private const val DB_ID = "f0d2a309-8eb4-4574-a622-503d6e5f47be"

class ReferenceResolveTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    fun testRefResolvesWithinFile() {
        val reference = myFixture.getReferenceAtCaretPositionWithAssertion("refResolve.yaml")
        val resolved = reference.resolve()
        assertInstanceOf(resolved, YAMLKeyValue::class.java)
        val idKeyValue = resolved as YAMLKeyValue
        assertEquals("id", idKeyValue.keyText)
        assertEquals(DB_ID, idKeyValue.valueText)
        assertTrue(IamConfigPsi.isPluginDefinitionId(idKeyValue))
        assertEquals("JdbcConnectionPool", idKeyValue.parentMapping?.getKeyValueByKey("type")?.valueText)
    }

    fun testQuotedRefResolves() {
        // A single-quoted ref must still resolve: quote stripping is handled by scalar.textValue.
        val reference = myFixture.getReferenceAtCaretPositionWithAssertion("quotedRefResolve.yaml")
        val resolved = reference.resolve() as? YAMLKeyValue
        assertNotNull("a quoted ref value must resolve to a plugin id", resolved)
        assertEquals("id", resolved!!.keyText)
        assertEquals(DB_ID, resolved.valueText)
    }

    fun testParentRefResolves() {
        val reference = myFixture.getReferenceAtCaretPositionWithAssertion("parentRefResolve.yaml")
        val resolved = reference.resolve() as? YAMLKeyValue
        assertNotNull("parentRef should resolve to a plugin id", resolved)
        assertEquals(DB_ID, resolved!!.valueText)
    }

    fun testRefToPropertyLevelIdDoesNotResolve() {
        // "register-mtan" is a property-level id (under properties:), not a plugin id sibling of type:.
        val reference = myFixture.getReferenceAtCaretPositionWithAssertion("refToPropertyId.yaml")
        assertNull("a property-level id must not be a reference target", reference.resolve())
    }

    fun testNoReferenceInNonIamConfigFile() {
        val reference = myFixture.getReferenceAtCaretPosition("notIamConfig.yaml")
        assertNull("references are only contributed in IamConfig files", reference)
    }
}
