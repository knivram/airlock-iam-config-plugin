package me.knivram.iamplugin

import me.knivram.iamplugin.folding.RefDisplayNameFoldingBuilder
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.yaml.psi.YAMLFile

class FoldingTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    private fun buildRegions(fileName: String): Array<FoldingDescriptor> {
        val file = myFixture.configureByFile(fileName).containingFile as YAMLFile
        return RefDisplayNameFoldingBuilder().buildFoldRegions(file, myFixture.editor.document, false)
    }

    private fun buildRegionsFromText(text: String): Pair<YAMLFile, Array<FoldingDescriptor>> {
        val file = myFixture.configureByText("config.yaml", text) as YAMLFile
        val regions = RefDisplayNameFoldingBuilder().buildFoldRegions(file, myFixture.editor.document, false)
        return file to regions
    }

    fun testRefWithDisplayNameProducesSingleFold() {
        val (file, regions) = buildRegionsFromText(
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: JdbcConnectionPool
              id: db-1
              displayName: PostgreSQL DB
            - type: SomePlugin
              id: p-1
              properties:
                sqlDataSource:
                  ref: db-1
            """.trimIndent(),
        )
        assertSize(1, regions)
        val region = regions[0]
        assertEquals("PostgreSQL DB", region.placeholderText)

        // Fold range covers exactly the id value (not the `ref:` key, not surrounding whitespace).
        val foldedText = file.text.substring(region.range.startOffset, region.range.endOffset)
        assertEquals("db-1", foldedText)
    }

    fun testRefFoldIsCollapsedByDefault() {
        val (_, regions) = buildRegionsFromText(
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: JdbcConnectionPool
              id: db-1
              displayName: PostgreSQL DB
            - type: SomePlugin
              id: p-1
              properties:
                sqlDataSource:
                  ref: db-1
            """.trimIndent(),
        )
        assertSize(1, regions)
        assertTrue(
            "ref fold must be collapsed by default",
            RefDisplayNameFoldingBuilder().isCollapsedByDefault(regions[0].element),
        )
    }

    fun testParentRefAlsoFolds() {
        val (file, regions) = buildRegionsFromText(
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: JdbcConnectionPool
              id: db-1
              displayName: PostgreSQL DB
            - type: SomePlugin
              id: p-1
              parentRef: db-1
            """.trimIndent(),
        )
        assertSize(1, regions)
        assertEquals("PostgreSQL DB", regions[0].placeholderText)
        val foldedText = file.text.substring(regions[0].range.startOffset, regions[0].range.endOffset)
        assertEquals("db-1", foldedText)
    }

    fun testQuotedRefFoldsInnerIdOnly() {
        val (file, regions) = buildRegionsFromText(
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: JdbcConnectionPool
              id: db-1
              displayName: PostgreSQL DB
            - type: SomePlugin
              id: p-1
              properties:
                sqlDataSource:
                  ref: 'db-1'
            """.trimIndent(),
        )
        assertSize(1, regions)
        assertEquals("PostgreSQL DB", regions[0].placeholderText)
        // Quote-stripped: the folded range is the id inside the quotes, quotes stay visible.
        val foldedText = file.text.substring(regions[0].range.startOffset, regions[0].range.endOffset)
        assertEquals("db-1", foldedText)
    }

    fun testMultipleRefsEachFoldToOwnDisplayName() {
        // Two distinct refs in one file each resolve to a different target; both fold, independently.
        val (file, regions) = buildRegionsFromText(
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: JdbcConnectionPool
              id: db-1
              displayName: PostgreSQL DB
            - type: JdbcConnectionPool
              id: db-2
              displayName: MySQL DB
            - type: SomePlugin
              id: p-1
              properties:
                primary:
                  ref: db-1
                secondary:
                  ref: db-2
            """.trimIndent(),
        )
        assertSize(2, regions)
        val byPlaceholder = regions.associateBy { it.placeholderText }
        assertEquals(setOf("PostgreSQL DB", "MySQL DB"), byPlaceholder.keys)
        // Each fold covers exactly its own id value.
        val pgRange = byPlaceholder.getValue("PostgreSQL DB").range
        val myRange = byPlaceholder.getValue("MySQL DB").range
        assertEquals("db-1", file.text.substring(pgRange.startOffset, pgRange.endOffset))
        assertEquals("db-2", file.text.substring(myRange.startOffset, myRange.endOffset))
    }

    fun testMixedResolvableAndDanglingFoldsOnlyResolvable() {
        // One ref resolves (folds), one is dangling (no fold). Exactly one region, over the good id.
        val (file, regions) = buildRegionsFromText(
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: JdbcConnectionPool
              id: db-1
              displayName: PostgreSQL DB
            - type: SomePlugin
              id: p-1
              properties:
                good:
                  ref: db-1
                broken:
                  ref: missing-9999
            """.trimIndent(),
        )
        assertSize(1, regions)
        assertEquals("PostgreSQL DB", regions[0].placeholderText)
        val r = regions[0].range
        assertEquals("db-1", file.text.substring(r.startOffset, r.endOffset))
    }

    fun testFoldIsDisplayOnlyUnderlyingTextStaysTheId() {
        // The fold must be purely visual: the document text under the fold range is still the id, so
        // navigation / references / inspections are unaffected. (Caret-expand reveals exactly this text.)
        val (file, regions) = buildRegionsFromText(
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: JdbcConnectionPool
              id: db-1
              displayName: PostgreSQL DB
            - type: SomePlugin
              id: p-1
              properties:
                sqlDataSource:
                  ref: db-1
            """.trimIndent(),
        )
        assertSize(1, regions)
        val region = regions[0]
        // Placeholder differs from the real text, but the underlying text is untouched: still the id.
        assertEquals("PostgreSQL DB", region.placeholderText)
        assertEquals("db-1", file.text.substring(region.range.startOffset, region.range.endOffset))
    }

    fun testCollapsedByDefaultThroughRealEditorPipeline() {
        // Cross-check through the real platform folding pipeline (not just the builder in isolation):
        // open the file in the editor and inspect the materialised fold regions. Our ref fold must be
        // present (placeholder == displayName) and collapsed by default (region.isExpanded == false).
        // Selecting the region / placing the caret inside expands it back to the id — this asserts the
        // initial collapsed state the user sees on open.
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: JdbcConnectionPool
              id: db-1
              displayName: PostgreSQL DB
            - type: SomePlugin
              id: p-1
              properties:
                sqlDataSource:
                  ref: db-1
            """.trimIndent(),
        )
        // Running the highlighting passes on the open editor materialises code folding via the real
        // platform pipeline (which invokes our builder's isCollapsedByDefault) into the folding model.
        myFixture.doHighlighting()
        val ours = myFixture.editor.foldingModel.allFoldRegions
            .filter { it.placeholderText == "PostgreSQL DB" }
        assertSize(1, ours)
        assertFalse("the ref fold must be collapsed by default", ours.single().isExpanded)
        // Display-only: the region still spans the id text in the document.
        val region = ours.single()
        assertEquals(
            "db-1",
            myFixture.file.text.substring(region.startOffset, region.endOffset),
        )
    }

    fun testRefWithoutDisplayNameNotFolded() {
        assertEmpty(buildRegions("folding/refNoDisplayName.yaml"))
    }

    fun testDanglingRefNotFolded() {
        assertEmpty(buildRegions("folding/refDangling.yaml"))
    }

    fun testNonIamConfigFileNotFolded() {
        assertEmpty(buildRegions("folding/notIamConfigFold.yaml"))
    }

    fun testBlankDisplayNameNotFolded() {
        // An empty displayName is treated as no display name (definitionInfo returns null displayName).
        val (_, regions) = buildRegionsFromText(
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: JdbcConnectionPool
              id: db-1
              displayName: ''
            - type: SomePlugin
              id: p-1
              properties:
                sqlDataSource:
                  ref: db-1
            """.trimIndent(),
        )
        assertEmpty(regions)
    }
}
