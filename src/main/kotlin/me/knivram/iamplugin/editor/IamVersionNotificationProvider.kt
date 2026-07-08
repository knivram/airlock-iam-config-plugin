package me.knivram.iamplugin.editor

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.editor.impl.EditorHeaderComponent
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.ui.EditorNotificationProvider
import com.intellij.ui.EditorNotifications
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import me.knivram.iamplugin.AirlockBundle
import me.knivram.iamplugin.catalog.AirlockPluginCatalog
import me.knivram.iamplugin.catalog.CatalogResolution
import me.knivram.iamplugin.catalog.VersionProvenance
import me.knivram.iamplugin.psi.IamConfigPsi
import me.knivram.iamplugin.settings.IamVersionOverrides
import org.jetbrains.yaml.psi.YAMLFile
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.util.function.Function
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

/** Which header (if any) an IAM config file gets for its version resolution. */
internal enum class BannerKind {
    /**
     * The file's `metadata.iamVersion` cannot be honoured — it names a version with no bundled
     * catalog or cannot be parsed. One user-facing case: the declared version isn't usable and a
     * fallback catalog answers instead.
     */
    UNSUPPORTED_MARKER,

    /** The file declares no version at all; the default catalog answers (suppressible). */
    NO_MARKER,

    /** A manual per-file selection is active; shown so it stays visible and clearable. */
    OVERRIDE_ACTIVE,
}

internal data class BannerSpec(val kind: BannerKind, val requestedRaw: String?, val resolvedVersion: String)

/** The greyed hint shown beside the dropdown to name what auto-detect resolved to. */
internal enum class VersionHint { NONE, FALLBACK, DEFAULT }

/**
 * True when the dropdown reads "Auto-detect" (no manual override in effect); false when it names a
 * manually selected version. Auto-detect covers both the markerless and the unsupported-marker
 * cases — in both, resolution is still automatic; the [versionHint] explains where it landed.
 */
internal val BannerSpec.isAutoDetect: Boolean
    get() = kind != BannerKind.OVERRIDE_ACTIVE

/** Which resolved-version hint (if any) sits beside the dropdown for this header. */
internal val BannerSpec.versionHint: VersionHint
    get() = when (kind) {
        BannerKind.UNSUPPORTED_MARKER -> VersionHint.FALLBACK
        BannerKind.NO_MARKER -> VersionHint.DEFAULT
        BannerKind.OVERRIDE_ACTIVE -> VersionHint.NONE
    }

/**
 * The pure header decision: no header for an exact marker match; one for markers that cannot be
 * honoured (unbundled or unparseable — one user-facing case) and for markerless files (the
 * latter suppressible); one while a manual override is active (the only UI where the override is
 * visible and clearable, so it is deliberately NOT suppressible).
 */
internal fun bannerFor(resolution: CatalogResolution, suppressNoVersion: Boolean): BannerSpec? {
    val resolved = resolution.catalog.version
    return when (resolution.provenance) {
        VersionProvenance.OVERRIDE ->
            BannerSpec(BannerKind.OVERRIDE_ACTIVE, resolution.requestedRaw, resolved)

        VersionProvenance.MARKER ->
            if (resolution.exactMatch) null
            else BannerSpec(BannerKind.UNSUPPORTED_MARKER, resolution.requestedRaw, resolved)

        VersionProvenance.DEFAULT ->
            if (suppressNoVersion) null else BannerSpec(BannerKind.NO_MARKER, null, resolved)
    }
}

/**
 * SQL-console-style editor header shown whenever the catalog validating the file is not simply
 * "the version the file declares": no `metadata.iamVersion`, an unsupported/unreadable one, or an
 * active manual selection. An info label explains why; a [ComboBoxAction] dropdown (the same
 * widget as the SQL console's schema switcher) reads "Auto-detect" (or the manually selected
 * version) and switches it — a choice is a per-file, per-developer override, never written into
 * the YAML; "Auto-detect" clears it. While auto-detecting, a greyed hint beside the dropdown
 * names the version resolution actually landed on (the fallback, or the default).
 */
class IamVersionNotificationProvider : EditorNotificationProvider {

