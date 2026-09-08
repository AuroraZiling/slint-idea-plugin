package dev.slint.ideaplugin.ide.lsp

import com.intellij.util.execution.ParametersListUtil

/** Preserve argv boundaries; explicit arguments win over manual and discovered paths. */
object LibraryArguments {
    data class Result(val arguments: List<String>, val conflicts: List<String>)

    fun merge(args: String, includes: List<String>, discovered: Map<String, String>, manual: Map<String, String>): Result {
        val mapping = LinkedHashMap(discovered)
        val conflicts = mutableListOf<String>()
        fun put(name: String, path: String, source: String) {
            require(name.isNotBlank() && path.isNotBlank()) { "Library name and path must not be empty" }
            if (mapping.containsKey(name) && mapping[name] != path) conflicts += "$name: $source overrides ${mapping[name]}"
            mapping[name] = path
        }
        manual.forEach { (name, path) -> put(name, path, "Manual override") }
        val parsed = ParametersListUtil.parse(args)
        val result = mutableListOf<String>()
        var i = 0
        while (i < parsed.size) {
            val arg = parsed[i++]
            val value = when {
                arg == "-L" || arg == "--library-path" -> {
                    require(i < parsed.size) { "$arg requires name=path" }
                    parsed[i++]
                }
                arg.startsWith("--library-path=") -> arg.substringAfter('=')
                arg.startsWith("-L") && arg.length > 2 -> arg.substring(2)
                else -> null
            }
            if (value == null) result += arg else {
                val entry = value.removePrefix("=").split('=', limit = 2)
                require(entry.size == 2) { "Library argument requires name=path: $value" }
                put(entry[0], entry[1], "Args")
            }
        }
        includes.filter { it.isNotBlank() }.forEach { result += listOf("-I", it) }
        mapping.forEach { (name, path) -> result += listOf("-L", "$name=$path") }
        return Result(result, conflicts)
    }
}
