package me.knivram.iamplugin.inspection

import me.knivram.iamplugin.AirlockBundle
import me.knivram.iamplugin.catalog.AirlockPluginCatalog
import me.knivram.iamplugin.catalog.CatalogProperty
import me.knivram.iamplugin.catalog.PropertyKind
import me.knivram.iamplugin.psi.IamConfigFileModel
import me.knivram.iamplugin.psi.IamConfigPsi
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.modcommand.ModPsiUpdater
import com.intellij.modcommand.PsiUpdateModCommandQuickFix
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.YAMLElementGenerator
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping

/**
 * Flags a known-type plugin that omits a property the catalog marks Mandatory.
 *
 * Reported as an ERROR and enabled by default: the missing property is highlighted on the plugin's
 * `type:` (the way Kotlin highlights a class name when interface members are unimplemented), and a
 * quick-fix inserts the missing mandatory keys under `properties:` (creating that block when absent),
 * leaving each value empty and ready to fill.
 *
 * Caveat (accepted): in Airlock IAM mandatory-ness is sometimes conditional (a property is only
 * required given some other setting). The catalog flag is taken from the docs, so a few props that are
 * only conditionally required are still reported; this stricter behaviour is intentional.
 *
 * Conversely, an omitted Mandatory Plugin-Link is NOT flagged when its assignable type is configured as
 * a global default (a definition nested under `GlobalConfiguration`/`GlobalAuthenticationSettings`):
 * IAM auto-links the single global instance into such a slot, so omitting it is valid. The match is by
 * assignable TYPE (not property name), applies only to single Plugin-Link slots, and fires per-property
 * only when a global instance actually exists in the file — other genuinely-missing mandatory props on
 * the same plugin are still reported.
 *
 * Scoped to KNOWN types; the present keys are read from the plugin's own `properties:` mapping.
 */
class MissingMandatoryPropertyInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file as? YAMLFile ?: return PsiElementVisitor.EMPTY_VISITOR
        if (!IamConfigPsi.isIamConfigFile(file)) return PsiElementVisitor.EMPTY_VISITOR
        val catalog = AirlockPluginCatalog.forFile(file)
        val model = IamConfigFileModel.of(file)

        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element !is YAMLMapping) return
                // A `properties:` value-mapping holds property keys, not a plugin definition: a
                // property literally named `type` (e.g. `properties.type: Service`) must not be read as
                // a plugin's `type:`. Mirrors the model/other inspections' properties-direct exclusion.
                if (IamConfigPsi.isPropertiesMapping(element)) return
                // Only a plugin definition (has a type:) carries declarable properties.
                val type = element.getKeyValueByKey(IamConfigPsi.TYPE_KEY)?.valueText
                    ?.takeIf { it.isNotBlank() } ?: return
                if (!catalog.isKnownType(type)) return

                val mandatory = catalog.propertiesOf(type).filter { it.mandatory }
                if (mandatory.isEmpty()) return

                val propertiesMapping = (element.getKeyValueByKey(IamConfigPsi.PROPERTIES_KEY)?.value
                    as? YAMLMapping)
                val present: Set<String> = propertiesMapping
                    ?.keyValues
                    ?.mapNotNull { it.keyText.takeIf(String::isNotBlank) }
                    ?.toSet()
                    ?: emptySet()

                // A mandatory property is missing unless present, OR exempt: an omitted Mandatory
                // Plugin-Link whose assignable type is configured as a global default (under
                // GlobalConfiguration/GlobalAuthenticationSettings) is auto-linked by IAM, not missing.
                val missing = mandatory
                    .filter { it.name !in present && !isGlobalDefaultExempt(it) }
                    .map { it.name }
                if (missing.isEmpty()) return

                // Highlight just the type VALUE element (like Kotlin underlines the class name when
                // interface members are unimplemented), not the whole `type:` key-value. Mirrors
                // WrongChildTypeInspection's `.value` anchor. Falls back to the KV then the mapping if
                // the value node is somehow absent (unreachable here: a non-blank type was required above).
                val typeKv = element.getKeyValueByKey(IamConfigPsi.TYPE_KEY)
                val anchor: PsiElement = typeKv?.value ?: typeKv ?: element
                holder.registerProblem(
                    anchor,
                    AirlockBundle.message(
                        "inspection.missingMandatory.message",
                        type,
                        missing.joinToString(", "),
                    ),
                    ProblemHighlightType.ERROR,
                    AddMissingMandatoryPropertiesFix(missing),
                )
            }

            /**
             * True if [prop] is a single Plugin-Link to a type IAM auto-links from a global default: the
             * kind is Plugin-Link and at least one assignable type is both a catalog global-default type
             * and actually present as a global-scope definition in this file. Keyed by assignable TYPE.
             */
            private fun isGlobalDefaultExempt(prop: CatalogProperty): Boolean =
                prop.kind == PropertyKind.PLUGIN_LINK &&
                    prop.assignable.any { it in catalog.globalDefaultTypes && it in model.globalDefaults }
        }
    }
}

