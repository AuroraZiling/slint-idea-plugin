package dev.slint.ideaplugin.ide.lsp

import com.intellij.testFramework.LightPlatformTestCase
import dev.slint.ideaplugin.ide.cargo.CargoLibraryService
import kotlinx.coroutines.*
import java.nio.file.Path
import com.intellij.openapi.project.Project
import com.intellij.openapi.extensions.PluginDescriptor
import java.lang.reflect.Proxy
import java.nio.file.Files

/** Exercises the same coroutine/process boundary used by project startup. */
class CargoProcessIntegrationTest : LightPlatformTestCase() {
    fun testCompleteDiscoveryPublishesLibraries(): Unit = runBlocking {
        withContext(Dispatchers.IO) {
            val externalRoot = System.getenv("SLINT_CARGO_TEST_PROJECT")
            val root = Path.of(externalRoot ?: "src/test/fixtures/cargo-libraries").toAbsolutePath()
            val pluginRoot = Files.createTempDirectory("slint-installed")
            com.intellij.openapi.util.Disposer.register(testRootDisposable) {
                com.intellij.openapi.util.io.FileUtil.delete(pluginRoot.toFile())
            }
            Files.createDirectories(pluginRoot.resolve("cargo-libraries"))
            Files.copy(Path.of("native/cargo-libraries/target/release/slint-cargo-libraries.exe"),
                pluginRoot.resolve("cargo-libraries/slint-cargo-libraries.exe"))
            val fixtureProject = object : Project by project {
                override fun getBasePath(): String = root.toString()
            }
            val descriptor = Proxy.newProxyInstance(PluginDescriptor::class.java.classLoader,
                arrayOf(PluginDescriptor::class.java)) { _, method, _ ->
                if (method.name == "getPluginPath") pluginRoot else null
            } as PluginDescriptor
            CommandLineHandler.bindPlugin(descriptor)
            val service = CargoLibraryService(fixtureProject, this)
            val resolve = CargoLibraryService::class.java.getDeclaredMethod("resolve")
            resolve.isAccessible = true
            resolve.invoke(service)
            assertNull(service.snapshot.error, service.snapshot.error)
            val context = service.snapshot.libraries.firstOrNull { it.name == "i18n" }?.context
            assertNotNull(context)
            val mappings = service.snapshot.mappings(context!!.packageId)
            assertTrue(mappings.keys.containsAll(setOf("i18n", "lucide")))
            val parameters = CommandLineHandler.createCommandLine(fixtureProject, mappings).parametersList.list
            assertTrue(parameters.contains("i18n=${mappings["i18n"]}"))
            assertTrue(parameters.contains("lucide=${mappings["lucide"]}"))
            Files.createDirectories(Path.of("build/cargo-verification"))
            Files.writeString(Path.of("build/cargo-verification/cargo2-service-mappings.json"), com.google.gson.Gson().toJson(mappings))
        }
    }
    fun testCargoMetadataFromDiscoveryCoroutine(): Unit = runBlocking {
        withContext(Dispatchers.IO) {
            val service = CargoLibraryService(project, this)
            val command = CargoLibraryService::class.java.getDeclaredMethod("command", Path::class.java, Array<String>::class.java)
            command.isAccessible = true
            val result = command.invoke(service, Path.of("src/test/fixtures/cargo-libraries").toAbsolutePath(),
                arrayOf("cargo", "metadata", "--format-version=1", "--locked")) as String
            assertTrue(result.contains("lucide-slint"))
        }
    }
}
