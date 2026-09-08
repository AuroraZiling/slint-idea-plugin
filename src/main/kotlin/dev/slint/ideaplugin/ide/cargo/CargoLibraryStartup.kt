package dev.slint.ideaplugin.ide.cargo

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.extensions.PluginAware
import com.intellij.openapi.extensions.PluginDescriptor
import dev.slint.ideaplugin.ide.lsp.CommandLineHandler

class CargoLibraryStartup : ProjectActivity, PluginAware {
    override fun setPluginDescriptor(pluginDescriptor: PluginDescriptor) {
        CommandLineHandler.bindPlugin(pluginDescriptor)
    }
    override suspend fun execute(project: Project) {
        project.service<CargoLibraryService>().start()
    }
}
