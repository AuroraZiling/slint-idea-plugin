package dev.slint.ideaplugin.ide.cargo

enum class LibraryStatus { Resolved, PendingBuild, Unresolved, Ambiguous }
enum class LibraryKind { File, Directory, Unknown }
data class CargoBuildContext(val workspace: String, val packageId: String, val manifest: String,
    val configuration: String, val outDir: String?)
data class CargoLibrary(val name: String, val path: String?, val kind: LibraryKind,
    val context: CargoBuildContext, val source: String, val status: LibraryStatus, val evidence: String)
data class CargoResolution(val contexts: List<CargoBuildContext> = emptyList(), val libraries: List<CargoLibrary> = emptyList(),
    val watchFiles: Set<String> = emptySet(), val error: String? = null) {
    fun mappings(packageId: String?): Map<String, String> = libraries.filter {
        it.context.packageId == packageId && it.status == LibraryStatus.Resolved && it.path != null
    }.associate { it.name to it.path!! }
}
