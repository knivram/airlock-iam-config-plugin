package com.github.knivram.airlockiamconfigplugin

import com.github.knivram.airlockiamconfigplugin.psi.IamConfigPsi
import com.github.knivram.airlockiamconfigplugin.usage.IamConfigFindUsagesHandlerFactory
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar

class FindUsagesTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    fun testFindUsagesFindsRefAndParentRef() {
        // usages.yaml declares the id and references it twice (ref + parentRef) in the same file.
        val file = myFixture.configureByFile("usages.yaml") as YAMLFile
        val idKeyValue = pluginId(file, TARGET_ID)

        val usages = myFixture.findUsages(idKeyValue)

        assertEquals("ref + parentRef should both be reported as usages", 2, usages.size)
        // Composition: every usage is a ref/parentRef scalar valued TARGET_ID; the definition's own
        // id is never reported as a usage of itself.
        val keyTexts = HashSet<String>()
        for (usage in usages) {
            val scalar = usage.element as? YAMLScalar
            assertNotNull("usage should be a YAML scalar", scalar)
            assertTrue("usage must be a ref/parentRef value", IamConfigPsi.isReferenceValueScalar(scalar!!))
            assertEquals(TARGET_ID, scalar.textValue)
            (scalar.parent as? YAMLKeyValue)?.keyText?.let(keyTexts::add)
        }
        assertEquals(setOf("ref", "parentRef"), keyTexts)
    }

    fun testFindUsagesFindsRefInSameFile() {
        val file = myFixture.configureByFile("sameFileUsage.yaml") as YAMLFile
        val idKeyValue = pluginId(file, TARGET_ID)

        val usages = myFixture.findUsages(idKeyValue)

        assertEquals("the single same-file ref is the only usage", 1, usages.size)
        val scalar = usages.single().element as YAMLScalar
        assertTrue(IamConfigPsi.isReferenceValueScalar(scalar))
        assertEquals(TARGET_ID, scalar.textValue)
    }

    fun testHighlightUsagesFindsRefs() {
        // Highlight Usages in File goes through findReferencesToHighlight, not processElementUsages.
        val file = myFixture.configureByFile("usages.yaml") as YAMLFile
        val idKeyValue = pluginId(file, TARGET_ID)

        val handler = IamConfigFindUsagesHandlerFactory().createFindUsagesHandler(idKeyValue, true)
        assertNotNull("a find-usages handler must be created for a plugin id", handler)
        val references = handler!!.findReferencesToHighlight(idKeyValue, GlobalSearchScope.fileScope(file))

        assertEquals("both ref and parentRef should be highlightable", 2, references.size)
    }

    private fun pluginId(file: YAMLFile, id: String): YAMLKeyValue =
        PsiTreeUtil.collectElementsOfType(file, YAMLKeyValue::class.java)
            .first { IamConfigPsi.isPluginDefinitionId(it) && it.valueText == id }

    companion object {
        private const val TARGET_ID = "f0d2a309-8eb4-4574-a622-503d6e5f47be"
    }
}
