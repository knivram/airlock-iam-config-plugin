package com.github.knivram.airlockiamconfigplugin

import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.testFramework.fixtures.BasePlatformTestCase

private const val DB_ID = "f0d2a309-8eb4-4574-a622-503d6e5f47be"
private const val POLICY_ID = "658b3b83-f638-45ff-a433-1e130c847e0c"

class CompletionTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    fun testRefCompletionListsPluginIds() {
        myFixture.configureByFile("refComplete.yaml")
        myFixture.completeBasic()
        val lookups = myFixture.lookupElementStrings
        assertNotNull("ref completion should offer a list", lookups)
        assertContainsElements(lookups!!, DB_ID, POLICY_ID)
    }

    fun testRefCompletionPresentsDisplayNameButInsertsId() {
        // Core contract: the lookup STRING (what is inserted) is the id; the PRESENTED text is the displayName.
        myFixture.configureByFile("refComplete.yaml")
        myFixture.completeBasic()
        val presented = myFixture.lookupElements!!.map { element ->
            val p = LookupElementPresentation()
            element.renderElement(p)
            element.lookupString to p.itemText
        }
        assertContainsElements(presented, DB_ID to "PostgreSQL Database Connection")
    }

    fun testRefCompletionByDisplayNameInsertsTheId() {
        // Typing the displayName matches, but the id (not the displayName) ends up in the document.
        myFixture.configureByText(
            "c.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: SimplePasswordPolicy
              id: $POLICY_ID
              displayName: Default Password Policy
            - type: Consumer
              id: consumer-1
              properties:
                policy:
                  ref: Default<caret>
            """.trimIndent(),
        )
        myFixture.completeBasic() // single match ("Default Password Policy") auto-inserts
        assertTrue(
            "completion must insert the id, not the displayName",
            myFixture.editor.document.text.contains("ref: $POLICY_ID"),
        )
    }

    fun testRefCompletionIsSlotScopedToAssignableTypes() {
        // passwordPolicy is a Plugin-Link slot whose assignable set excludes JdbcConnectionPool.
        // Ref completion in that slot must offer only the assignable file plugin, not the Jdbc one.
        myFixture.configureByText(
            "c.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: SimplePasswordPolicy
              id: $POLICY_ID
              displayName: Policy
            - type: JdbcConnectionPool
              id: $DB_ID
              displayName: DB
            - type: PasswordSettings
              id: pw-1
              properties:
                passwordPolicy:
                  ref: <caret>
            """.trimIndent(),
        )
        myFixture.completeBasic()
        val lookups = myFixture.lookupElementStrings
        assertNotNull("slot-scoped ref completion should offer a list", lookups)
        assertContainsElements(lookups!!, POLICY_ID)
        assertFalse(
            "a file plugin whose type is not assignable to the slot must not be offered",
            lookups.contains(DB_ID),
        )
    }

    fun testRefCompletionFallsBackToAllWhenSlotUnknown() {
        // parentRef on a top-level spec item has no resolvable slot; all file plugins are offered.
        myFixture.configureByText(
            "c.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: SimplePasswordPolicy
              id: $POLICY_ID
              displayName: Policy
            - type: JdbcConnectionPool
              id: $DB_ID
              displayName: DB
            - type: PasswordSettings
              id: pw-1
              parentRef: <caret>
            """.trimIndent(),
        )
        myFixture.completeBasic()
        val lookups = myFixture.lookupElementStrings
        assertNotNull(lookups)
        assertContainsElements(lookups!!, POLICY_ID, DB_ID)
    }

    fun testRefCompletionFallsBackWhenSlotHasNoAssignableList() {
        // smsGateway-style: a plugin-valued slot whose catalog property has no assignable list must
        // fall back to all file plugins (never silently offer nothing).
        myFixture.configureByText(
            "c.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: JdbcConnectionPool
              id: $DB_ID
              displayName: DB
            - type: ZzzCustomParent
              id: parent-1
              properties:
                someUnknownSlot:
                  ref: <caret>
            """.trimIndent(),
        )
        myFixture.completeBasic()
        val lookups = myFixture.lookupElementStrings
        assertNotNull(lookups)
        assertContainsElements(lookups!!, DB_ID)
    }

    fun testTypeCompletionOffersCatalogTypes() {
        // A typed prefix narrows the (1500+) catalog; "Jdbc" matches JdbcConnectionPool & JdbcDriverProperty.
        myFixture.configureByText(
            "c.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: Jdbc<caret>
            """.trimIndent(),
        )
        myFixture.completeBasic()
        val lookups = myFixture.lookupElementStrings
        assertNotNull("type completion should offer a list", lookups)
        assertContainsElements(lookups!!, "JdbcConnectionPool")
    }

    fun testTypeCompletionOffersProjectTypes() {
        // Custom types not in the bundled catalog must still be offered, sourced from the project index.
        myFixture.configureByText(
            "c.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: ZzzCustomAlpha
              id: a
            - type: ZzzCustomBeta
              id: b
            - type: Zzz<caret>
            """.trimIndent(),
        )
        myFixture.completeBasic()
        val lookups = myFixture.lookupElementStrings
        assertNotNull("type completion should offer a list", lookups)
        assertContainsElements(lookups!!, "ZzzCustomAlpha", "ZzzCustomBeta")
    }

    fun testPropertyNameCompletionOffersSchemaKeys() {
        myFixture.configureByText(
            "c.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: JdbcConnectionPool
              id: db-1
              properties:
                <caret>
            """.trimIndent(),
        )
        myFixture.completeBasic()
        val lookups = myFixture.lookupElementStrings
        assertNotNull("property-name completion should offer a list", lookups)
        assertContainsElements(lookups!!, "url", "maximumPoolSize", "driverClass")
    }

    fun testPropertyNameCompletionHidesAlreadyPresentKeys() {
        myFixture.configureByText(
            "c.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: JdbcConnectionPool
              id: db-1
              properties:
                url: jdbc:postgresql://localhost/db
                u<caret>
            """.trimIndent(),
        )
        myFixture.completeBasic()
        val lookups = myFixture.lookupElementStrings
        assertNotNull(lookups)
        assertFalse("an already-present key must not be re-offered", lookups!!.contains("url"))
        assertContainsElements(lookups, "user") // a different, still-absent key remains
    }

    fun testPropertyNameCompletionSuppressedForUnknownType() {
        myFixture.configureByText(
            "c.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: ZzzCustomUnknownPlugin
              id: x
              properties:
                <caret>
            """.trimIndent(),
        )
        myFixture.completeBasic()
        myFixture.lookupElementStrings?.let {
            assertFalse("no schema keys for a custom type", it.contains("url"))
        }
    }

    fun testSlotScopedTypeCompletionOffersAssignableAndExcludesNonAssignable() {
        // passwordPolicy is a Plugin-Link slot of PasswordSettings; only its assignable types are offered.
        myFixture.configureByText(
            "c.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: PasswordSettings
              id: pw-1
              properties:
                passwordPolicy:
                  type: <caret>
            """.trimIndent(),
        )
        myFixture.completeBasic()
        val lookups = myFixture.lookupElementStrings
        assertNotNull("slot-scoped type completion should offer a list", lookups)
        assertContainsElements(lookups!!, "SimplePasswordPolicy")
        assertFalse(
            "a non-assignable catalog type must not be offered in this slot",
            lookups.contains("JdbcConnectionPool"),
        )
    }

    // --- Plugin-definition root keys (Improvement #2) ---

    fun testPluginRootKeyCompletionInSpecItem() {
        myFixture.configureByText(
            "c.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - <caret>
            """.trimIndent(),
        )
        myFixture.completeBasic()
        val lookups = myFixture.lookupElementStrings
        assertNotNull("root-key completion should offer a list in a spec item", lookups)
        assertContainsElements(lookups!!, "type", "id", "displayName", "comment", "parentRef", "properties")
    }

    fun testPluginRootKeyCompletionInSlotValueDedupesAndOffersRef() {
        // A slot value mapping that already has `type:` must not re-offer it, but should offer the rest.
        myFixture.configureByText(
            "c.yaml",
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
                  <caret>
            """.trimIndent(),
        )
        myFixture.completeBasic()
        val lookups = myFixture.lookupElementStrings
        assertNotNull(lookups)
        assertFalse("an already-present root key must not be re-offered", lookups!!.contains("type"))
        assertContainsElements(lookups, "id", "displayName", "comment", "parentRef", "properties")
        assertFalse("ref is not offered once type: is present", lookups.contains("ref"))
    }

    fun testPluginRootKeyCompletionInPluginListItem() {
        myFixture.configureByText(
            "c.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: AuthenticationFlow
              id: flow-1
              properties:
                steps:
                - pluginList:
                  - <caret>
            """.trimIndent(),
        )
        myFixture.completeBasic()
        val lookups = myFixture.lookupElementStrings
        assertNotNull("root-key completion should offer a list in a pluginList item", lookups)
        assertContainsElements(lookups!!, "type", "id", "displayName", "comment", "parentRef", "properties", "ref")
    }

    fun testPluginRootKeyCompletionInPluginMapValue() {
        myFixture.configureByText(
            "c.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: SomeType
              id: x-1
              properties:
                onFailureGotos:
                - pluginMap:
                    SOME_KEY:
                      <caret>
            """.trimIndent(),
        )
        myFixture.completeBasic()
        val lookups = myFixture.lookupElementStrings
        assertNotNull("root-key completion should offer a list in a pluginMap value", lookups)
        assertContainsElements(lookups!!, "type", "id", "displayName", "comment", "parentRef", "properties")
    }

    fun testPluginRootKeyNotOfferedInsidePropertiesMapping() {
        // Inside `properties:` we want property NAMES (from the catalog), not plugin root keys.
        myFixture.configureByText(
            "c.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: JdbcConnectionPool
              id: db-1
              properties:
                <caret>
            """.trimIndent(),
        )
        myFixture.completeBasic()
        val lookups = myFixture.lookupElementStrings
        assertNotNull(lookups)
        assertContainsElements(lookups!!, "url", "driverClass")
        assertFalse("plugin root key `displayName` must not be offered inside properties:", lookups.contains("displayName"))
    }

    fun testPluginRootKeyNotOfferedWhenRefPresent() {
        // A link mapping (has `ref:`) carries no definition body keys.
        myFixture.configureByText(
            "c.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: PasswordSettings
              id: pw-1
              properties:
                passwordPolicy:
                  ref: some-id
                  <caret>
            """.trimIndent(),
        )
        myFixture.completeBasic()
        myFixture.lookupElementStrings?.let {
            assertFalse("no root keys are offered next to a ref:", it.contains("type"))
            assertFalse(it.contains("properties"))
        }
    }

    fun testPluginRootKeyCompletionInEmptyPluginValuedSlot() {
        // passwordPolicy is a Plugin-Link slot; an empty slot offers the direct-mapping root keys.
        myFixture.configureByText(
            "c.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: PasswordSettings
              id: pw-1
              properties:
                passwordPolicy:
                  <caret>
            """.trimIndent(),
        )
        myFixture.completeBasic()
        val lookups = myFixture.lookupElementStrings
        assertNotNull("empty plugin-valued slot should offer root keys", lookups)
        assertContainsElements(lookups!!, "type", "ref")
    }

    fun testPluginRootKeyCompletionInEmptyPluginWrapper() {
        // An empty `- plugin:` value-entry wrapper's inner mapping is a plugin; offer its root keys.
        myFixture.configureByText(
            "c.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: SomePlugin
              id: p-1
              properties:
                otpGenerator:
                - plugin:
                    <caret>
            """.trimIndent(),
        )
        myFixture.completeBasic()
        val lookups = myFixture.lookupElementStrings
        assertNotNull("empty plugin wrapper should offer root keys", lookups)
        assertContainsElements(lookups!!, "type", "ref", "id", "displayName", "comment", "parentRef", "properties")
    }

    fun testRefCompletionFallsBackWhenAssignableListIsEmpty() {
        // Adminapp.customExtensions is a Plugin-List slot whose catalog assignable list is EMPTY;
        // ref completion must fall back to all file plugins, never offer nothing.
        myFixture.configureByText(
            "c.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: JdbcConnectionPool
              id: $DB_ID
              displayName: DB
            - type: Adminapp
              id: admin-1
              properties:
                customExtensions:
                  ref: <caret>
            """.trimIndent(),
        )
        myFixture.completeBasic()
        val lookups = myFixture.lookupElementStrings
        assertNotNull(lookups)
        assertContainsElements(lookups!!, DB_ID)
    }

    fun testTypeCompletionFallsBackWhenAssignableListIsEmpty() {
        // Same empty-assignable slot: type completion must offer the full catalog, not nothing.
        myFixture.configureByText(
            "c.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: Adminapp
              id: admin-1
              properties:
                customExtensions:
                - pluginList:
                  - type: Jdbc<caret>
            """.trimIndent(),
        )
        myFixture.completeBasic()
        val lookups = myFixture.lookupElementStrings
        assertNotNull("type completion should fall back to the full catalog", lookups)
        assertContainsElements(lookups!!, "JdbcConnectionPool")
    }

    // --- Plugin-value wrapper scaffolds (Improvement #3) ---

    fun testScaffoldPluginListInsertsWrapper() {
        // `steps` on AuthenticationFlow is a Plugin-List slot.
        myFixture.configureByText(
            "c.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: AuthenticationFlow
              id: flow-1
              properties:
                steps: pluginLi<caret>
            """.trimIndent(),
        )
        myFixture.completeBasic()
        assertTrue(
            "pluginList scaffold should insert the wrapper skeleton:\n${myFixture.editor.document.text}",
            myFixture.editor.document.text.contains("steps:\n    - pluginList:\n      - type: "),
        )
    }

    fun testScaffoldOnFreshLineDoesNotLeaveBlankLine() {
        // Triggering the scaffold on a fresh, already-indented line under the key must NOT insert a blank
        // line between `steps:` and `- pluginList:` (it reuses the current line).
        myFixture.configureByText(
            "c.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: AuthenticationFlow
              id: flow-1
              properties:
                steps:
                  pluginLi<caret>
            """.trimIndent(),
        )
        myFixture.completeBasic()
        val text = myFixture.editor.document.text
        assertTrue(
            "scaffold reuses the fresh line (dash at the key's column, no blank line):\n$text",
            text.contains("steps:\n    - pluginList:\n      - type: "),
        )
        assertFalse("no blank line between the key and the wrapper:\n$text", text.contains("steps:\n\n"))
    }

    fun testScaffoldPluginMapInsertsWrapper() {
        myFixture.configureByText(
            "c.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: Airlock2FAActivationStep
              id: step-1
              properties:
                onFailureGotos: pluginMa<caret>
            """.trimIndent(),
        )
        myFixture.completeBasic()
        val text = myFixture.editor.document.text
        assertTrue(
            "pluginMap scaffold should insert the wrapper skeleton:\n$text",
            text.contains("onFailureGotos:\n    - pluginMap:\n      key:\n        type: "),
        )
    }

    fun testScaffoldPluginLinkInsertsWrapper() {
        myFixture.configureByText(
            "c.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: PasswordSettings
              id: pw-1
              properties:
                passwordPolicy: plug<caret>
            """.trimIndent(),
        )
        myFixture.completeBasic()
        val text = myFixture.editor.document.text
        assertTrue(
            "plugin scaffold should insert the wrapper skeleton:\n$text",
            text.contains("passwordPolicy:\n    - plugin:\n      type: "),
        )
    }

    fun testScaffoldNotOfferedForScalarProperty() {
        // `driverClass` is a String property; no plugin-wrapper scaffold must be offered.
        myFixture.configureByText(
            "c.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: JdbcConnectionPool
              id: db-1
              properties:
                driverClass: <caret>
            """.trimIndent(),
        )
        myFixture.completeBasic()
        myFixture.lookupElementStrings?.let {
            assertFalse("no scaffold for a scalar property", it.contains("pluginList"))
            assertFalse(it.contains("pluginMap"))
            assertFalse(it.contains("plugin"))
        }
    }

    fun testScaffoldNotOfferedForUnknownType() {
        myFixture.configureByText(
            "c.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: ZzzCustomUnknownPlugin
              id: x
              properties:
                whatever: <caret>
            """.trimIndent(),
        )
        myFixture.completeBasic()
        myFixture.lookupElementStrings?.let {
            assertFalse("no scaffold for an unknown plugin type", it.contains("pluginList"))
        }
    }

    // --- Variables & environments (Improvement #4) ---

    fun testEnvCompletionOffersUsedEnvNames() {
        myFixture.configureByText(
            "c.yaml",
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
                  env: intellij
                - value: b
                  env: tilt
                - value: c
                  env: <caret>
            """.trimIndent(),
        )
        myFixture.completeBasic()
        val lookups = myFixture.lookupElementStrings
        assertNotNull("env completion should offer a list", lookups)
        assertContainsElements(lookups!!, "intellij", "tilt")
        assertFalse("env-value completion must not inject catalog plugin types", lookups.contains("JdbcConnectionPool"))
        assertFalse("env-value completion must not inject property names", lookups.contains("url"))
    }

    fun testEnvNamesNotOfferedOutsideEnvValue() {
        // Harvested env names must be offered ONLY at an `env:` value position, not at a `value:` scalar.
        myFixture.configureByText(
            "c.yaml",
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
                  env: intellij
                - value: <caret>
                  env: tilt
            """.trimIndent(),
        )
        myFixture.completeBasic()
        myFixture.lookupElementStrings?.let {
            assertFalse("harvested env names must not be offered at a non-env value position", it.contains("intellij"))
        }
    }

    fun testVarKeyCompletionOffersNameSensitive() {
        myFixture.configureByText(
            "c.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: SomePlugin
              id: p-1
              properties:
                originatorName:
                - value: Airlock
                  var:
                    <caret>
            """.trimIndent(),
        )
        myFixture.completeBasic()
        val lookups = myFixture.lookupElementStrings
        assertNotNull("var-key completion should offer a list", lookups)
        assertContainsElements(lookups!!, "name", "sensitive")
    }

    fun testVarKeyCompletionHidesPresentKeys() {
        myFixture.configureByText(
            "c.yaml",
            """
            schemaVersion: iam.airlock.com/v1.1
            type: IamConfig
            metadata: {}
            spec:
            - type: SomePlugin
              id: p-1
              properties:
                originatorName:
                - value: Airlock
                  var:
                    name: ORIGINATOR
                    <caret>
            """.trimIndent(),
        )
        myFixture.completeBasic()
        val lookups = myFixture.lookupElementStrings
        assertNotNull(lookups)
        assertFalse("an already-present var key must not be re-offered", lookups!!.contains("name"))
        assertContainsElements(lookups, "sensitive")
    }

    fun testNoCompletionInNonIamConfigFile() {
        // ref position in a non-IamConfig file must NOT offer project plugin ids.
        myFixture.configureByText(
            "ref.yaml",
            """
            type: SomethingElse
            spec:
            - ref: <caret>
            """.trimIndent(),
        )
        myFixture.completeBasic()
        myFixture.lookupElementStrings?.let {
            assertFalse("ref completion must be suppressed in non-IamConfig files", it.contains(DB_ID))
        }

        // type position in a non-IamConfig file must NOT offer the bundled catalog.
        myFixture.configureByText(
            "type.yaml",
            """
            type: SomethingElse
            spec:
            - type: Jdbc<caret>
            """.trimIndent(),
        )
        myFixture.completeBasic()
        myFixture.lookupElementStrings?.let {
            assertFalse("type completion must be suppressed in non-IamConfig files", it.contains("JdbcConnectionPool"))
        }
    }
}
