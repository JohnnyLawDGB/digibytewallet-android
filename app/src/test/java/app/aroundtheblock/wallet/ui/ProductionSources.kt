package app.aroundtheblock.wallet.ui

import java.io.File

/**
 * Every Kotlin and Java file that is built into the app: each module's source sets except the
 * test ones (main and the flavour sets, `play` and `sideload`). A gate that walks only `main`
 * would miss a flavour's copy of a screen.
 *
 * Paths are relative to the repository root, with `/` separators. Unit tests run in the app
 * module's directory, so the root is its parent.
 */
internal object ProductionSources {

    private val modules = listOf("app", "core", "native", "game")

    val root: File = File("..").canonicalFile

    val files: List<Pair<String, String>> by lazy {
        modules.flatMap { module ->
            File(root, "$module/src").listFiles().orEmpty()
                .filter { it.isDirectory && !it.name.startsWith("test") && it.name != "androidTest" }
                .sortedBy { it.name }
                .flatMap { set ->
                    set.walkTopDown()
                        .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                        .sortedBy { it.path }
                        .toList()
                }
        }.map { it.relativeTo(root).invariantSeparatorsPath to it.readText() }
    }

    /** The line of [index] in [text], counting from 1. */
    fun lineOf(text: String, index: Int): Int = text.substring(0, index).count { it == '\n' } + 1
}
