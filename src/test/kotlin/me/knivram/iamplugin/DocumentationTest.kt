package me.knivram.iamplugin

import me.knivram.iamplugin.catalog.AirlockPluginCatalog
import me.knivram.iamplugin.documentation.AirlockDocumentationProvider
import com.intellij.psi.PsiElement
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue

/**
 * Drives [AirlockDocumentationProvider] end-to-end: configure a YAML with a `<caret>` on the hover
 * target, resolve the doc element via getCustomDocumentationElement, then render via generateDoc.
 * Uses the real bundled catalog (JdbcConnectionPool, maximumPoolSize, PasswordSettings.passwordPolicy).
 */
class DocumentationTest : BasePlatformTestCase() {

    private val provider = AirlockDocumentationProvider()

    /** A definition (JdbcConnectionPool "My Pool") plus a ref to it, caret on the ref value. */
    private val REF_FIXTURE = """
        schemaVersion: iam.airlock.com/v1.1
        type: IamConfig
        spec:
        - type: JdbcConnectionPool
          id: my-pool
          displayName: My Pool
        - type: SomePlugin
          id: consumer
          properties:
            sqlDataSource:
              ref: my<caret>-pool
    """.trimIndent()

    /** The doc element the provider claims at the current caret, or null. */
    private fun targetAtCaret(text: String): Any? {
        val file = myFixture.configureByText("config.yaml", text) as YAMLFile
        val offset = myFixture.caretOffset
        val ctx = file.findElementAt(offset)
        return provider.getCustomDocumentationElement(myFixture.editor, file, ctx, offset)
    }

    /** Full doc HTML at the current caret, or null when nothing is claimed / catalog miss. */
    private fun docAtCaret(text: String): String? {
        val file = myFixture.configureByText("config.yaml", text) as YAMLFile
        val offset = myFixture.caretOffset
        val ctx = file.findElementAt(offset)
        val target = provider.getCustomDocumentationElement(myFixture.editor, file, ctx, offset)
            ?: return null
        return provider.generateDoc(target, ctx)
    }

    private fun jdbcDefinition(extra: String = ""): String = """
        schemaVersion: iam.airlock.com/v1.1
        type: IamConfig
        spec:
        - type: JdbcConnectionPool
          id: my-pool
          displayName: My Pool
          properties:
            url: jdbc:postgresql://localhost/db
            maximumPoolSize: 10
        $extra
    """.trimIndent()

    // (a) type value -> doc contains the plugin title + a known property name.
    fun testTypeValueDoc() {
        val doc = docAtCaret(
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            spec:
            - type: JdbcConn<caret>ectionPool
              id: my-pool
            """.trimIndent(),
        )
        assertNotNull("type value of a known type must produce a doc", doc)
        assertTrue("doc shows the type token", doc!!.contains("JdbcConnectionPool"))
        assertTrue("doc lists a known property", doc.contains("url"))
        assertTrue("doc lists another known property", doc.contains("maximumPoolSize"))
        // "HikariCP" appears only in the catalog description, not the title or any property name.
        assertTrue("doc embeds the type description", doc.contains("HikariCP"))
        // No property cap: every property is listed, including ones past the old 12-row limit.
        val props = AirlockPluginCatalog.getInstance().entry("JdbcConnectionPool")!!.properties
        assertTrue("JdbcConnectionPool has >12 properties (the old cap would have truncated)", props.size > 12)
        assertTrue("the last property is shown (no cap)", doc.contains(props.last().name))
    }

    // (b) property key -> doc contains the property's kind + a snippet of its description.
    fun testPropertyKeyDoc() {
        val doc = docAtCaret(
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            spec:
            - type: JdbcConnectionPool
              id: my-pool
              properties:
                maximum<caret>PoolSize: 10
            """.trimIndent(),
        )
        assertNotNull("a known property key must produce a doc", doc)
        assertTrue("doc names the property", doc!!.contains("maximumPoolSize"))
        assertTrue("doc shows the Kind row", doc.contains("Kind"))
        assertTrue("doc shows the integer kind", doc.contains("integer"))
        assertTrue("doc shows the Mandatory row", doc.contains("Mandatory"))
        // maximumPoolSize is not mandatory; assert the row VALUE, not just the header.
        assertEquals("maximumPoolSize is not mandatory", "no", mandatoryValue(doc))
        // maximumPoolSize description begins "The maximum pool size ..." (verified in bundled data).
        assertTrue("doc embeds the property description", doc.contains("maximum pool size"))
    }

    /** The yes/no value rendered in the "Mandatory" section row, or null if absent. */
    private fun mandatoryValue(doc: String): String? =
        Regex("Mandatory.*?(yes|no)", RegexOption.DOT_MATCHES_ALL).find(doc)?.groupValues?.get(1)

