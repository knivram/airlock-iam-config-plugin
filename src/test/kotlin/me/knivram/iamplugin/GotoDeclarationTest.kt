package me.knivram.iamplugin

import me.knivram.iamplugin.navigation.IamConfigGotoDeclarationHandler
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class GotoDeclarationTest : BasePlatformTestCase() {

    private val handler = IamConfigGotoDeclarationHandler()

    fun testGotoDeclarationFromIdValueNavigatesToUsages() {
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: JdbcConnectionPool
              id: db-<caret>1
              displayName: DB
            - type: PluginA
              id: a
              properties:
                one:
                  ref: db-1
            - type: PluginB
              id: b
              properties:
                two:
                  parentRef: db-1
            """.trimIndent(),
        )
        val targets = handler.getGotoDeclarationTargets(sourceAtCaret(), myFixture.caretOffset, myFixture.editor)
        assertNotNull("Cmd+B on an id value should navigate to its usages", targets)
        assertSize(2, targets!!)
    }

    fun testGotoDeclarationNotHandledOnRefValue() {
        // ref values navigate via the reference, not this handler.
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: JdbcConnectionPool
              id: db-1
            - type: PluginA
              id: a
              properties:
                one:
                  ref: db-<caret>1
            """.trimIndent(),
        )
        assertNull(handler.getGotoDeclarationTargets(sourceAtCaret(), myFixture.caretOffset, myFixture.editor))
    }

    fun testGotoDeclarationNullWhenIdUnused() {
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: JdbcConnectionPool
              id: db-<caret>1
            """.trimIndent(),
        )
        assertNull(handler.getGotoDeclarationTargets(sourceAtCaret(), myFixture.caretOffset, myFixture.editor))
    }

    private fun sourceAtCaret() = myFixture.file.findElementAt(myFixture.caretOffset)
}
