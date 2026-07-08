package me.knivram.iamplugin.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.yaml.psi.YAMLFile
import java.io.File

/**
 * Per-file IAM version selections made in the editor banner, plus the banner suppress flag.
 *
 * Deliberately stored in the WORKSPACE file (per developer, not VCS-shared): an override is
 * personal IDE state saying "validate this file as version X", not a property of the config —
 * the shareable way to declare a version is the file's own `metadata.iamVersion`.
 */
@Service(Service.Level.PROJECT)
@State(name = "AirlockIamVersionOverrides", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class IamVersionOverrides : SimplePersistentStateComponent<IamVersionOverrides.State>(State()) {

    class State : BaseState() {
        /** File URL → selected IAM version. */
        var overrides by map<String, String>()

        /** Suppresses only the "no version declared" info banner, never the fallback warnings. */
        var suppressNoVersionBanner by property(false)
    }

    override fun loadState(state: State) {
        // Prune overrides of local files that no longer exist. Plain-path check on file:// URLs
        // only — non-file protocols (in-memory test files etc.) are never pruned.
        val stale = state.overrides.keys.filter { url ->
            url.startsWith(LOCAL_FILE_PREFIX) && !File(url.removePrefix(LOCAL_FILE_PREFIX)).exists()
        }
        stale.forEach { state.overrides.remove(it) }
        super.loadState(state)
    }

    /** [file]'s selected version, or null when none. Resolves through `originalFile` so the
     *  in-memory copies completion works on see the same override as the real file. */
    fun versionFor(file: YAMLFile): String? =
        file.originalFile.virtualFile?.let { versionFor(it) }

    fun versionFor(file: VirtualFile): String? = state.overrides[file.url]

    /** Select [version] for [file]; null clears the selection. */
    fun set(file: VirtualFile, version: String?) {
        if (version == null) state.overrides.remove(file.url) else state.overrides[file.url] = version
    }

    var suppressNoVersionBanner: Boolean
        get() = state.suppressNoVersionBanner
        set(value) {
            state.suppressNoVersionBanner = value
        }

    companion object {
        private const val LOCAL_FILE_PREFIX = "file://"

        fun getInstance(project: Project): IamVersionOverrides = project.service()
    }
}
