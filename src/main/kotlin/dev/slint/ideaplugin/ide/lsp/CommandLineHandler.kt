package dev.slint.ideaplugin.ide.lsp

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.extensions.PluginDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.system.CpuArch
import dev.slint.ideaplugin.ide.settings.SlintBackend
import dev.slint.ideaplugin.ide.settings.SlintSettingsState
import dev.slint.ideaplugin.ide.settings.SlintStyle
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.getPosixFilePermissions
import kotlin.io.path.isExecutable
import kotlin.io.path.setPosixFilePermissions

object CommandLineHandler {
    @Volatile private var installationPath: Path? = null

    fun bindPlugin(descriptor: PluginDescriptor) { installationPath = descriptor.pluginPath }
    fun createCommandLine(project: Project, libraries: Map<String, String> = emptyMap()): GeneralCommandLine {
        val settingState = SlintSettingsState.getInstance(project).lspSettings

        val parameters = LibraryArguments.merge(settingState.args, settingState.includePaths,
            libraries, settingState.libraryOverrides).arguments.toMutableList()

        if (settingState.backend != SlintBackend.DEFAULT) {
            parameters.add("--backend")
            parameters.add(settingState.backend.toString())
        }

        if (settingState.style != SlintStyle.DEFAULT) {
            parameters.add("--style")
            parameters.add(settingState.style.toString())
        }

        if (settingState.noToolbar) {
            parameters.add("--no-toolbar")
        }

        val path = if (settingState.useExternalLsp) {
            settingState.path
        } else {
            getEmbeddedLspPath().toString()
        }

        return GeneralCommandLine(path).apply {
            addParameters(parameters)
            withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            withCharset(Charsets.UTF_8)
        }
    }

    private fun getEmbeddedLspPath(): Path? {
        val programName: String

        if (SystemInfo.isMac) {
            programName = "Slint Live Preview.app/Contents/MacOS/slint-lsp"
        } else if (SystemInfo.isLinux) {
            programName = when (CpuArch.CURRENT) {
                CpuArch.X86_64 -> "slint-lsp-x86_64-unknown-linux-gnu"
                CpuArch.ARM32 -> "slint-lsp-armv7-unknown-linux-gnueabihf"
                CpuArch.ARM64 -> "slint-lsp-aarch64-unknown-linux-gnu"
                else -> {
                    return null
                }
            }
        } else if (SystemInfo.isWindows) {
            programName = "slint-lsp-x86_64-pc-windows-msvc.exe"
        } else {
            return null
        }
        
        val lspPath = pluginPath()
            .resolve("language-server/bin")
            .resolve(programName)

        if (!SystemInfo.isWindows && !lspPath.isExecutable()) {
            lspPath.setPosixFilePermissions(
                lspPath.getPosixFilePermissions()
                    .plus(PosixFilePermission.OWNER_EXECUTE)
            )
        }

        return lspPath
    }

    fun pluginPath(): Path = requireNotNull(installationPath) {
        "Slint plugin installation is unavailable"
    }
}
