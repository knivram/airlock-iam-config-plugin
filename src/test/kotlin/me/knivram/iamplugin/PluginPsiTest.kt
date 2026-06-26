package me.knivram.iamplugin

import me.knivram.iamplugin.psi.IamConfigPsi
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping

/**
 * Structural helpers for value-entry / plugin-mapping / properties-mapping recognition. These are catalog-free
 * and purely PSI-shape driven, so the tests use synthetic types and cover every container/value form from
 * example-config.yaml.
 */
class PluginPsiTest : BasePlatformTestCase() {

    private fun configure(text: String): YAMLFile = myFixture.configureByText("config.yaml", text) as YAMLFile

    /** The mapping carrying `key: value`, returned as its enclosing [YAMLMapping]. */
    private fun mappingWith(file: YAMLFile, key: String, value: String): YAMLMapping {
        val kv = PsiTreeUtil.findChildrenOfType(file, YAMLKeyValue::class.java)
            .first { it.keyText == key && it.valueText == value }
        return kv.parentMapping ?: error("no parent mapping for $key: $value")
    }

    /** The value-mapping of the (first) key named [key]. */
    private fun valueMappingOf(file: YAMLFile, key: String): YAMLMapping {
        val kv = PsiTreeUtil.findChildrenOfType(file, YAMLKeyValue::class.java).first { it.keyText == key }
        return kv.value as? YAMLMapping ?: error("$key has no value mapping")
    }

    // ---- isPropertiesMapping ------------------------------------------------------------------

    fun testIsPropertiesMappingTrueForPropertiesValue() {
        val file = configure(
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: ParentPlugin
              id: p-1
              properties:
                driverClass: org.postgresql.Driver
            """.trimIndent(),
        )
        val propsMapping = valueMappingOf(file, "properties")
        assertTrue(IamConfigPsi.isPropertiesMapping(propsMapping))
        assertFalse(IamConfigPsi.isPluginMappingContext(propsMapping))
    }

    fun testIsPropertiesMappingFalseForSlotValuePluginMapping() {
        val file = configure(
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: ParentPlugin
              id: p-1
              properties:
                sqlDataSource:
                  type: JdbcConnectionPool
            """.trimIndent(),
        )
        val child = mappingWith(file, "type", "JdbcConnectionPool")
        assertFalse(IamConfigPsi.isPropertiesMapping(child))
    }

    // ---- isPluginMappingContext ---------------------------------------------------------------

    fun testPluginMappingContextSpecItem() {
        val file = configure(
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: TopLevelPlugin
              id: p-1
            """.trimIndent(),
        )
        assertTrue(IamConfigPsi.isPluginMappingContext(mappingWith(file, "type", "TopLevelPlugin")))
    }

    fun testPluginMappingContextSlotValue() {
        val file = configure(
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: ParentPlugin
              id: p-1
              properties:
                sqlDataSource:
                  type: JdbcConnectionPool
            """.trimIndent(),
        )
        assertTrue(IamConfigPsi.isPluginMappingContext(mappingWith(file, "type", "JdbcConnectionPool")))
    }

    fun testPluginMappingContextPluginListItem() {
        val file = configure(
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: ParentPlugin
              id: p-1
              properties:
                steps:
                - pluginList:
                  - type: SomeStep
            """.trimIndent(),
        )
        assertTrue(IamConfigPsi.isPluginMappingContext(mappingWith(file, "type", "SomeStep")))
    }

    fun testPluginMappingContextPluginWrapperInner() {
        val file = configure(
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: ParentPlugin
              id: p-1
              properties:
                smsGateway:
                - plugin:
                    type: GenericHttpSmsGateway
            """.trimIndent(),
        )
        assertTrue(IamConfigPsi.isPluginMappingContext(mappingWith(file, "type", "GenericHttpSmsGateway")))
    }

