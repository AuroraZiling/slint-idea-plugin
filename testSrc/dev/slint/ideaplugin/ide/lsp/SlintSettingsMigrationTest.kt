package dev.slint.ideaplugin.ide.lsp

import com.intellij.util.xmlb.XmlSerializer
import dev.slint.ideaplugin.ide.settings.SlintSettingsState
import org.junit.Assert.*
import org.junit.Test

class SlintSettingsMigrationTest {
    @Test fun oldStateRetainsExternalServerArgsAndIncludes() {
        val old = SlintSettingsState().apply {
            lspSettings.path = "C:\\Slint tools\\slint-lsp.exe"
            lspSettings.useExternalLsp = true
            lspSettings.args = "--style fluent"
            lspSettings.includePaths.add("D:\\UI includes")
        }
        val xml = XmlSerializer.serialize(old)
        val restored = XmlSerializer.deserialize(xml, SlintSettingsState::class.java)
        assertEquals(old.lspSettings.path, restored.lspSettings.path)
        assertEquals(old.lspSettings.args, restored.lspSettings.args)
        assertEquals(old.lspSettings.includePaths, restored.lspSettings.includePaths)
        assertTrue(restored.lspSettings.useExternalLsp)
        assertTrue(restored.lspSettings.discoverCargoLibraries)
        assertTrue(restored.lspSettings.libraryOverrides.isEmpty())
    }
}
