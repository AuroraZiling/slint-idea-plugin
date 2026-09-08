package dev.slint.ideaplugin.ide.lsp

import com.intellij.openapi.project.Project
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.LocalFileSystem
import dev.slint.ideaplugin.ide.cargo.CargoLibraryService
import java.nio.file.Path
import com.intellij.openapi.extensions.PluginAware
import com.intellij.openapi.extensions.PluginDescriptor
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.platform.lsp.api.lsWidget.LspServerWidgetItem
import dev.slint.ideaplugin.SlintIcons
import dev.slint.ideaplugin.ide.settings.SlintSettingsConfigurable
import dev.slint.ideaplugin.lang.SlintFileType

@Suppress("UnstableApiUsage")
class SlintLspServerSupportProvider : LspServerSupportProvider, PluginAware {
    override fun setPluginDescriptor(pluginDescriptor: PluginDescriptor) {
        CommandLineHandler.bindPlugin(pluginDescriptor)
    }
    override fun fileOpened(
        project: Project,
        file: VirtualFile,
        serverStarter: LspServerSupportProvider.LspServerStarter
    ) {
        if (file.fileType != SlintFileType) return
        val service = project.service<CargoLibraryService>()
        service.start()
        val context = service.contextFor(Path.of(file.path))
        val rootPath = context?.manifest?.let { Path.of(it).parent.toString() } ?: project.basePath ?: return
        val root = LocalFileSystem.getInstance().findFileByPath(rootPath.replace('\\', '/')) ?: return
        serverStarter.ensureServerStarted(SlintLspServerDescriptor(project, context?.packageId, root))
    }

    override fun createLspServerWidgetItem(lspServer: LspServer, currentFile: VirtualFile?): LspServerWidgetItem =
        LspServerWidgetItem(lspServer, currentFile, SlintIcons.SLINT, SlintSettingsConfigurable::class.java)
}
