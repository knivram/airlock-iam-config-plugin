package me.knivram.iamplugin.inspection

import me.knivram.iamplugin.AirlockBundle
import me.knivram.iamplugin.catalog.AirlockPluginCatalog
import me.knivram.iamplugin.psi.IamConfigPsi
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Warns when a property the catalog marks `sensitive` (e.g. `AES256DecryptionTicketDecoder.password`)
 * holds a literal plain-text value instead of an externalized/obfuscated form.
 *
 * A secret committed verbatim into a config is the smell this nudges away from; the gentle
 * WEAK_WARNING severity reflects that dev/example configs legitimately carry literals. The accepted,
 * un-flagged forms are: a configuration variable (`var:`), an external secret (`storageId:`), a
 * `[FILE]…` reference, and an `[OBFUSCATED]…` value.
 *
 * Both shapes a sensitive value can take are covered, each gated on [IamConfigPsi.isIamConfigFile]:
 *  - a direct scalar property value — a [YAMLKeyValue] that is a direct child of a `properties:`
 *    mapping whose enclosing plugin type is known and whose catalog property is sensitive; and
 *  - a value-entry carrier — a `value:` key inside a value-entry ([IamConfigPsi.isValueEntry]) whose
 *    enclosing property (resolved via [IamConfigPsi.slotOf]) is sensitive.
 *
 * Scoped to KNOWN types with a sensitive catalog property, so custom/unknown plugins are never
 * flagged. Blank/missing/non-scalar values and storageId/var forms are left untouched. No quick-fix.
 */
class PlaintextSecretInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file as? YAMLFile ?: return PsiElementVisitor.EMPTY_VISITOR
        if (!IamConfigPsi.isIamConfigFile(file)) return PsiElementVisitor.EMPTY_VISITOR
        val catalog = AirlockPluginCatalog.getInstance()

        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element !is YAMLKeyValue) return
                // (b) a value-entry carrier: `- value: <scalar>` inside a value-entry. A `value:` key that
                // is NOT a value-entry carrier (e.g. a plugin type whose own property is literally named
                // `value`, such as StaticSensitiveHttpParameter) falls through to the direct-property check.
                if (element.keyText == "value" && checkValueEntryCarrier(element)) return
                // (a) a direct scalar property value under `properties:`.
                checkDirectProperty(element)
            }

            /** (a) `<property>: <scalar>` where the property is a sensitive scalar slot. */
            private fun checkDirectProperty(propertyKv: YAMLKeyValue) {
                if (!IamConfigPsi.isDirectChildOfProperties(propertyKv)) return
                val propertiesKv = propertyKv.parentMapping?.parent as? YAMLKeyValue ?: return
                val type = IamConfigPsi.enclosingPluginType(propertiesKv) ?: return
                if (!isSensitive(type, propertyKv.keyText)) return
                flagIfPlainScalar(propertyKv, propertyKv.keyText)
            }

            /**
             * (b) `- value: <scalar>` where the entry's enclosing property is a sensitive slot. Returns true
             * if [valueKv] is a genuine value-entry carrier (and was handled here), false if it is some other
             * `value:` key the caller should treat as a direct property.
             */
            private fun checkValueEntryCarrier(valueKv: YAMLKeyValue): Boolean {
                val entry = valueKv.parentMapping ?: return false
                if (!IamConfigPsi.isValueEntry(entry)) return false
                // An entry carrying a `var:` qualifier externalizes its value; never flag it.
                if (entry.getKeyValueByKey(IamConfigPsi.VAR_KEY) != null) return true
                val slot = IamConfigPsi.slotOf(entry) ?: return true
                if (!isSensitive(slot.parentType, slot.propertyName)) return true
                flagIfPlainScalar(valueKv, slot.propertyName)
                return true
            }

            private fun isSensitive(type: String, propertyName: String): Boolean {
                if (!catalog.isKnownType(type)) return false
                return catalog.property(type, propertyName)?.sensitive == true
            }

            /**
             * Registers the warning when [carrierKv]'s value is an inline, non-blank scalar that is not
             * already an `[OBFUSCATED]`/`[FILE]` reference. A `storageId:` mapping, a blank/missing value
             * and any non-scalar value (a nested mapping/sequence) are externalized forms and skipped.
             */
            private fun flagIfPlainScalar(carrierKv: YAMLKeyValue, propertyName: String) {
                val scalar = carrierKv.value as? YAMLScalar ?: return
                val text = scalar.textValue
                if (text.isBlank()) return
                if (text.startsWith("[OBFUSCATED]") || text.startsWith("[FILE]")) return
                holder.registerProblem(
                    scalar,
                    AirlockBundle.message("inspection.plaintextSecret.message", propertyName),
                    ProblemHighlightType.WEAK_WARNING,
                )
            }
        }
    }
}
