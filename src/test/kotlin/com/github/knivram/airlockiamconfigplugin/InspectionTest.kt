package com.github.knivram.airlockiamconfigplugin

import com.github.knivram.airlockiamconfigplugin.inspection.DanglingReferenceInspection
import com.github.knivram.airlockiamconfigplugin.inspection.DuplicateIdInspection
import com.github.knivram.airlockiamconfigplugin.inspection.MissingMandatoryPropertyInspection
import com.github.knivram.airlockiamconfigplugin.inspection.UnknownPropertyInspection
import com.github.knivram.airlockiamconfigplugin.inspection.VariableEnvInspection
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

    fun testUnknownPropertyIgnoresStructuralValueKeys() {
        // Defensive guard: if value-entry structural keys (value/env/var) ever surface directly under a
        // known type's `properties:` (malformed value list), they are structural, not unknown properties.
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
                value: x
                env: prod
                var: y
                pluginList: z
            """.trimIndent(),
        )
        val highlights = myFixture.doHighlighting()
        assertEmpty("structural 'value' key must not be flagged", highlights.byDescription("'value'"))
        assertEmpty("structural 'env' key must not be flagged", highlights.byDescription("'env'"))
        assertEmpty("structural 'var' key must not be flagged", highlights.byDescription("'var'"))
        assertEmpty("structural 'pluginList' key must not be flagged", highlights.byDescription("'pluginList'"))
    }

    fun testUnknownPropertyIgnoresStorageIdKey() {
        // storageId is a value-override structural key; even if it surfaces directly under a known
        // type's `properties:` (malformed), it must never be flagged as an unknown property.
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
                storageId: x
            """.trimIndent(),
        )
        assertEmpty(
            "structural 'storageId' key must not be flagged",
            myFixture.doHighlighting().byDescription("'storageId'"),
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

    fun testWrongChildTypeViaRefInPluginListFlagged() {
        // A `- ref:` item inside a pluginList wrapper must resolve its type through the ref and be
        // checked against the slot's assignable set (httpParams accepts only HttpParameter). This
        // exercises enclosingPropertyKeyValue's wrapper-skipping together with ref resolution.
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
            - type: AccessCookieIdentityPropagator
              id: prop-1
              properties:
                httpParams:
                - pluginList:
                  - ref: db-1
            """.trimIndent(),
        )
        assertSize(1, myFixture.doHighlighting().byDescription("not assignable to property 'httpParams'"))
    }

    fun testWrongChildTypeViaParentRefInPluginMapFlagged() {
        // parentRef is also a REF_KEY resolved by childTypeOf; a parentRef-linked child inside a
        // pluginMap wrapper must be type-checked against the slot (onFailureGotos accepts only StepId).
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
            - type: AccountLinkDeletionInitiationStep
              id: step-1
              properties:
                onFailureGotos:
                - pluginMap:
                    SOME_KEY:
                      parentRef: db-1
            """.trimIndent(),
        )
        assertSize(1, myFixture.doHighlighting().byDescription("not assignable to property 'onFailureGotos'"))
    }

    fun testWrongChildTypeEmptyAssignableNotFlagged() {
        // Adminapp.customExtensions is a KNOWN Plugin-List slot with an EMPTY assignable list.
        // An empty assignable list means "any plugin allowed" -> a known child must never be flagged.
        myFixture.enableInspections(WrongChildTypeInspection())
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: Adminapp
              id: app-1
              properties:
                customExtensions:
                - pluginList:
                  - type: JdbcConnectionPool
            """.trimIndent(),
        )
        assertEmpty(
            "an empty assignable list means any plugin is allowed",
            myFixture.doHighlighting().byDescription("not assignable"),
        )
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

    // ---- VariableEnv (variables & environments, Improvement #4) -------------------------------

    fun testVarMissingNameFlagged() {
        myFixture.enableInspections(VariableEnvInspection())
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: GenericHttpSmsGateway
              id: gw-1
              properties:
                originatorName:
                - value: Airlock
                  var:
                    sensitive: true
            """.trimIndent(),
        )
        assertSize(1, myFixture.doHighlighting().byDescription("requires a 'name'"))
    }

    fun testVarWithNameNotFlagged() {
        myFixture.enableInspections(VariableEnvInspection())
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: GenericHttpSmsGateway
              id: gw-1
              properties:
                originatorName:
                - value: Airlock
                  var:
                    name: ORIGINATOR
            """.trimIndent(),
        )
        assertEmpty(myFixture.doHighlighting().byDescription("requires a 'name'"))
    }

    fun testVarUnknownKeyFlagged() {
        myFixture.enableInspections(VariableEnvInspection())
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: GenericHttpSmsGateway
              id: gw-1
              properties:
                originatorName:
                - value: Airlock
                  var:
                    name: ORIGINATOR
                    sensitiv: true
            """.trimIndent(),
        )
        assertSize(1, myFixture.doHighlighting().byDescription("'sensitiv' is not a valid key"))
    }

    fun testVarSensitiveNonBoolFlagged() {
        myFixture.enableInspections(VariableEnvInspection())
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: GenericHttpSmsGateway
              id: gw-1
              properties:
                originatorName:
                - value: Airlock
                  var:
                    name: ORIGINATOR
                    sensitive: maybe
            """.trimIndent(),
        )
        assertSize(1, myFixture.doHighlighting().byDescription("'sensitive' must be a boolean"))
    }

    fun testVarSensitiveBoolNotFlagged() {
        // Both the bare boolean (example-config style) and the quoted form are accepted.
        myFixture.enableInspections(VariableEnvInspection())
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: GenericHttpSmsGateway
              id: gw-1
              properties:
                user:
                - value: a
                  var:
                    name: U
                    sensitive: true
                password:
                - value: b
                  var:
                    name: P
                    sensitive: 'false'
            """.trimIndent(),
        )
        assertEmpty(myFixture.doHighlighting().byDescription("must be a boolean"))
    }

    fun testDuplicateEnvContextFlagged() {
        myFixture.enableInspections(VariableEnvInspection())
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: SomePlugin
              id: p-1
              properties:
                url:
                - value: a
                  env: prod
                - value: b
                  env: prod
            """.trimIndent(),
        )
        assertSize(2, myFixture.doHighlighting().byDescription("Duplicate value entry for environment 'prod'"))
    }

    fun testDuplicateSharedEntriesFlagged() {
        // Two entries with no env/context both target the shared/default environment -> duplicate.
        myFixture.enableInspections(VariableEnvInspection())
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: SomePlugin
              id: p-1
              properties:
                url:
                - value: a
                - value: b
            """.trimIndent(),
        )
        assertSize(2, myFixture.doHighlighting().byDescription("Duplicate value entry for environment 'shared'"))
    }

    fun testVarBlankNameFlagged() {
        // A present-but-empty `name:` is as bad as a missing one.
        myFixture.enableInspections(VariableEnvInspection())
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: GenericHttpSmsGateway
              id: gw-1
              properties:
                originatorName:
                - value: Airlock
                  var:
                    name:
                    sensitive: true
            """.trimIndent(),
        )
        assertSize(1, myFixture.doHighlighting().byDescription("requires a 'name'"))
    }

    fun testDuplicateContextFlagged() {
        // The duplicate key is (env, context); two entries sharing the same context (no env) collide.
        myFixture.enableInspections(VariableEnvInspection())
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: SomePlugin
              id: p-1
              properties:
                url:
                - value: a
                  context: ctxA
                - value: b
                  context: ctxA
            """.trimIndent(),
        )
        assertSize(2, myFixture.doHighlighting().byDescription("Duplicate value entry"))
    }

    fun testSameEnvDifferentContextNotFlagged() {
        // (env, context) is the key: same env but distinct contexts are independent overrides.
        myFixture.enableInspections(VariableEnvInspection())
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: SomePlugin
              id: p-1
              properties:
                url:
                - value: a
                  env: prod
                  context: ctxA
                - value: b
                  env: prod
                  context: ctxB
            """.trimIndent(),
        )
        assertEmpty(myFixture.doHighlighting().byDescription("Duplicate value entry"))
    }

    fun testValueEntriesAcrossDifferentPropertiesNotFlagged() {
        // Duplicate detection is per value list: identical shared entries under DIFFERENT properties
        // are independent and must not collide.
        myFixture.enableInspections(VariableEnvInspection())
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: SomePlugin
              id: p-1
              properties:
                first:
                - value: a
                second:
                - value: b
            """.trimIndent(),
        )
        assertEmpty(myFixture.doHighlighting().byDescription("Duplicate value entry"))
    }

    fun testValueEntryUnknownKeyDoesNotFlagQualifiers() {
        // env/context/var are legal qualifiers alongside the value carrier and must never be flagged.
        myFixture.enableInspections(VariableEnvInspection())
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: SomePlugin
              id: p-1
              properties:
                url:
                - value: a
                  env: prod
                  context: ctxA
                  var:
                    name: U
            """.trimIndent(),
        )
        assertEmpty(myFixture.doHighlighting().byDescription("not a valid key in a value entry"))
    }

    fun testDistinctEnvNotFlagged() {
        myFixture.enableInspections(VariableEnvInspection())
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: SomePlugin
              id: p-1
              properties:
                url:
                - value: a
                - value: b
                  env: intellij
                - value: c
                  env: tilt
            """.trimIndent(),
        )
        assertEmpty(myFixture.doHighlighting().byDescription("Duplicate value entry"))
    }

    fun testMultipleValueFormsFlagged() {
        myFixture.enableInspections(VariableEnvInspection())
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: SomePlugin
              id: p-1
              properties:
                thing:
                - value: a
                  plugin:
                    type: SomeChild
            """.trimIndent(),
        )
        assertSize(2, myFixture.doHighlighting().byDescription("must have exactly one value form"))
    }

    fun testValueEntryUnknownKeyFlagged() {
        myFixture.enableInspections(VariableEnvInspection())
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: SomePlugin
              id: p-1
              properties:
                url:
                - value: a
                  bogus: y
            """.trimIndent(),
        )
        assertSize(1, myFixture.doHighlighting().byDescription("'bogus' is not a valid key in a value entry"))
    }

    fun testEnvVarOnValidConfigClean() {
        // The canonical multi-entry block from example-config: distinct envs, a sensitive var, storageId.
        myFixture.enableInspections(VariableEnvInspection())
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: GenericHttpSmsGateway
              id: gw-1
              properties:
                relyingPartyId:
                - value: replace_me.airlock.cloud
                  var:
                    name: HOST
                - value: manage.127.0.0.1.nip.io
                  env: intellij
                - value: manage.127.0.0.1.nip.io
                  env: tilt
                basicAuthPassword:
                - value: '[FILE]secrets/twilio-password'
                  var:
                    name: SMS_PASSWORD
                    sensitive: true
                secret:
                - value:
                    storageId: airlock2fa.encryption.secret
            """.trimIndent(),
        )
        val warnings = myFixture.doHighlighting().filter { it.severity >= HighlightSeverity.WEAK_WARNING }
        assertEmpty("a valid variable/environment config must produce no warnings", warnings)
    }

    fun testPluginListInnerItemsNotTreatedAsValueEntries() {
        // A pluginList whose items are `- ref:` / `- type:` must not be flagged as value entries.
        myFixture.enableInspections(VariableEnvInspection())
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: SomePlugin
              id: p-1
              properties:
                steps:
                - pluginList:
                  - ref: step-a
                  - ref: step-b
                  - type: SomeStep
                    id: step-c
            """.trimIndent(),
        )
        val warnings = myFixture.doHighlighting().filter { it.severity >= HighlightSeverity.WEAK_WARNING }
        assertEmpty("inner pluginList items must not be flagged", warnings)
    }

    fun testStorageIdValueNotFlagged() {
        myFixture.enableInspections(VariableEnvInspection())
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: SomePlugin
              id: p-1
              properties:
                secret:
                - value:
                    storageId: some.secret.id
            """.trimIndent(),
        )
        val warnings = myFixture.doHighlighting().filter { it.severity >= HighlightSeverity.WEAK_WARNING }
        assertEmpty("a storageId value entry must not be flagged", warnings)
    }

    fun testVarScalarValueFlagged() {
        // A `var:` whose value is a non-blank scalar is malformed (it has no `name:`).
        myFixture.enableInspections(VariableEnvInspection())
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: SomePlugin
              id: p-1
              properties:
                skin:
                - value: green
                  var: SKIN_COLOR
            """.trimIndent(),
        )
        assertSize(1, myFixture.doHighlighting().byDescription("requires a 'name'"))
    }

    fun testVarEmptyValueNotFlagged() {
        // An empty/mid-typing `var:` (null value) must not be flagged.
        myFixture.enableInspections(VariableEnvInspection())
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: SomePlugin
              id: p-1
              properties:
                skin:
                - value: green
                  var:
            """.trimIndent(),
        )
        assertEmpty(myFixture.doHighlighting().byDescription("requires a 'name'"))
    }

    fun testVarOutsideValueEntryNotFlagged() {
        // A `var:` mapping that is NOT a value-entry qualifier (e.g. a property literally named `var`
        // on a custom type) is outside the documented grammar and must not be validated.
        myFixture.enableInspections(VariableEnvInspection())
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: ZzzCustomPlugin
              id: p-1
              properties:
                var:
                  sensitive: true
            """.trimIndent(),
        )
        val warnings = myFixture.doHighlighting().filter { it.severity >= HighlightSeverity.WEAK_WARNING }
        assertEmpty("a var: that is not a value-entry qualifier must not be flagged", warnings)
    }

    fun testQualifierOnlyEntryFlagged() {
        // A sequence-item value entry carrying only an env qualifier (no value form) is malformed.
        myFixture.enableInspections(VariableEnvInspection())
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: SomePlugin
              id: p-1
              properties:
                url:
                - env: prod
            """.trimIndent(),
        )
        assertSize(1, myFixture.doHighlighting().byDescription("must have a value form"))
    }

    fun testQualifierOnlyVarEntryFlagged() {
        myFixture.enableInspections(VariableEnvInspection())
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: SomePlugin
              id: p-1
              properties:
                url:
                - var:
                    name: X
            """.trimIndent(),
        )
        assertSize(1, myFixture.doHighlighting().byDescription("must have a value form"))
    }

    fun testPluginListItemsNotFlaggedAsMissingValueForm() {
        // A plain pluginList/spec item (type:/ref:, no qualifier) is not a value entry and must not be
        // flagged for a missing value form.
        myFixture.enableInspections(VariableEnvInspection())
        myFixture.configureByText(
            "config.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: SomePlugin
              id: p-1
              properties:
                steps:
                - pluginList:
                  - ref: step-a
                  - type: SomeStep
                    id: step-c
            """.trimIndent(),
        )
        assertEmpty(myFixture.doHighlighting().byDescription("must have a value form"))
    }

    fun testAllValueFormsProduceNoFalsePositives() {
        // Improvement #5: every value form from the YAML spec, under ALL inspections, clean.
        myFixture.enableInspections(
            DanglingReferenceInspection(),
            DuplicateIdInspection(),
            UnknownPropertyInspection(),
            WrongChildTypeInspection(),
            VariableEnvInspection(),
        )
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
              properties:
                driverClass: org.postgresql.Driver
                url:
                - value: '[FILE]secrets/url'
                - value: jdbc:postgresql://localhost/db
                  env: intellij
                password:
                - value:
                    storageId: db.password
                  env: intellij
                - value: changeit
                  var:
                    name: DB_PASSWORD
                    sensitive: true
            - type: ZzzCustomPlugin
              id: custom-1
              properties:
                languages:
                - valueList:
                  - de
                  - en
                empty:
                - valueList: []
                link:
                - plugin: null
                otherLink:
                - plugin:
                    ref: db-1
                steps:
                - pluginList:
                  - ref: db-1
                gotos:
                - pluginMap:
                    SOME_KEY:
                      type: ZzzCustomChild
                      id: child-1
                shared:
                - value: x
                  context: ctxA
                - value: y
                  context: ctxB
            """.trimIndent(),
        )
        val warnings = myFixture.doHighlighting().filter { it.severity >= HighlightSeverity.WEAK_WARNING }
        assertEmpty(
            "no inspection may misfire on any spec value form:\n" + warnings.joinToString("\n") { it.description.orEmpty() },
            warnings,
        )
    }

    private fun List<HighlightInfo>.byDescription(needle: String): List<HighlightInfo> =
        filter { it.description?.contains(needle) == true }
}
