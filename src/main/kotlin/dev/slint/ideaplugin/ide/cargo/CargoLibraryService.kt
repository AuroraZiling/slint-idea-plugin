package dev.slint.ideaplugin.ide.cargo

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.SystemInfo
import dev.slint.ideaplugin.ide.lsp.CommandLineHandler
import dev.slint.ideaplugin.ide.services.SlintServerService
import dev.slint.ideaplugin.ide.settings.SlintSettingsState
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.*

/** Each project owns its snapshot and one cancellable worker. No RustRover internal APIs. */
@Service(Service.Level.PROJECT)
class CargoLibraryService(private val project: Project, private val scope: CoroutineScope) : Disposable {
    @Volatile var snapshot = CargoResolution()
        private set
    @Volatile private var active: Process? = null
    private val refresh = AtomicBoolean(true)
    private val started = AtomicBoolean(false)
    private var inputs = ""
    private var outputs = ""
    private var retryAt = 0L

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            while (isActive && !project.isDisposed) {
                if (SlintSettingsState.getInstance(project).lspSettings.discoverCargoLibraries) {
                    val current = inputStamp()
                    val generated = outputStamp()
                    val pending = snapshot.error != null || snapshot.libraries.any { it.status == LibraryStatus.PendingBuild }
                    if (refresh.getAndSet(false) || current != inputs || (pending && System.currentTimeMillis() >= retryAt)) {
                        inputs = current
                        resolve()
                        // Keep the pre-build fingerprint so a source edit during Cargo isn't swallowed.
                        // Discovery adds watched files once; Cargo output files are excluded from this stamp.
                        outputs = outputStamp()
                        retryAt = System.currentTimeMillis() + if (snapshot.error == null) 15000 else 60000
                    } else if (generated != outputs) {
                        // target may be excluded from VFS. Poll actual mapped files, including content changes.
                        resolve()
                        inputs = inputStamp()
                        outputs = outputStamp()
                    }
                } else if (snapshot.contexts.isNotEmpty() || snapshot.error != null) publish(CargoResolution())
                delay(2500)
            }
        }
    }
    fun refresh() { refresh.set(true); start() }
    fun contextFor(path: Path): CargoBuildContext? = snapshot.contexts.filter {
        path.toAbsolutePath().normalize().startsWith(Path.of(it.manifest).parent.toAbsolutePath().normalize())
    }.maxByOrNull { Path.of(it.manifest).nameCount }

    private fun digest(files: Collection<Path>): String {
        val md = MessageDigest.getInstance("SHA-256")
        files.sortedBy { it.toString() }.forEach { p ->
            md.update(p.toString().toByteArray())
            try { if (p.isRegularFile()) md.update(Files.readAllBytes(p)) else md.update(0.toByte()) }
            catch (_: Exception) { md.update(1.toByte()) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
    private fun inputStamp(): String {
        val root = project.basePath?.let(Path::of) ?: return ""
        val files = snapshot.watchFiles.map(Path::of).toMutableSet()
        files.add(root.resolve("Cargo.toml"))
        files.add(root.resolve("Cargo.lock"))
        var ancestor: Path? = root
        while (ancestor != null) {
            files.add(ancestor.resolve(".cargo/config.toml"))
            files.add(ancestor.resolve(".cargo/config"))
            files.add(ancestor.resolve("rust-toolchain.toml"))
            files.add(ancestor.resolve("rust-toolchain"))
            ancestor = ancestor.parent
        }
        // Only expand explicitly declared Cargo inputs. Ordinary Rust/Slint editor saves
        // must not launch Cargo; mapped source files are supplied by the AST analyzer.
        fun visit(dir: Path, depth: Int) {
            if (depth > 16) return
            try { Files.newDirectoryStream(dir).use { entries -> entries.forEach { p ->
                if (Files.isSymbolicLink(p)) return@forEach
                if (p.isDirectory() && p.name !in setOf("target", ".git", ".idea", "node_modules", ".gradle", ".intellijPlatform")) visit(p, depth + 1)
                else if (p.isRegularFile() && p.extension != "slint") files.add(p)
            } } } catch (_: Exception) { }
        }
        files.filter { it.isDirectory() }.toList().forEach { visit(it, 0) }
        return digest(files)
    }
    private fun outputStamp(): String = digest(snapshot.libraries.mapNotNull { it.path?.let(Path::of) }.flatMap { p ->
        if (p.isDirectory()) try { Files.walk(p).use { s -> s.filter { it.isRegularFile() && it.extension == "slint" }.toList() } } catch (_: Exception) { listOf(p) }
        else listOf(p)
    })
    private fun command(root: Path, vararg args: String): String {
        val line = GeneralCommandLine(*args).withWorkDirectory(root.toFile()).withCharset(Charsets.UTF_8)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
        val handler = CapturingProcessHandler(line)
        active = handler.process
        try {
            val result = handler.runProcess(600_000)
            check(!result.isTimeout) { "Command timed out: ${args.first()}" }
            check(result.exitCode == 0) { "${args.take(2).joinToString(" ")} failed (${result.exitCode}):\n${result.stderr.takeLast(6000)}" }
            return result.stdout
        } finally { active = null }
    }
    private fun resolve() {
        val root = project.basePath?.let(Path::of) ?: return
        if (!root.resolve("Cargo.toml").exists()) { publish(CargoResolution()); return }
        if (!TrustedProjects.isProjectTrusted(project)) { publish(CargoResolution(error = "Cargo discovery waits for project trust before executing build scripts.")); return }
        if (!SystemInfo.isWindows) { publish(CargoResolution(error = "This local build bundles the Cargo analyzer for Windows only.")); return }
        try {
            val locked = if (root.resolve("Cargo.lock").exists()) arrayOf("--locked") else emptyArray()
            val metadata = JsonParser.parseString(command(root, "cargo", "metadata", "--format-version=1", *locked)).asJsonObject
            val output = command(root, "cargo", "check", "--workspace", "--message-format=json", *locked)
            val buildOutputs = CargoBuildOutputs.parse(output)
            val outDirs = buildOutputs.unique
            val request = JsonObject().apply { add("metadata", metadata); add("out_dirs", outDirs) }
            val temp = Files.createTempFile("slint-cargo-request-", ".json")
            val result = try {
                Files.writeString(temp, request.toString())
                val exe = CommandLineHandler.pluginPath().resolve("cargo-libraries/slint-cargo-libraries.exe")
                JsonParser.parseString(command(root, exe.toString(), temp.toString())).asJsonObject
            } finally { Files.deleteIfExists(temp) }
            val contexts = mutableListOf<CargoBuildContext>()
            val libraries = mutableListOf<CargoLibrary>()
            val watched = result["watch_files"].asJsonArray.map { it.asString }.toMutableSet()
            result["contexts"].asJsonArray.forEach { element ->
                val c = element.asJsonObject
                val id = c["package_id"].asString
                val context = CargoBuildContext(metadata["workspace_root"].asString, id, c["manifest"].asString,
                    "Cargo defaults; source/config SHA-256=$inputs", outDirs.get(id)?.asString)
                contexts += context
                context.outDir?.let { out ->
                    // Cargo's actual build output belongs to this OUT_DIR, never a historical scan.
                    val buildOutput = Path.of(out).parent.resolve("output")
                    if (buildOutput.isRegularFile()) {
                        Files.readAllLines(buildOutput).forEach { directive ->
                            val relative = when {
                                directive.startsWith("cargo:rerun-if-changed=") -> directive.substringAfter('=')
                                directive.startsWith("cargo::rerun-if-changed=") -> directive.substringAfter('=')
                                else -> null
                            }
                            if (!relative.isNullOrBlank()) {
                                val input = Path.of(context.manifest).parent.resolve(relative).toAbsolutePath().normalize()
                                if (input.extension != "slint" && !input.startsWith(Path.of(out).toAbsolutePath().normalize())) watched.add(input.toString())
                            }
                        }
                    }
                }
                c["libraries"].asJsonArray.forEach { item ->
                    val l = item.asJsonObject
                    val ambiguous = buildOutputs.ambiguous[id]
                    libraries += CargoLibrary(l["name"].asString, l["path"].takeUnless { it.isJsonNull }?.asString,
                        LibraryKind.valueOf(l["kind"].asString), context, l["source"].asString,
                        if (ambiguous != null) LibraryStatus.Ambiguous else LibraryStatus.valueOf(l["status"].asString),
                        if (ambiguous != null) "Multiple Cargo OUT_DIR values for $id: ${ambiguous.joinToString()}" else l["evidence"].asString)
                }
            }
            publish(CargoResolution(contexts, libraries, watched), true)
        } catch (e: Exception) {
            if (project.isDisposed) return
            val previous = snapshot
            publish(previous.copy(libraries = previous.libraries.map {
                it.copy(status = LibraryStatus.Unresolved, evidence = "Cargo context could not be refreshed; previous path is inactive")
            }, error = e.message ?: e.javaClass.simpleName))
        }
    }
    private fun publish(next: CargoResolution, checkContent: Boolean = false) {
        val before = snapshot
        snapshot = next
        if (next.error != null && next.error != before.error) Logger.getInstance(CargoLibraryService::class.java)
            .warn("Cargo library discovery for ${project.basePath}: ${next.error}")
        val changed = before.contexts.map { it.packageId to it.manifest } != next.contexts.map { it.packageId to it.manifest } ||
            before.libraries.map { Triple(it.context.packageId, it.name, it.path to it.status) } != next.libraries.map { Triple(it.context.packageId, it.name, it.path to it.status) }
        if (changed) Logger.getInstance(CargoLibraryService::class.java).info(
            "Cargo libraries for ${project.basePath}: " + next.libraries.joinToString { "${it.context.packageId}: @${it.name} ${it.status}" })
        if (changed || (checkContent && outputs != outputStamp())) ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) project.service<SlintServerService>().restartServer()
        }
    }
    override fun dispose() {
        active?.let { process -> process.toHandle().descendants().forEach { it.destroy() }; process.destroy() }
    }
}