    override fun collectNotificationData(
        project: Project,
        file: VirtualFile,
    ): Function<in FileEditor, out JComponent?>? {
        val psi = PsiManager.getInstance(project).findFile(file) as? YAMLFile ?: return null
        if (!IamConfigPsi.isIamConfigFile(psi)) return null

        val resolution = AirlockPluginCatalog.resolve(psi)
        val suppress = IamVersionOverrides.getInstance(project).suppressNoVersionBanner
        val spec = bannerFor(resolution, suppress) ?: return null
        return Function { buildHeader(project, file, spec) }
    }

    private fun buildHeader(project: Project, file: VirtualFile, spec: BannerSpec): JComponent {
        val header = EditorHeaderComponent()
        header.layout = BorderLayout()

        val message = when (spec.kind) {
            BannerKind.UNSUPPORTED_MARKER ->
                AirlockBundle.message("banner.unsupportedVersion", spec.requestedRaw.orEmpty())

            BannerKind.NO_MARKER -> AirlockBundle.message("banner.noVersion")

            BannerKind.OVERRIDE_ACTIVE -> AirlockBundle.message("banner.overrideActive")
        }
        val label = JBLabel(message, AllIcons.General.Information, SwingConstants.LEADING)
        label.border = JBUI.Borders.empty(4, 8)
        header.add(label, BorderLayout.WEST)

        val actions = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0))
        actions.isOpaque = false

        val hintText = when (spec.versionHint) {
            VersionHint.FALLBACK -> AirlockBundle.message("banner.hint.fallback", spec.resolvedVersion)
            VersionHint.DEFAULT -> AirlockBundle.message("banner.hint.default", spec.resolvedVersion)
            VersionHint.NONE -> null
        }
        if (hintText != null) {
            val hint = JBLabel(hintText)
            hint.componentStyle = UIUtil.ComponentStyle.SMALL
            hint.foreground = UIUtil.getContextHelpForeground()
            actions.add(hint)
        }

        val dropdownText = if (spec.isAutoDetect) {
            AirlockBundle.message("banner.dropdown.autoShort")
        } else {
            AirlockBundle.message("banner.dropdown.label", spec.resolvedVersion)
        }
        val toolbar = ActionManager.getInstance().createActionToolbar(
            TOOLBAR_PLACE,
            DefaultActionGroup(VersionComboAction(project, file, dropdownText)),
            true,
        )
        toolbar.targetComponent = header
        toolbar.component.isOpaque = false
        actions.add(toolbar.component)
        if (spec.kind == BannerKind.NO_MARKER) {
            actions.add(
                ActionLink(AirlockBundle.message("banner.action.dontShowAgain")) {
                    IamVersionOverrides.getInstance(project).suppressNoVersionBanner = true
                    EditorNotifications.getInstance(project).updateAllNotifications()
                },
            )
        }
        header.add(actions, BorderLayout.EAST)
        return header
    }

    /**
     * The version dropdown: labelled "Auto-detect" (no override) or the manually selected version;
     * its popup offers "Auto-detect" (clears the override, back to marker/default resolution) and
     * every bundled version.
     */
    private inner class VersionComboAction(
        private val project: Project,
        private val file: VirtualFile,
        buttonText: String,
    ) : ComboBoxAction() {

        init {
            setSmallVariant(true)
            templatePresentation.text = buttonText
        }

        override fun createPopupActionGroup(button: JComponent, dataContext: DataContext): DefaultActionGroup {
            val group = DefaultActionGroup()
            group.add(object : AnAction(AirlockBundle.message("banner.dropdown.auto")) {
                override fun actionPerformed(e: AnActionEvent) = select(null)
            })
            group.addSeparator()
            for (version in AirlockPluginCatalog.getInstance().bundledVersions) {
                group.add(object : AnAction(AirlockBundle.message("banner.dropdown.label", version)) {
                    override fun actionPerformed(e: AnActionEvent) = select(version)
                })
            }
            return group
        }

        private fun select(version: String?) {
            IamVersionOverrides.getInstance(project).set(file, version)
            refresh(project, file)
        }
    }

    /** Re-evaluate the header and re-run highlighting: the override changes what validates. */
    private fun refresh(project: Project, file: VirtualFile) {
        EditorNotifications.getInstance(project).updateNotifications(file)
        PsiManager.getInstance(project).findFile(file)?.let {
            DaemonCodeAnalyzer.getInstance(project).restart(it)
        }
    }

    private companion object {
        private const val TOOLBAR_PLACE = "AirlockIamVersionHeader"
    }
}