    fun testPluginMappingContextPluginMapValue() {
        val file = configure(
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: ParentPlugin
              id: p-1
              properties:
                onFailureGotos:
                - pluginMap:
                    SOME_KEY:
                      type: StepId
            """.trimIndent(),
        )
        assertTrue(IamConfigPsi.isPluginMappingContext(mappingWith(file, "type", "StepId")))
    }

    fun testPluginMappingContextFalseForPropertiesMapping() {
        val file = configure(
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: ParentPlugin
              id: p-1
              properties:
                driverClass: org.postgresql.Driver
            """.trimIndent(),
        )
        assertFalse(IamConfigPsi.isPluginMappingContext(valueMappingOf(file, "properties")))
    }

    fun testPluginMappingContextFalseForVarMapping() {
        val file = configure(
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: ParentPlugin
              id: p-1
              properties:
                originatorName:
                - value: Airlock
                  var:
                    name: SOME_VAR
            """.trimIndent(),
        )
        assertFalse(IamConfigPsi.isPluginMappingContext(valueMappingOf(file, "var")))
    }

    fun testPluginMappingContextFalseForValueEntryMapping() {
        val file = configure(
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: ParentPlugin
              id: p-1
              properties:
                originatorName:
                - value: Airlock
            """.trimIndent(),
        )
        // The mapping holding `value: Airlock` is a value-entry, not a plugin mapping.
        assertFalse(IamConfigPsi.isPluginMappingContext(mappingWith(file, "value", "Airlock")))
    }

    // ---- isValueEntry -------------------------------------------------------------------------

    fun testIsValueEntryForValue() {
        val file = configure(
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: ParentPlugin
              id: p-1
              properties:
                originatorName:
                - value: Airlock
            """.trimIndent(),
        )
        assertTrue(IamConfigPsi.isValueEntry(mappingWith(file, "value", "Airlock")))
    }

    fun testIsValueEntryForValueList() {
        val file = configure(
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: ParentPlugin
              id: p-1
              properties:
                languages:
                - valueList:
                  - de
                  - en
            """.trimIndent(),
        )
        val valueListKv = PsiTreeUtil.findChildrenOfType(file, YAMLKeyValue::class.java)
            .first { it.keyText == "valueList" }
        assertTrue(IamConfigPsi.isValueEntry(valueListKv.parentMapping!!))
    }

    fun testIsValueEntryForPluginWrapper() {
        val file = configure(
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: ParentPlugin
              id: p-1
              properties:
                otpGenerator:
                - plugin:
                    type: ExtendedStringGenerator
            """.trimIndent(),
        )
        val pluginKv = PsiTreeUtil.findChildrenOfType(file, YAMLKeyValue::class.java)
            .first { it.keyText == "plugin" }
        assertTrue(IamConfigPsi.isValueEntry(pluginKv.parentMapping!!))
    }

    fun testIsValueEntryFalseForInnerPluginListItem() {
        val file = configure(
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: ParentPlugin
              id: p-1
              properties:
                tags:
                - pluginList:
                  - ref: some-tag-id
            """.trimIndent(),
        )
        // The inner `- ref: …` item has no value-carrier key, so it is not a value-entry.
        val refMapping = mappingWith(file, "ref", "some-tag-id")
        assertFalse(IamConfigPsi.isValueEntry(refMapping))
    }

    // ---- refTargetType ------------------------------------------------------------------------

    fun testRefTargetTypeResolvesDefinitionType() {
        val file = configure(
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: JdbcConnectionPool
              id: db-1
            - type: ParentPlugin
              id: p-1
              properties:
                sqlDataSource:
                  ref: db-1
            """.trimIndent(),
        )
        val model = me.knivram.iamplugin.psi.IamConfigFileModel.of(file)
        assertEquals("JdbcConnectionPool", IamConfigPsi.refTargetType("db-1", model))
        assertNull(IamConfigPsi.refTargetType("no-such-id", model))
    }
}
