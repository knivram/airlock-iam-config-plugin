package com.github.knivram.airlockiamconfigplugin

import com.github.knivram.airlockiamconfigplugin.psi.IamConfigFileModel
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.yaml.psi.YAMLFile

class IamConfigFileModelTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    fun testModelCollectsOnlyPluginDefinitions() {
        val file = myFixture.configureByFile("definitions.yaml") as YAMLFile
        val model = IamConfigFileModel.of(file)

        val dbDefs = model.definitions["f0d2a309-8eb4-4574-a622-503d6e5f47be"]
        assertNotNull(dbDefs)
        assertSize(1, dbDefs!!)
        assertEquals("JdbcConnectionPool", dbDefs[0].parentMapping?.getKeyValueByKey("type")?.valueText)

        // A property-level id (register-mtan, under properties:) is not a plugin definition.
        assertFalse(model.definitions.containsKey("register-mtan"))
        assertContainsElements(model.types, "GlobalConfiguration", "JdbcConnectionPool", "StepId")
    }

    fun testModelIsEmptyForNonIamConfigFile() {
        val file = myFixture.configureByText(
            "plain.yaml",
            """
            type: SomethingElse
            spec:
            - ref: whatever
              id: x
            """.trimIndent(),
        ) as YAMLFile
        val model = IamConfigFileModel.of(file)
        assertEmpty(model.definitions.keys)
        assertEmpty(model.references.keys)
    }
}
