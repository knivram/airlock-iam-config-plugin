package com.github.knivram.airlockiamconfigplugin

import com.github.knivram.airlockiamconfigplugin.psi.IamConfigPsi
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping

/**
 * Slot resolution is purely structural (no catalog), so these tests use synthetic parent/child types
 * and assert one (parentType, propertyName) per container form.
 */
class SlotResolutionTest : BasePlatformTestCase() {

    /** The plugin mapping carrying the named key (`type:` or `ref:`) with the given value. */
    private fun pluginMappingWith(file: YAMLFile, key: String, value: String): YAMLMapping {
        val kv = PsiTreeUtil.findChildrenOfType(file, YAMLKeyValue::class.java)
            .first { it.keyText == key && it.valueText == value }
        return kv.parentMapping ?: error("no parent mapping for $key: $value")
    }

    private fun configure(text: String): YAMLFile = myFixture.configureByText("config.yaml", text) as YAMLFile

    fun testSlotPluginLinkDirect() {
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
        val child = pluginMappingWith(file, "type", "JdbcConnectionPool")
        assertEquals(IamConfigPsi.Slot("ParentPlugin", "sqlDataSource"), IamConfigPsi.slotOf(child))
    }

    fun testSlotPluginLinkViaRef() {
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
                  ref: some-target-id
            """.trimIndent(),
        )
        val child = pluginMappingWith(file, "ref", "some-target-id")
        assertEquals(IamConfigPsi.Slot("ParentPlugin", "sqlDataSource"), IamConfigPsi.slotOf(child))
    }

    fun testSlotPluginList() {
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
        val child = pluginMappingWith(file, "type", "SomeStep")
        assertEquals(IamConfigPsi.Slot("ParentPlugin", "steps"), IamConfigPsi.slotOf(child))
    }

    fun testSlotPluginMap() {
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
        val child = pluginMappingWith(file, "type", "StepId")
        assertEquals(IamConfigPsi.Slot("ParentPlugin", "onFailureGotos"), IamConfigPsi.slotOf(child))
    }

    fun testSlotPluginWrapper() {
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
                  var:
                    name: SMS_GW
            """.trimIndent(),
        )
        val child = pluginMappingWith(file, "type", "GenericHttpSmsGateway")
        assertEquals(IamConfigPsi.Slot("ParentPlugin", "smsGateway"), IamConfigPsi.slotOf(child))
    }

    fun testTopLevelSpecItemHasNoSlot() {
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
        val child = pluginMappingWith(file, "type", "TopLevelPlugin")
        assertNull(IamConfigPsi.slotOf(child))
    }

    fun testEnclosingPluginType() {
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
        val propertiesKv = PsiTreeUtil.findChildrenOfType(file, YAMLKeyValue::class.java)
            .first { it.keyText == "properties" }
        assertEquals("ParentPlugin", IamConfigPsi.enclosingPluginType(propertiesKv))
    }
}
