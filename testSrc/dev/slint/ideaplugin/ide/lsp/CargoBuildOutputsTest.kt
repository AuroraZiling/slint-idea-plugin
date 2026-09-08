package dev.slint.ideaplugin.ide.lsp

import dev.slint.ideaplugin.ide.cargo.CargoBuildOutputs
import org.junit.Assert.*
import org.junit.Test

class CargoBuildOutputsTest {
    // Minimized replay of the user's actual Cargo stream: num-traits occurs twice,
    // while the Slint application has one unambiguous build-script result.
    @Test fun dependencyBuildVariantsDoNotDiscardApplicationOutput() {
        val output = """
            {"reason":"build-script-executed","package_id":"registry+https://github.com/rust-lang/crates.io-index#num-traits@0.2.19","out_dir":"C:/target/debug/build/num-traits-08dd914998a26fa0/out"}
            {"reason":"build-script-executed","package_id":"registry+https://github.com/rust-lang/crates.io-index#num-traits@0.2.19","out_dir":"C:/target/debug/build/num-traits-c81c958f56053a09/out"}
            {"reason":"build-script-executed","package_id":"application","out_dir":"C:/target/debug/build/application/out"}
        """.trimIndent()
        val result = CargoBuildOutputs.parse(output)
        assertEquals("C:/target/debug/build/application/out", result.unique["application"].asString)
        assertEquals(1, result.unique.size())
        assertEquals(2, result.ambiguous.values.single().size)
    }
    @Test fun repeatedCachedMessageIsNotAmbiguous() {
        val line = """{"reason":"build-script-executed","package_id":"app","out_dir":"C:/out"}"""
        val result = CargoBuildOutputs.parse("$line\n$line")
        assertTrue(result.ambiguous.isEmpty())
        assertEquals("C:/out", result.unique["app"].asString)
    }

    @Test fun ambiguousApplicationNeverGetsAnArbitraryDirectory() {
        val result = CargoBuildOutputs.parse("""
            {"reason":"build-script-executed","package_id":"app","out_dir":"C:/one"}
            {"reason":"build-script-executed","package_id":"app","out_dir":"C:/two"}
        """.trimIndent())
        assertFalse(result.unique.has("app"))
        assertEquals(setOf("C:/one", "C:/two"), result.ambiguous["app"])
    }
}
