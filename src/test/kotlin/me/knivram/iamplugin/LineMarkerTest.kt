package me.knivram.iamplugin

import com.intellij.codeInsight.daemon.GutterMark
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class LineMarkerTest : BasePlatformTestCase() {

    fun testMarkersOnDefinitionAndReference() {
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: JdbcConnectionPool
              id: db-1
              displayName: DB
            - type: PluginA
              id: a
              properties:
                one:
                  ref: db-1
            """.trimIndent(),
        )
        myFixture.doHighlighting()
        val gutters = myFixture.findAllGutters()

        assertSize(2, gutters.byTooltip("Navigate to references") + gutters.byTooltip("Navigate to definition"))
        assertSize(1, gutters.byTooltip("Navigate to references"))
        assertSize(1, gutters.byTooltip("Navigate to definition"))
    }

    fun testNoMarkerForUnreferencedId() {
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: JdbcConnectionPool
              id: db-1
              displayName: DB
            """.trimIndent(),
        )
        myFixture.doHighlighting()
        val gutters = myFixture.findAllGutters()
        assertEmpty(gutters.byTooltip("Navigate to references"))
        assertEmpty(gutters.byTooltip("Navigate to definition"))
    }

    fun testNoMarkersInNonIamConfigFile() {
        // Same id/ref shape, but not an IamConfig (no schemaVersion/type header) -> untouched.
        myFixture.configureByText(
            "config.yaml",
            """
            spec:
            - type: JdbcConnectionPool
              id: db-1
            - type: PluginA
              id: a
              properties:
                one:
                  ref: db-1
            """.trimIndent(),
        )
        myFixture.doHighlighting()
        val gutters = myFixture.findAllGutters()
        assertEmpty(gutters.byTooltip("Navigate to references"))
        assertEmpty(gutters.byTooltip("Navigate to definition"))
    }

    private fun List<GutterMark>.byTooltip(tooltip: String): List<GutterMark> =
        filter { it.tooltipText == tooltip }
}
