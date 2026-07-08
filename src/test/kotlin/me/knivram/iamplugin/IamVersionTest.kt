package me.knivram.iamplugin

import me.knivram.iamplugin.catalog.IamVersion
import junit.framework.TestCase

class IamVersionTest : TestCase() {

    fun testParsePlain() {
        assertEquals(IamVersion(8, 6), IamVersion.parse("8.6"))
    }

    fun testParseToleratesQuotesAndWhitespace() {
        assertEquals(IamVersion(8, 6), IamVersion.parse("'8.6'"))
        assertEquals(IamVersion(8, 6), IamVersion.parse("\"8.6\""))
        assertEquals(IamVersion(8, 6), IamVersion.parse("  8.6  "))
        assertEquals(IamVersion(8, 6), IamVersion.parse(" '8.6' "))
    }

    fun testParseDropsPatchComponents() {
        assertEquals(IamVersion(8, 6), IamVersion.parse("8.6.1"))
        assertEquals(IamVersion(8, 6), IamVersion.parse("8.6.1.2"))
    }

    fun testParseRejectsGarbage() {
        assertNull(IamVersion.parse(null))
        assertNull(IamVersion.parse(""))
        assertNull(IamVersion.parse("   "))
        assertNull(IamVersion.parse("latest"))
        assertNull(IamVersion.parse("8"))
        assertNull(IamVersion.parse("8."))
        assertNull(IamVersion.parse("v8.6"))
        assertNull(IamVersion.parse("8.6-beta"))
    }

    fun testOrderingIsNumericNotLexicographic() {
        assertTrue(IamVersion(8, 4) < IamVersion(8, 5))
        assertTrue(IamVersion(8, 5) < IamVersion(8, 6))
        // Lexicographically "8.10" < "8.9"; numerically it must be greater.
        assertTrue(IamVersion(8, 9) < IamVersion(8, 10))
        assertTrue(IamVersion(8, 6) < IamVersion(9, 0))
    }

    fun testToString() {
        assertEquals("8.6", IamVersion(8, 6).toString())
        assertEquals("8.10", IamVersion(8, 10).toString())
    }
}
