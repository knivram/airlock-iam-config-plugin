package com.github.knivram.airlockiamconfigplugin

import com.github.knivram.airlockiamconfigplugin.documentation.AirlockDocumentationProvider
import com.github.knivram.airlockiamconfigplugin.psi.IamConfigFileModel
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.impl.PsiManagerEx
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue

/**
 * Regression for the user-reported staleness: hover / Quick Documentation on a `ref:` resolves to the
 * target plugin definition when the file is freshly opened, but after editing the file the ref stops
 * resolving and the doc falls back to the platform generic popup (titled "ref", showing the file name).
 *
 * Root cause: [IamConfigFileModel.of] cached the per-file Model keyed only on the `file` PsiElement.
 * That dependency tracks the file's document modification stamp but NOT structural PSI invalidations
 * (AST drop / background reparse), which the platform signals via [PsiModificationTracker]. After such
 * an event the stale Model is served, holding now-detached `id:` / `ref:` PSI nodes; multiResolve then
 * returns an invalid target and the doc falls back. The cache must depend on MODIFICATION_COUNT.
 */
class RefResolveAfterEditTest : BasePlatformTestCase() {

    private val provider = AirlockDocumentationProvider()

    private val yaml = """
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
              ref: my-pool
    """.trimIndent()

    private fun refValueOffset(text: String): Int = text.lastIndexOf("my-pool")

    /**
     * The decisive reproduction: warm the cache, then trigger a structural PSI invalidation that bumps
     * [PsiModificationTracker.MODIFICATION_COUNT] without changing the document text (exactly what a
     * background reparse / AST drop does mid-editing). The cached Model must be rebuilt; with the old
     * `file`-only dependency it is served stale.
     */
    fun testModelRebuiltAfterPsiInvalidation() {
        val file = myFixture.configureByText("config.yaml", yaml) as YAMLFile

        val model1 = IamConfigFileModel.of(file)
        assertNotNull("fresh open indexes the definition", model1.definitions["my-pool"])
        val mcBefore = PsiModificationTracker.getInstance(project).modificationCount

        // Structural PSI invalidation (AST drop / background reparse) — bumps MODIFICATION_COUNT,
        // does NOT bump the file's document modification stamp.
        PsiManagerEx.getInstanceEx(project).dropPsiCaches()
        assertTrue(
            "the drop must bump MODIFICATION_COUNT (precondition of the repro)",
            PsiModificationTracker.getInstance(project).modificationCount != mcBefore,
        )

        val model2 = IamConfigFileModel.of(file)
        assertNotSame(
            "after a PSI invalidation the Model must be rebuilt, not served stale (the staleness bug)",
            model1,
            model2,
        )
    }

    /** End-to-end: the ref still resolves and the instance doc still renders after a document edit. */
    fun testRefStillResolvesAfterDocumentEdit() {
        val file = myFixture.configureByText("config.yaml", yaml) as YAMLFile

        val freshResolved = file.findReferenceAt(refValueOffset(file.text))?.resolve() as? YAMLKeyValue
        assertNotNull("fresh open: ref resolves to the target plugin id", freshResolved)
        assertEquals("my-pool", freshResolved!!.valueText)
        assertNotNull("fresh open: instance doc renders", provider.generateDoc(freshResolved, null))

        // User repro: delete the `ref: my-pool` line and re-insert the same line.
        val document = myFixture.editor.document
        val pdm = PsiDocumentManager.getInstance(project)
        val oldLine = "      ref: my-pool"
        WriteCommandAction.runWriteCommandAction(project) {
            val start = document.text.indexOf(oldLine)
            document.deleteString(start, start + oldLine.length)
            pdm.commitDocument(document)
            document.insertString(start, oldLine)
            pdm.commitDocument(document)
        }

        val newFile = pdm.getPsiFile(document) as YAMLFile
        val resolved = newFile.findReferenceAt(newFile.text.lastIndexOf("my-pool"))?.resolve() as? YAMLKeyValue
        assertNotNull("after edit: ref must STILL resolve to the target plugin id", resolved)
        assertEquals("my-pool", resolved!!.valueText)
        assertTrue("after edit: resolved target is valid PSI", resolved.isValid)
        val doc = provider.generateDoc(resolved, null)
        assertNotNull("after edit: instance doc must still render", doc)
        assertTrue("after edit: doc still shows the plugin type", doc!!.contains("JdbcConnectionPool"))
    }

    /** A delete+readd of the definition's `id:` line keeps the ref resolving to valid PSI. */
    fun testRefResolvesAfterDefinitionDeletedAndReadded() {
        val file = myFixture.configureByText("config.yaml", yaml) as YAMLFile
        val document = myFixture.editor.document
        val pdm = PsiDocumentManager.getInstance(project)

        assertNotNull(file.findReferenceAt(refValueOffset(file.text))?.resolve())

        WriteCommandAction.runWriteCommandAction(project) {
            val idLine = "  id: my-pool"
            val start = document.text.indexOf(idLine)
            document.deleteString(start, start + idLine.length)
            pdm.commitDocument(document)
        }
        // Mid-edit the definition is gone, so the ref does not resolve — expected.
        val midFile = pdm.getPsiFile(document) as YAMLFile
        assertNull(
            "with the definition removed the ref must not resolve",
            midFile.findReferenceAt(midFile.text.lastIndexOf("my-pool"))?.resolve(),
        )

        WriteCommandAction.runWriteCommandAction(project) {
            val anchor = "  displayName: My Pool"
            val start = document.text.indexOf(anchor)
            document.insertString(start, "  id: my-pool\n")
            pdm.commitDocument(document)
        }

        val newFile = pdm.getPsiFile(document) as YAMLFile
        val resolved = newFile.findReferenceAt(newFile.text.lastIndexOf("my-pool"))?.resolve() as? YAMLKeyValue
        assertNotNull("after re-adding the id, the ref must resolve again", resolved)
        assertTrue("resolved id must be valid PSI", resolved!!.isValid)
        assertNotNull("instance doc renders again", provider.generateDoc(resolved, null))
    }
}
