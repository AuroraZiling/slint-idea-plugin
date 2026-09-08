package dev.slint.ideaplugin.ide.settings

import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.components.service
import dev.slint.ideaplugin.ide.cargo.CargoLibraryService
import dev.slint.ideaplugin.ide.lsp.LibraryArguments
import javax.swing.JTextArea
import javax.swing.JScrollPane
import javax.swing.Timer

class SlintSettingsComponent(project: Project, lspSettings: SlintLspSettings) {
    private var panel: DialogPanel? = null

    init {
        panel = panel {
            group("LSP Settings") {
                lateinit var useExternalLspCheckBox: Cell<JBCheckBox>

                row {
                    useExternalLspCheckBox = checkBox("Use external LSP server")
                        .bindSelected(lspSettings::useExternalLsp)
                }
                indent {
                    row {
                        textFieldWithBrowseButton()
                            .label("LSP path:")
                            .bindText(lspSettings::path)
                            .enabledIf(useExternalLspCheckBox.selected)
                            .align(AlignX.FILL)
                    }
                }
                row("Args:") {
                    expandableTextField()
                        .comment("The command line arguments passed to the Slint LSP server")
                        .bindText(lspSettings::args)
                        .align(AlignX.FILL)
                }
                row {
                    val pathsTablePanel = PathsTablePanel()
                    cell(pathsTablePanel.component)
                        .comment("List of paths in which the `import` statement and `@image-url` are looked up")
                        .align(AlignX.FILL)
                        .label("Include paths:", LabelPosition.TOP)
                        .onIsModified { pathsTablePanel.onModified(lspSettings.includePaths) }
                        .onApply { pathsTablePanel.onApply(lspSettings.includePaths) }
                        .onReset { pathsTablePanel.onReset(lspSettings.includePaths) }
                }
            }
            group("Cargo component libraries") {
                row {
                    checkBox("Automatically discover libraries from Cargo")
                        .bindSelected(lspSettings::discoverCargoLibraries)
                }
                val discovered = JTextArea(9, 65).apply { isEditable = false; lineWrap = false }
                val service = project.service<CargoLibraryService>()
                fun update() {
                    val result = service.snapshot
                    discovered.text = buildString {
                        appendLine("Name / Path / Source / Status")
                        result.error?.let { appendLine(it) }
                        result.libraries.forEach { l -> appendLine("${l.name} / ${l.path ?: "—"} / ${l.source} / ${l.status}\n  ${l.context.packageId}\n  ${l.evidence}") }
                        result.contexts.forEach { c ->
                            try { LibraryArguments.merge(lspSettings.args, emptyList(), result.mappings(c.packageId), lspSettings.libraryOverrides)
                                .conflicts.forEach { appendLine(it) } } catch (e: IllegalArgumentException) { appendLine(e.message) }
                        }
                    }
                }
                row { cell(JScrollPane(discovered)).align(AlignX.FILL).label("Discovered libraries:", LabelPosition.TOP) }
                row { button("Refresh Cargo library resolution") { service.refresh(); update() } }
                val overrides = JTextArea(4, 65)
                fun readOverrides(): Map<String, String> = overrides.text.lineSequence().filter { it.isNotBlank() }.associate { line ->
                    val pair = line.split('=', limit = 2)
                    require(pair.size == 2 && pair[0].isNotBlank() && pair[1].isNotBlank()) { "Expected name=path for each library override" }
                    pair[0].trim() to pair[1].trim()
                }
                row {
                    cell(JScrollPane(overrides)).align(AlignX.FILL)
                        .label("Manual library overrides (one name=path per line):", LabelPosition.TOP)
                        .onReset { overrides.text = lspSettings.libraryOverrides.entries.joinToString("\n") { "${it.key}=${it.value}" } }
                        .onIsModified { runCatching { readOverrides() != lspSettings.libraryOverrides }.getOrDefault(true) }
                        .onApply { lspSettings.libraryOverrides = readOverrides().toMutableMap(); service.refresh() }
                }
                val timer = Timer(1500) { update() }
                discovered.addHierarchyListener { if (discovered.isShowing) { timer.start(); service.start() } else timer.stop() }
                update()
            }
            group("Preview") {
                row {
                    checkBox("Hide the toolbar of the preview")
                        .comment("")
                        .bindSelected(lspSettings::noToolbar)
                }
                row("Style:") {
                    comboBox(SlintStyle.entries)
                        .bindItem(lspSettings::style.toNullableProperty())
                }
                row("Backend:") {
                    comboBox(SlintBackend.entries)
                        .bindItem(lspSettings::backend.toNullableProperty())
                }
//                Disabled before planned development
//                row {
//                    checkBox("Provided by editor")
//                        .comment("Instead of letting the Language Server display the preview in a native window, show the preview in an editor tab using web-assembly. You need to reopen previously opened files.<br>(EXPERIMENTAL AND ONLY PREVIEW IS SUPPORTED)")
//                        .bindSelected(lspSettings::providedByEditor)
//                }
            }
        }
    }

    fun getPanel(): DialogPanel? {
        return panel
    }
}
