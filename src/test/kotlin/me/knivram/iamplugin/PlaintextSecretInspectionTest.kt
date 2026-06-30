package me.knivram.iamplugin

import me.knivram.iamplugin.inspection.PlaintextSecretInspection
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Tests for [PlaintextSecretInspection], using AES256DecryptionTicketDecoder whose `password`
 * property is a sensitive String in the bundled catalog (and `requireAuthenticatedEncryption` is a
 * non-sensitive sibling). The inspection message embeds the property name, so the flagged cases are
 * asserted against `'password'`.
 */
class PlaintextSecretInspectionTest : BasePlatformTestCase() {

    fun testPlaintextSecretFlagged() {
        myFixture.enableInspections(PlaintextSecretInspection())
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: AES256DecryptionTicketDecoder
              id: dec-1
              properties:
                password: hunter2
            """.trimIndent(),
        )
        assertSize(1, myFixture.doHighlighting().byDescription("'password'"))
    }

    fun testPlaintextSecretInValueEntryFlagged() {
        myFixture.enableInspections(PlaintextSecretInspection())
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: AES256DecryptionTicketDecoder
              id: dec-1
              properties:
                password:
                - value: hunter2
            """.trimIndent(),
        )
        assertSize(1, myFixture.doHighlighting().byDescription("'password'"))
    }

    fun testObfuscatedValueNotFlagged() {
        myFixture.enableInspections(PlaintextSecretInspection())
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: AES256DecryptionTicketDecoder
              id: dec-1
              properties:
                password: '[OBFUSCATED]AbCdEf=='
            """.trimIndent(),
        )
        assertEmpty(myFixture.doHighlighting().byDescription("'password'"))
    }

    fun testFileReferenceNotFlagged() {
        myFixture.enableInspections(PlaintextSecretInspection())
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: AES256DecryptionTicketDecoder
              id: dec-1
              properties:
                password: '[FILE]secrets/password'
            """.trimIndent(),
        )
        assertEmpty(myFixture.doHighlighting().byDescription("'password'"))
    }

    fun testStorageIdValueEntryNotFlagged() {
        myFixture.enableInspections(PlaintextSecretInspection())
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: AES256DecryptionTicketDecoder
              id: dec-1
              properties:
                password:
                - value:
                    storageId: some.secret.id
            """.trimIndent(),
        )
        assertEmpty(myFixture.doHighlighting().byDescription("'password'"))
    }

    fun testVarQualifiedValueEntryNotFlagged() {
        myFixture.enableInspections(PlaintextSecretInspection())
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: AES256DecryptionTicketDecoder
              id: dec-1
              properties:
                password:
                - value: changeit
                  var:
                    name: DEC_PASSWORD
                    sensitive: true
            """.trimIndent(),
        )
        assertEmpty(myFixture.doHighlighting().byDescription("'password'"))
    }

    fun testNonSensitivePropertyNotFlagged() {
        // requireAuthenticatedEncryption is a non-sensitive sibling on the same type.
        myFixture.enableInspections(PlaintextSecretInspection())
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: AES256DecryptionTicketDecoder
              id: dec-1
              properties:
                requireAuthenticatedEncryption: false
            """.trimIndent(),
        )
        assertEmpty(myFixture.doHighlighting().byDescription("'requireAuthenticatedEncryption'"))
    }

    fun testUnknownTypeNotFlagged() {
        // A custom type's `password:` is not catalog-known and must never be flagged.
        myFixture.enableInspections(PlaintextSecretInspection())
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: ZzzCustomUnknownPlugin
              id: x
              properties:
                password: hunter2
            """.trimIndent(),
        )
        assertEmpty(myFixture.doHighlighting().byDescription("'password'"))
    }

    fun testSensitivePropertyNamedValueFlagged() {
        // StaticSensitiveHttpParameter has a property literally named `value` that is sensitive. As a
        // direct property (not a value-entry carrier) its plain-text value must still be flagged.
        myFixture.enableInspections(PlaintextSecretInspection())
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: StaticSensitiveHttpParameter
              id: p-1
              properties:
                value: hunter2
            """.trimIndent(),
        )
        assertSize(1, myFixture.doHighlighting().byDescription("'value'"))
    }

    private fun List<HighlightInfo>.byDescription(needle: String): List<HighlightInfo> =
        filter { it.description?.contains(needle) == true }
}