    // (b'') a mandatory property key -> the Mandatory row VALUE is "yes".
    fun testMandatoryPropertyKeyDoc() {
        val doc = docAtCaret(
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            spec:
            - type: JdbcConnectionPool
              id: my-pool
              properties:
                u<caret>rl: jdbc:postgresql://localhost/db
            """.trimIndent(),
        )
        assertNotNull("a known property key must produce a doc", doc)
        assertTrue("doc names the property", doc!!.contains("url"))
        assertEquals("url is mandatory", "yes", mandatoryValue(doc))
    }

    // (b') plugin-valued property key -> assignable types row with a real assignable token.
    fun testPluginValuedPropertyKeyDoc() {
        val doc = docAtCaret(
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            spec:
            - type: PasswordSettings
              id: pw
              properties:
                password<caret>Policy:
                  ref: pol-1
            """.trimIndent(),
        )
        assertNotNull(doc)
        assertTrue("doc shows assignable types", doc!!.contains("Assignable types"))
        assertTrue("doc lists a real assignable token", doc.contains("SimplePasswordPolicy"))
    }

    // (c) id value -> doc contains the type + displayName.
    fun testIdValueDoc() {
        val doc = docAtCaret(
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            spec:
            - type: JdbcConnectionPool
              id: my<caret>-pool
              displayName: My Pool
            """.trimIndent(),
        )
        assertNotNull("a plugin id value must produce an instance doc", doc)
        assertTrue("instance doc shows the type", doc!!.contains("JdbcConnectionPool"))
        assertTrue("instance doc shows the displayName", doc.contains("My Pool"))
        assertTrue("instance doc shows the id", doc.contains("my-pool"))
        // "HikariCP" is unique to the type description, pinning the instance-doc description block.
        assertTrue("instance doc embeds the type description", doc.contains("HikariCP"))
    }

    // (c') ref value resolves to the id key-value; generateDoc on the resolved id renders instance doc.
    fun testRefResolvesToInstanceDoc() {
        val file = myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            spec:
            - type: JdbcConnectionPool
              id: my-pool
              displayName: My Pool
            - type: SomePlugin
              id: other
              properties:
                sqlDataSource:
                  ref: my<caret>-pool
            """.trimIndent(),
        ) as YAMLFile
        val offset = myFixture.caretOffset
        val ctx = file.findElementAt(offset)

        // The provider claims the ref value itself (Target 4) and resolves it to the target definition's
        // `id:` key-value, so Quick Doc stays robust instead of relying on the platform's reference/UUID path.
        val claimed = provider.getCustomDocumentationElement(myFixture.editor, file, ctx, offset)
        assertInstanceOf(claimed, YAMLKeyValue::class.java)
        assertEquals("ref doc element is the target plugin's id", "id", (claimed as YAMLKeyValue).keyText)
        assertEquals("my-pool", claimed.valueText)

        val doc = provider.generateDoc(claimed, ctx)
        assertNotNull("instance doc for the resolved id", doc)
        assertTrue(doc!!.contains("JdbcConnectionPool"))
        assertTrue(doc.contains("My Pool"))
    }

    // Hovering a ref VALUE must produce the target plugin's doc via OUR provider (Target 4), not rely on
    // the platform's reference/UUID documentation. docAtCaret exercises getCustomDocumentationElement +
    // generateDoc, exactly what the IDE uses.
    fun testRefValueDocResolvesToTargetPluginViaProvider() {
        val doc = docAtCaret(REF_FIXTURE)
        assertNotNull("hovering a ref must show the target plugin's doc", doc)
        assertTrue("ref doc shows the target type", doc!!.contains("JdbcConnectionPool"))
        assertTrue("ref doc shows the target displayName", doc.contains("My Pool"))
    }

    // The reported bug: the ref doc stops working after edits. A structural PSI invalidation (AST drop /
    // background reparse, which dropPsiCaches simulates) must NOT break it.
    fun testRefValueDocSurvivesPsiInvalidation() {
        val file = myFixture.configureByText("config.yaml", REF_FIXTURE) as YAMLFile
        val offset = myFixture.caretOffset
        // warm: the doc resolves on a fresh file
        assertNotNull(provider.getCustomDocumentationElement(myFixture.editor, file, file.findElementAt(offset), offset))

        com.intellij.psi.impl.PsiManagerEx.getInstanceEx(project).dropPsiCaches()

        val current = myFixture.file as YAMLFile
        val ctx = current.findElementAt(offset)
        val target = provider.getCustomDocumentationElement(myFixture.editor, current, ctx, offset)
        assertNotNull("ref doc element must still resolve after a PSI invalidation", target)
        val doc = provider.generateDoc(target, ctx)
        assertNotNull("ref doc must still render after a PSI invalidation", doc)
        assertTrue(doc!!.contains("JdbcConnectionPool"))
        assertTrue(doc.contains("My Pool"))
    }

