package me.knivram.iamplugin

import me.knivram.iamplugin.icon.AirlockIamIconProvider
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class FileIconTest : BasePlatformTestCase() {

    private val provider = AirlockIamIconProvider()

    fun testIconShownForIamConfigFile() {
        val file = myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec: []
            """.trimIndent(),
        )
        assertSame(AirlockIcons.FILE, provider.getIcon(file, 0))
    }

    fun testNoIconForPlainYaml() {
        val file = myFixture.configureByText("plain.yaml", "type: SomethingElse\nfoo: bar\n")
        assertNull(provider.getIcon(file, 0))
    }
}
