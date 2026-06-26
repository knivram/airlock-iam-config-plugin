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

    fun testModelHarvestsEnvNames() {
        val file = myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: ParentPlugin
              id: p-1
              properties:
                url:
                - value: a
                  env: intellij
                - value: b
                  env: tilt
                - value: c
            """.trimIndent(),
        ) as YAMLFile
        val model = IamConfigFileModel.of(file)
        assertContainsElements(model.envNames, "intellij", "tilt")
        assertSize(2, model.envNames.toList())
    }

    fun testPropertyLevelTypeAndIdNotADefinition() {
        // A `type:`+`id:` pair directly under a `properties:` mapping is a property-level id, not a
        // plugin definition; it must not be indexed (matches IamConfigPsi.isPluginDefinitionId).
        val file = myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: SomePlugin
              id: outer-1
              properties:
                type: NotAPlugin
                id: not-a-definition
            """.trimIndent(),
        ) as YAMLFile
        val model = IamConfigFileModel.of(file)
        assertFalse(
            "a type+id pair under properties: must not be a plugin definition",
            model.definitions.containsKey("not-a-definition"),
        )
        assertTrue("the real outer plugin id is still indexed", model.definitions.containsKey("outer-1"))
    }

    fun testEnvNameNotHarvestedFromScalarProperty() {
        // A scalar property literally named `env` (custom type) is not an environment qualifier and
        // must not pollute the harvested environment names; a real `env:` qualifier still contributes.
        val file = myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: ZzzCustomPlugin
              id: p-1
              properties:
                env: somevalue
                url:
                - value: x
                  env: prod
            """.trimIndent(),
        ) as YAMLFile
        val model = IamConfigFileModel.of(file)
        assertContainsElements(model.envNames, "prod")
        assertFalse(
            "a scalar property named env must not be treated as an environment name",
            model.envNames.contains("somevalue"),
        )
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