    // (d) null: unknown type value -> element IS claimed, but generateDoc returns null (no popup, no error).
    fun testUnknownTypeYieldsNoDoc() {
        val unknownTypeYaml = """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            spec:
            - type: ZzzDefinitely<caret>NotARealType
              id: x
        """.trimIndent()

        // The provider claims the type value (Target 1 has no catalog gate).
        val target = targetAtCaret(unknownTypeYaml)
        assertNotNull("the unknown type value is still claimed as a doc element", target)

        // But generateDoc on it yields null: catalog miss -> no popup, no error.
        val file = myFixture.configureByText("config.yaml", unknownTypeYaml) as YAMLFile
        val ctx = file.findElementAt(myFixture.caretOffset)
        assertNull(
            "an unknown type produces no doc (catalog miss returns null, not an error)",
            provider.generateDoc(target as PsiElement, ctx),
        )
    }

    // (d) null: non-IamConfig file -> nothing claimed.
    fun testNonIamConfigFileYieldsNoDoc() {
        val target = targetAtCaret(
            """
            type: SomethingElse
            spec:
            - type: JdbcConn<caret>ectionPool
            """.trimIndent(),
        )
        assertNull("documentation is only offered in IamConfig files", target)
    }

    // (d) null: unrelated scalar (a plain value, not a type/id/property key) -> nothing claimed.
    fun testUnrelatedScalarYieldsNoDoc() {
        val target = targetAtCaret(
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            spec:
            - type: JdbcConnectionPool
              id: my-pool
              displayName: My<caret> Pool
            """.trimIndent(),
        )
        assertNull("displayName value is not a documentation target", target)
    }

    // The type KEY (not its value) is not claimed.
    fun testTypeKeyNotClaimed() {
        val target = targetAtCaret(
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            spec:
            - ty<caret>pe: JdbcConnectionPool
              id: my-pool
            """.trimIndent(),
        )
        assertNull("only the type VALUE is claimed, not the key", target)
    }

    // A property VALUE position is not claimed (only the key is).
    fun testPropertyValueNotClaimed() {
        val target = targetAtCaret(jdbcDefinitionWithCaretOnUrlValue())
        assertNull("only the property KEY is claimed, not its value", target)
    }

    private fun jdbcDefinitionWithCaretOnUrlValue(): String = """
        schemaVersion: iam.airlock.com/v1.1
        type: IamConfig
        spec:
        - type: JdbcConnectionPool
          id: my-pool
          properties:
            url: jdbc:postgre<caret>sql://localhost/db
    """.trimIndent()

    // Unknown property under a known type -> no doc.
    fun testUnknownPropertyUnderKnownTypeYieldsNoDoc() {
        val doc = docAtCaret(
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            spec:
            - type: JdbcConnectionPool
              id: my-pool
              properties:
                bogus<caret>Prop: 1
            """.trimIndent(),
        )
        assertNull("an unknown property under a known type produces no doc", doc)
    }

    // Property key under an unknown parent type -> no doc.
    fun testPropertyUnderUnknownParentTypeYieldsNoDoc() {
        val target = targetAtCaret(
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            spec:
            - type: ZzzDefinitelyNotARealType
              id: x
              properties:
                any<caret>Prop: 1
            """.trimIndent(),
        )
        assertNull("a property under an unknown parent type is not claimed", target)
    }

    // Non-definition id (a property named id, not a sibling of type:) -> no doc.
    fun testNonDefinitionIdYieldsNoDoc() {
        val target = targetAtCaret(
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            spec:
            - type: JdbcConnectionPool
              id: my-pool
              properties:
                inner:
                  id: not-a<caret>-definition
            """.trimIndent(),
        )
        assertNull("a property-level id is not a plugin definition id", target)
    }

    // Sanitization smoke: the rendered description carries an allowed tag and no <a>/<div> residue.
    fun testDescriptionIsSanitized() {
        // maximumPoolSize's bundled description carries inline <code>/<em> (verified) that no
        // section value can emit, so it pins the description passthrough.
        val doc = docAtCaret(
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            spec:
            - type: JdbcConnectionPool
              id: my-pool
              properties:
                maximum<caret>PoolSize: 10
            """.trimIndent(),
        )
        assertNotNull(doc)
        assertTrue("an allowed inline tag from the description is preserved", doc!!.contains("<code>"))
        assertTrue("emphasis from the description survives", doc.contains("<em>"))
        assertFalse("anchor tags are stripped at build time", doc.contains("<a "))
        assertFalse("no docs-only div residue leaks in", doc.contains("<div class=\"property"))
    }

    // The build-time balancing pass guarantees no bundled description ships with unbalanced <p> tags.
    fun testDescriptionTagsAreBalanced() {
        // AspSmsGateway's plugin-level description ended with an orphan <p> in the source HTML;
        // the generator's balancing pass must have closed it (verified against the bundled data).
        val entry = AirlockPluginCatalog.getInstance().entry("AspSmsGateway")
        assertNotNull("AspSmsGateway is a known type", entry)
        val desc = entry!!.description
        assertNotNull("AspSmsGateway has a bundled description", desc)
        val open = Regex("<p>").findAll(desc!!).count()
        val close = Regex("</p>").findAll(desc).count()
        assertEquals("description <p> tags are balanced", open, close)
    }
}
