package com.github.knivram.airlockiamconfigplugin

import com.github.knivram.airlockiamconfigplugin.inspection.DanglingReferenceInspection
import com.github.knivram.airlockiamconfigplugin.inspection.DuplicateIdInspection
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class InspectionTest : BasePlatformTestCase() {

    fun testDanglingReferenceFlagged() {
        myFixture.enableInspections(DanglingReferenceInspection())
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: JdbcConnectionPool
              id: good-id-0000
              displayName: DB
            - type: Consumer
              id: consumer-1
              properties:
                ok:
                  ref: good-id-0000
                broken:
                  ref: missing-id-9999
            """.trimIndent(),
        )
        val highlights = myFixture.doHighlighting()
        assertSize(1, highlights.byDescription("missing-id-9999"))
        assertEmpty(highlights.byDescription("good-id-0000"))
    }

    fun testDanglingReferenceIsFileLocal() {
        // A ref whose target id exists only in ANOTHER file is dangling under single-file scoping.
        myFixture.enableInspections(DanglingReferenceInspection())
        myFixture.addFileToProject(
            "other.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: JdbcConnectionPool
              id: in-other-file
              displayName: DB
            """.trimIndent(),
        )
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: Consumer
              id: consumer-1
              properties:
                broken:
                  ref: in-other-file
            """.trimIndent(),
        )
        assertSize(1, myFixture.doHighlighting().byDescription("in-other-file"))
    }

    fun testDanglingReferenceIgnoresNonIamConfigFile() {
        myFixture.enableInspections(DanglingReferenceInspection())
        myFixture.configureByText(
            "other.yaml",
            """
            type: SomethingElse
            spec:
            - ref: whatever-9999
            """.trimIndent(),
        )
        assertEmpty(myFixture.doHighlighting().byDescription("whatever-9999"))
    }

    fun testDuplicateIdWithinFileFlagged() {
        myFixture.enableInspections(DuplicateIdInspection())
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: PluginA
              id: dup-1234
            - type: PluginB
              id: dup-1234
            - type: PluginC
              id: unique-1
            """.trimIndent(),
        )
        val highlights = myFixture.doHighlighting()
        assertSize(2, highlights.byDescription("dup-1234"))
        assertEmpty(highlights.byDescription("unique-1"))
    }

    fun testDuplicateIdAcrossFilesNotFlagged() {
        // The whole point of single-file scoping: the same id in a duplicated (e.g. production) copy
        // must NOT be reported as a duplicate.
        myFixture.enableInspections(DuplicateIdInspection())
        myFixture.addFileToProject(
            "production.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: PluginA
              id: shared-uuid
            """.trimIndent(),
        )
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: PluginA
              id: shared-uuid
            """.trimIndent(),
        )
        assertEmpty(
            "an id duplicated only across files must not be flagged",
            myFixture.doHighlighting().byDescription("shared-uuid"),
        )
    }

    fun testValidConfigHasNoInspectionWarnings() {
        myFixture.enableInspections(DanglingReferenceInspection(), DuplicateIdInspection())
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
            - type: Consumer
              id: consumer-1
              properties:
                policy:
                  ref: db-1
            """.trimIndent(),
        )
        val warnings = myFixture.doHighlighting().filter { it.severity >= HighlightSeverity.WARNING }
        assertEmpty("a valid config must produce no warnings or errors", warnings)
    }

    private fun List<HighlightInfo>.byDescription(needle: String): List<HighlightInfo> =
        filter { it.description?.contains(needle) == true }
}
