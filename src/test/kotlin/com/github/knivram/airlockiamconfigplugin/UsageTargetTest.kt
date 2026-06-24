package com.github.knivram.airlockiamconfigplugin

import com.github.knivram.airlockiamconfigplugin.usage.IamConfigUsageTargetProvider
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.usages.PsiElementUsageTarget
import org.jetbrains.yaml.psi.YAMLKeyValue

class UsageTargetTest : BasePlatformTestCase() {

    private val provider = IamConfigUsageTargetProvider()

    fun testFindUsagesTargetOfferedOnIdValue() {
        // Caret on the id VALUE (the uuid), not the "id" key.
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: JdbcConnectionPool
              id: f0d2a309-<caret>8eb4-4574-a622-503d6e5f47be
              displayName: DB
            """.trimIndent(),
        )
        val targets = provider.getTargets(myFixture.editor, myFixture.file)
        assertNotNull("a usage target must be offered on the id value", targets)
        assertSize(1, targets!!)
        val element = (targets[0] as PsiElementUsageTarget).element as YAMLKeyValue
        assertEquals("id", element.keyText)
        assertEquals("f0d2a309-8eb4-4574-a622-503d6e5f47be", element.valueText)
    }

    fun testNoTargetOnTypeValue() {
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: Jdbc<caret>ConnectionPool
              id: some-id
            """.trimIndent(),
        )
        assertNull(
            "find-usages target must not be offered on a type value",
            provider.getTargets(myFixture.editor, myFixture.file),
        )
    }

    fun testNoTargetOnPropertyLevelId() {
        // A property-level id (not a plugin definition) must not offer a target.
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: StepId
              id: 22222222-2222-2222-2222-222222222222
              properties:
                id: regist<caret>er-mtan
            """.trimIndent(),
        )
        assertNull(
            "a property-level id must not offer a find-usages target",
            provider.getTargets(myFixture.editor, myFixture.file),
        )
    }

    fun testNoTargetInNonIamConfigFile() {
        myFixture.configureByText(
            "plain.yaml",
            """
            type: SomethingElse
            spec:
            - id: some-<caret>id
            """.trimIndent(),
        )
        assertNull(provider.getTargets(myFixture.editor, myFixture.file))
    }
}
