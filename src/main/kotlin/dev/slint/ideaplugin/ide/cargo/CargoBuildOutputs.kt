package dev.slint.ideaplugin.ide.cargo

import com.google.gson.JsonObject
import com.google.gson.JsonParser

internal object CargoBuildOutputs {
    data class Result(val unique: JsonObject, val ambiguous: Map<String, Set<String>>)

    fun parse(output: String): Result {
        val candidates = linkedMapOf<String, MutableSet<String>>()
        output.lineSequence().filter { it.startsWith("{") }.forEach { line ->
            val message = JsonParser.parseString(line).asJsonObject
            if (message.get("reason")?.asString == "build-script-executed") {
                val id = message["package_id"].asString
                val dir = message["out_dir"].asString
                candidates.getOrPut(id) { linkedSetOf() }.add(dir)
            }
        }
        // Dependencies can legitimately have different host/target or feature builds.
        // Never choose a directory arbitrarily, and never discard unrelated packages.
        val unique = JsonObject()
        candidates.filterValues { it.size == 1 }.forEach { (id, paths) -> unique.addProperty(id, paths.single()) }
        return Result(unique, candidates.filterValues { it.size > 1 })
    }
}