/**
 * Inserts the [missing] mandatory property keys under the plugin's `properties:` mapping, creating the
 * `properties:` block when absent. Each inserted key gets an empty value, ready to fill. Keys already
 * present are skipped (idempotent re-runs and the create-path seed). Generator-based PSI insertion is
 * used throughout so indentation stays correct.
 */
class AddMissingMandatoryPropertiesFix(private val missing: List<String>) : PsiUpdateModCommandQuickFix() {

    override fun getFamilyName(): String =
        AirlockBundle.message("inspection.missingMandatory.fix.familyName")

    override fun applyFix(project: Project, element: PsiElement, updater: ModPsiUpdater) {
        // `element` is the writable copy of the highlighted type VALUE element; its parent is the `type:`
        // key-value. Accept the fallback anchors too: the `type:` YAMLKeyValue directly, or the mapping.
        val pluginMapping = (element.parent as? YAMLKeyValue)?.parentMapping
            ?: (element as? YAMLKeyValue)?.parentMapping
            ?: element as? YAMLMapping
            ?: return
        val generator = YAMLElementGenerator.getInstance(project)

        val existingPropsValue = pluginMapping.getKeyValueByKey(IamConfigPsi.PROPERTIES_KEY)?.value
        val present: Set<String> = when (existingPropsValue) {
            is YAMLMapping -> existingPropsValue.keyValues
                .mapNotNull { it.keyText.takeIf(String::isNotBlank) }
                .toSet()
            else -> emptySet()
        }

        val toAdd = missing.filter { it !in present }
        if (toAdd.isEmpty()) return

        val propsMapping: YAMLMapping = when (existingPropsValue) {
            is YAMLMapping -> existingPropsValue
            else -> {
                // No (block-mapping) `properties:` yet. Build one from text with a single seeded child so
                // its value is a real block mapping; the remaining keys are appended afterwards. A plain
                // createYamlKeyValue("properties", "") would yield a scalar-valued key, which has no child
                // mapping to putKeyValue into. putKeyValue returns void, so re-fetch the inserted KV.
                val seed = toAdd.first()
                val dummy = generator.createDummyYamlWithText("${IamConfigPsi.PROPERTIES_KEY}:\n  $seed:")
                val newPropsKv = PsiTreeUtil.findChildOfType(dummy, YAMLKeyValue::class.java) ?: return
                pluginMapping.putKeyValue(newPropsKv)
                pluginMapping.getKeyValueByKey(IamConfigPsi.PROPERTIES_KEY)?.value as? YAMLMapping ?: return
            }
        }

        // Append every still-missing key (the create-path seed is already present in propsMapping).
        for (name in toAdd) {
            if (propsMapping.keyValues.any { it.keyText == name }) continue
            propsMapping.putKeyValue(generator.createYamlKeyValue(name, ""))
        }

        // Move the caret to the first added property (its empty value), ready to fill in.
        propsMapping.keyValues.firstOrNull { it.keyText == toAdd.first() }
            ?.let { updater.moveCaretTo(it.textRange.endOffset) }
    }
}
