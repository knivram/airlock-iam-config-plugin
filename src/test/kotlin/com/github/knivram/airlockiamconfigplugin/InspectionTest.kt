package com.github.knivram.airlockiamconfigplugin

import com.github.knivram.airlockiamconfigplugin.inspection.DanglingReferenceInspection
import com.github.knivram.airlockiamconfigplugin.inspection.DuplicateIdInspection
import com.github.knivram.airlockiamconfigplugin.inspection.MissingMandatoryPropertyInspection
import com.github.knivram.airlockiamconfigplugin.inspection.UnknownPropertyInspection
import com.github.knivram.airlockiamconfigplugin.inspection.WrongChildTypeInspection
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

    // ---- UnknownProperty ----------------------------------------------------------------------

    fun testUnknownPropertyFlagged() {
        myFixture.enableInspections(UnknownPropertyInspection())
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: JdbcConnectionPool
              id: db-1
              properties:
                url: jdbc:postgresql://localhost/db
                bogusKey: nope
            """.trimIndent(),
        )
        val highlights = myFixture.doHighlighting()
        assertSize(1, highlights.byDescription("bogusKey"))
        assertEmpty("a real schema key must not be flagged", highlights.byDescription("'url'"))
    }

    fun testUnknownPropertyNotFlaggedForUnknownType() {
        myFixture.enableInspections(UnknownPropertyInspection())
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: ZzzCustomUnknownPlugin
              id: x
              properties:
                anythingGoes: here
            """.trimIndent(),
        )
        assertEmpty(myFixture.doHighlighting().byDescription("anythingGoes"))
    }

    fun testUnknownPropertyIgnoresWrapperAndNestedKeys() {
        // Wrapper keys (pluginList) and keys nested inside a child plugin's own properties must not
        // be flagged against the OUTER type's schema.
        myFixture.enableInspections(UnknownPropertyInspection())
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: JdbcConnectionPool
              id: db-1
              properties:
                driverProperties:
                - pluginList:
                  - type: JdbcDriverProperty
                    properties:
                      property: someName
                      value: someValue
            """.trimIndent(),
        )
        val highlights = myFixture.doHighlighting()
        assertEmpty("pluginList wrapper key must not be flagged", highlights.byDescription("pluginList"))
        // 'property'/'value' are keys of the inner JdbcDriverProperty, not of JdbcConnectionPool;
        // they must be validated against the inner type's schema (where they are valid), never the outer.
        assertEmpty(
            "inner child property must not be flagged against outer type",
            highlights.byDescription("'JdbcConnectionPool' has no property"),
        )
        assertEmpty(
            "a valid inner child property must not be flagged at all",
            highlights.byDescription("has no property 'property'"),
        )
    }

    // ---- WrongChildType -----------------------------------------------------------------------

    fun testWrongChildTypeFlagged() {
        myFixture.enableInspections(WrongChildTypeInspection())
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: PasswordSettings
              id: pw-1
              properties:
                passwordPolicy:
                  type: JdbcConnectionPool
            """.trimIndent(),
        )
        assertSize(1, myFixture.doHighlighting().byDescription("JdbcConnectionPool"))
    }

    fun testWrongChildTypeValidChildNotFlagged() {
        myFixture.enableInspections(WrongChildTypeInspection())
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: PasswordSettings
              id: pw-1
              properties:
                passwordPolicy:
                  type: SimplePasswordPolicy
            """.trimIndent(),
        )
        assertEmpty(myFixture.doHighlighting().byDescription("SimplePasswordPolicy"))
    }

    fun testWrongChildTypeUnknownChildNotFlagged() {
        // A custom/unknown child type could legitimately be assignable; never flag it.
        myFixture.enableInspections(WrongChildTypeInspection())
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: PasswordSettings
              id: pw-1
              properties:
                passwordPolicy:
                  type: ZzzCustomPasswordPolicy
            """.trimIndent(),
        )
        assertEmpty(myFixture.doHighlighting().byDescription("ZzzCustomPasswordPolicy"))
    }

    fun testWrongChildTypeInPluginListFlagged() {
        // Sequence-backed Plugin-List slot: AccessCookieIdentityPropagator.httpParams accepts only
        // HttpParameter; a known, non-assignable JdbcConnectionPool child must be flagged.
        myFixture.enableInspections(WrongChildTypeInspection())
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: AccessCookieIdentityPropagator
              id: prop-1
              properties:
                httpParams:
                - pluginList:
                  - type: JdbcConnectionPool
            """.trimIndent(),
        )
        assertSize(1, myFixture.doHighlighting().byDescription("not assignable to property 'httpParams'"))
    }

    fun testWrongChildTypeInPluginMapFlagged() {
        // Sequence-backed Plugin-Map slot: AccountLinkDeletionInitiationStep.onFailureGotos accepts
        // only StepId; a known, non-assignable JdbcConnectionPool child must be flagged.
        myFixture.enableInspections(WrongChildTypeInspection())
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: AccountLinkDeletionInitiationStep
              id: step-1
              properties:
                onFailureGotos:
                - pluginMap:
                    SOME_KEY:
                      type: JdbcConnectionPool
            """.trimIndent(),
        )
        assertSize(1, myFixture.doHighlighting().byDescription("not assignable to property 'onFailureGotos'"))
    }

    fun testWrongChildTypeViaRefResolvesType() {
        // The child is given by ref to a same-file definition; the referenced type (JdbcConnectionPool)
        // is not assignable to passwordPolicy -> flagged.
        myFixture.enableInspections(WrongChildTypeInspection())
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: JdbcConnectionPool
              id: db-1
            - type: PasswordSettings
              id: pw-1
              properties:
                passwordPolicy:
                  ref: db-1
            """.trimIndent(),
        )
        assertSize(1, myFixture.doHighlighting().byDescription("not assignable to property 'passwordPolicy'"))
    }

    fun testWrongChildTypeViaRefValidTypeNotFlagged() {
        myFixture.enableInspections(WrongChildTypeInspection())
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: SimplePasswordPolicy
              id: policy-1
            - type: PasswordSettings
              id: pw-1
              properties:
                passwordPolicy:
                  ref: policy-1
            """.trimIndent(),
        )
        assertEmpty(myFixture.doHighlighting().byDescription("not assignable to property 'passwordPolicy'"))
    }

    // ---- MissingMandatory (off by default) ----------------------------------------------------

    fun testMissingMandatoryDisabledByDefault() {
        // No explicit enableInspections -> the default profile is used, where this inspection is OFF.
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: PasswordSettings
              id: pw-1
              properties:
                passwordPolicy:
                  type: SimplePasswordPolicy
            """.trimIndent(),
        )
        assertEmpty(
            "missing mandatory must not warn under the default profile",
            myFixture.doHighlighting().byDescription("missing mandatory property"),
        )
    }

    fun testMissingMandatoryFlagsWhenEnabled() {
        // PasswordSettings.passwordService is mandatory and omitted here.
        myFixture.enableInspections(MissingMandatoryPropertyInspection())
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: PasswordSettings
              id: pw-1
              properties:
                passwordPolicy:
                  type: SimplePasswordPolicy
            """.trimIndent(),
        )
        assertSize(1, myFixture.doHighlighting().byDescription("passwordService"))
    }

    private fun List<HighlightInfo>.byDescription(needle: String): List<HighlightInfo> =
        filter { it.description?.contains(needle) == true }
}
