package dev.slint.ideaplugin.ide.lsp

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.*
import com.intellij.platform.lsp.api.customization.LspCompletionSupport
import com.intellij.platform.lsp.api.customization.LspFormattingSupport
import dev.slint.ideaplugin.ide.settings.SlintSettingsState
import dev.slint.ideaplugin.lang.SlintFileType
import dev.slint.ideaplugin.lang.isSlint
import org.eclipse.lsp4j.services.LanguageServer
import dev.slint.ideaplugin.ide.cargo.CargoLibraryService
import java.nio.file.Path

@Suppress("UnstableApiUsage")
class SlintLspServerDescriptor(project: Project, val packageId: String?, root: VirtualFile) :
    LspServerDescriptor(project, "Slint: ${packageId ?: "project"}", root) {

    override fun isSupportedFile(file: VirtualFile) = file.isSlint &&
        project.service<CargoLibraryService>().contextFor(Path.of(file.path))?.packageId == packageId

    override fun createCommandLine(): GeneralCommandLine = CommandLineHandler.createCommandLine(project,
        project.service<CargoLibraryService>().snapshot.mappings(packageId)).withWorkDirectory(roots.first().path)

    override fun createInitializationOptions(): Any = SlintSettingsState.getInstance(project).lspSettings

    // Waiting for fix https://youtrack.jetbrains.com/issue/MP-6574
    // override fun createLsp4jClient(handler: LspServerNotificationsHandler): Lsp4jClient = SlintLspClient(handler)

    override val lsp4jServerClass: Class<out LanguageServer> = SlintLspServer::class.java
    override val lspServerListener: LspServerListener = SlintLspServerListener(project)
    override val lspCompletionSupport: LspCompletionSupport = SlintLspCompletionSupport()
    override val lspFormattingSupport: LspFormattingSupport = SlintLspFormattingSupport()
}
