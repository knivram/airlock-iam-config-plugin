package com.github.knivram.airlockiamconfigplugin

import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.testFramework.fixtures.BasePlatformTestCase

private const val DB_ID = "f0d2a309-8eb4-4574-a622-503d6e5f47be"
private const val POLICY_ID = "658b3b83-f638-45ff-a433-1e130c847e0c"

class CompletionTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    fun testRefCompletionListsPluginIds() {
        myFixture.configureByFile("refComplete.yaml")
        myFixture.completeBasic()
        val lookups = myFixture.lookupElementStrings
        assertNotNull("ref completion should offer a list", lookups)
        assertContainsElements(lookups!!, DB_ID, POLICY_ID)
    }

    fun testRefCompletionPresentsDisplayNameButInsertsId() {
        // Core contract: the lookup STRING (what is inserted) is the id; the PRESENTED text is the displayName.
        myFixture.configureByFile("refComplete.yaml")
        myFixture.completeBasic()
        val presented = myFixture.lookupElements!!.map { element ->
            val p = LookupElementPresentation()
            element.renderElement(p)
            element.lookupString to p.itemText
        }
        assertContainsElements(presented, DB_ID to "PostgreSQL Database Connection")
    }

    fun testRefCompletionByDisplayNameInsertsTheId() {
        // Typing the displayName matches, but the id (not the displayName) ends up in the document.
        myFixture.configureByText(
            "c.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: SimplePasswordPolicy
              id: $POLICY_ID
              displayName: Default Password Policy
            - type: Consumer
              id: consumer-1
              properties:
                policy:
                  ref: Default<caret>
            """.trimIndent(),
        )
        myFixture.completeBasic() // single match ("Default Password Policy") auto-inserts
        assertTrue(
            "completion must insert the id, not the displayName",
            myFixture.editor.document.text.contains("ref: $POLICY_ID"),
        )
    }

    fun testTypeCompletionOffersCatalogTypes() {
        // A typed prefix narrows the (1500+) catalog; "Jdbc" matches JdbcConnectionPool & JdbcDriverProperty.
        myFixture.configureByText(
            "c.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: Jdbc<caret>
            """.trimIndent(),
        )
        myFixture.completeBasic()
        val lookups = myFixture.lookupElementStrings
        assertNotNull("type completion should offer a list", lookups)
        assertContainsElements(lookups!!, "JdbcConnectionPool")
    }

    fun testTypeCompletionOffersProjectTypes() {
        // Custom types not in the bundled catalog must still be offered, sourced from the project index.
        myFixture.configureByText(
            "c.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: ZzzCustomAlpha
              id: a
            - type: ZzzCustomBeta
              id: b
            - type: Zzz<caret>
            """.trimIndent(),
        )
        myFixture.completeBasic()
        val lookups = myFixture.lookupElementStrings
        assertNotNull("type completion should offer a list", lookups)
        assertContainsElements(lookups!!, "ZzzCustomAlpha", "ZzzCustomBeta")
    }

    fun testNoCompletionInNonIamConfigFile() {
        // ref position in a non-IamConfig file must NOT offer project plugin ids.
        myFixture.configureByText(
            "ref.yaml",
            """
            type: SomethingElse
            spec:
            - ref: <caret>
            """.trimIndent(),
        )
        myFixture.completeBasic()
        myFixture.lookupElementStrings?.let {
            assertFalse("ref completion must be suppressed in non-IamConfig files", it.contains(DB_ID))
        }

        // type position in a non-IamConfig file must NOT offer the bundled catalog.
        myFixture.configureByText(
            "type.yaml",
            """
            type: SomethingElse
            spec:
            - type: Jdbc<caret>
            """.trimIndent(),
        )
        myFixture.completeBasic()
        myFixture.lookupElementStrings?.let {
            assertFalse("type completion must be suppressed in non-IamConfig files", it.contains("JdbcConnectionPool"))
        }
    }
}
