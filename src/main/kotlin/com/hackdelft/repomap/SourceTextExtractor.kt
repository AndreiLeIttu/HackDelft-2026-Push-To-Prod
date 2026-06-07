package com.hackdelft.repomap

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile

/**
 * Language-agnostic fallback used when PSI finds no classes (e.g. C#, Go, Python, TS — any
 * language IntelliJ IDEA can't parse into JVM PSI). Scans source files as text and pulls out
 * type declarations with their folder path as the "package", so the AI grouping still works.
 *
 * Approximate by design (regex, not a real parser) — good enough for a structural description.
 * Must be called inside a read action.
 */
object SourceTextExtractor {

    private val LOG = logger<SourceTextExtractor>()
    private const val MAX_TYPES = 4000
    private const val MAX_FILE_BYTES = 400_000L

    private val CODE_EXT = setOf(
        "cs", "ts", "tsx", "js", "jsx", "mjs", "py", "go", "rb", "rs", "php", "swift",
        "scala", "cpp", "cc", "cxx", "c", "h", "hpp", "hh", "m", "mm", "vb", "fs"
    )

    private val EXCLUDE_DIR = setOf(
        "bin", "obj", "node_modules", ".git", "dist", "build", "out", ".vs", ".idea",
        "packages", "vendor", "target", ".next", "wwwroot", "__pycache__", "tests", "test"
    )

    // Matches `class Foo`, `public sealed interface Bar`, `record struct Baz`, etc.
    private val TYPE_DECL =
        Regex("\\b(class|interface|record|struct|enum|trait|protocol)\\b\\s+([A-Za-z_][A-Za-z0-9_]*)")

    fun extract(project: Project): List<ClassSignature> {
        val base = project.basePath?.replace('\\', '/')
        val index = ProjectFileIndex.getInstance(project)
        val result = ArrayList<ClassSignature>()

        index.iterateContent { file ->
            if (result.size >= MAX_TYPES) return@iterateContent false
            if (!file.isDirectory) {
                try {
                    scanFile(file, base, result)
                } catch (t: Throwable) {
                    rethrowControlFlow(t)
                }
            }
            true
        }
        return result
    }

    private fun scanFile(file: VirtualFile, base: String?, out: MutableList<ClassSignature>) {
        val ext = file.extension?.lowercase() ?: return
        if (ext !in CODE_EXT) return

        val path = file.path.replace('\\', '/')
        if (EXCLUDE_DIR.any { path.contains("/$it/") }) return
        if (file.length > MAX_FILE_BYTES) return

        val text = String(file.contentsToByteArray(), Charsets.UTF_8)
        val rel = if (base != null && path.startsWith(base)) path.removePrefix(base).trimStart('/') else path
        val pkg = rel.substringBeforeLast('/', "").replace('/', '.')

        for (match in TYPE_DECL.findAll(text)) {
            if (out.size >= MAX_TYPES) break
            val kind = match.groupValues[1]
            val name = match.groupValues[2]
            val fqn = if (pkg.isEmpty()) name else "$pkg.$name"
            out += ClassSignature(fqn, pkg, name, kind, emptyList(), emptyList(), emptyList())
        }
    }
}
