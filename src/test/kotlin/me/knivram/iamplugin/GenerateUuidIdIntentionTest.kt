package me.knivram.iamplugin

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class GenerateUuidIdIntentionTest : BasePlatformTestCase() {

    private val intentionName = "Generate UUID"
    private val uuidRegex = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    fun testGeneratesUuidForEmptyPluginId() {
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: JdbcConnectionPool
              id: <caret>
              displayName: DB
            """.trimIndent(),
        )
        val intention = myFixture.findSingleIntention(intentionName)
        myFixture.launchAction(intention)

        // The id: now carries a freshly generated UUID.
        val idLine = myFixture.file.text.lineSequence().first { it.trimStart().startsWith("id:") }
        val value = idLine.substringAfter("id:").trim()
        assertTrue("expected a UUID, got '$value'", uuidRegex.matches(value))
    }

    fun testNotOfferedWhenIdAlreadyHasValue() {
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: JdbcConnectionPool
              id: already-the<caret>re
            """.trimIndent(),
        )
        assertEmpty(myFixture.filterAvailableIntentions(intentionName))
    }

    fun testNotOfferedForPropertyNamedId() {
        // `id` directly under `properties:` is a property value (e.g. StepId), not a plugin id.
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: StepId
              id: register-mtan
              properties:
                id: <caret>
                name: foo
            """.trimIndent(),
        )
        assertEmpty(myFixture.filterAvailableIntentions(intentionName))
    }

    fun testNotOfferedWithoutSiblingType() {
        // An `id:` with no sibling `type:` is not a plugin definition.
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - id: <caret>
              displayName: orphan
            """.trimIndent(),
        )
        assertEmpty(myFixture.filterAvailableIntentions(intentionName))
    }

    fun testNotOfferedInNonIamConfigFile() {
        myFixture.configureByText(
            "other.yaml",
            """
            type: SomethingElse
            spec:
            - type: Whatever
              id: <caret>
              displayName: x
            """.trimIndent(),
        )
        assertEmpty(myFixture.filterAvailableIntentions(intentionName))
    }
}
