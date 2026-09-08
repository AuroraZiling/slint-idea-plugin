package dev.slint.ideaplugin.ide.lsp

import org.junit.Assert.*
import org.junit.Test

class LibraryArgumentsTest {
    @Test fun windowsPathsRemainSingleArguments() {
        val path = "C:\\中文 (project)\\generated files\\i18n.slint"
        val result = LibraryArguments.merge("", listOf(path, "D:\\second path"), mapOf("i18n" to path), emptyMap())
        assertEquals(listOf("-I", path, "-I", "D:\\second path", "-L", "i18n=$path"), result.arguments)
    }
    @Test fun explicitArgumentsOverrideManualAndCargoOnce() {
        val result = LibraryArguments.merge("-L \"i18n=C:\\explicit path\\i18n.slint\" --style fluent", emptyList(), mapOf("i18n" to "auto"), mapOf("i18n" to "manual"))
        assertEquals(listOf("--style", "fluent", "-L", "i18n=C:\\explicit path\\i18n.slint"), result.arguments)
        assertEquals(2, result.conflicts.size)
    }
    @Test fun attachedAndRepeatedLibraryFlagsCollapse() {
        val result = LibraryArguments.merge("-Lx=a -Lx=b", emptyList(), emptyMap(), emptyMap())
        assertEquals(listOf("-L", "x=b"), result.arguments)
    }
    @Test(expected = IllegalArgumentException::class) fun malformedLibrariesFailClearly() {
        LibraryArguments.merge("-L", emptyList(), emptyMap(), emptyMap())
    }
}
