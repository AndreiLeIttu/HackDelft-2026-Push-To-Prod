package com.hackdelft.repomap

import com.intellij.analysis.AnalysisScope
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.packageDependencies.ForwardDependenciesBuilder
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager

/**
 * Extracts "uses" relationships between classes so the detail view can draw how classes
 * interact across semantic groups (e.g. a Database class that a UI class depends on).
 *
 * Uses the platform's [ForwardDependenciesBuilder], which resolves dependencies through
 * PSI for every supported language (Java, Kotlin, ...). We work at file granularity and
 * map each file to the fully-qualified names of the top-level classes it declares, so the
 * resulting edges line up with the class nodes' `path` in the architecture tree.
 *
 * Fully guarded: if analysis fails for any reason the map degrades gracefully to no edges
 * rather than breaking the whole tool window.
 *
 * NOTE: must be called inside a read action (see callers).
 */
object RepoDependencyAnalyzer {

    private val LOG = logger<RepoDependencyAnalyzer>()

    data class Edge(val source: String, val target: String)

    fun analyze(project: Project): List<Edge> {
        return try {
            // Restrict to JVM (Java/Kotlin) source files only. Including JS/TS/XML triggers
            // language visitors that require a cancellable job we don't have here, and their
            // edges would be useless anyway (our tree only contains JVM classes).
            val files = jvmSourceFiles(project)
            if (files.isEmpty()) return emptyList()

            val builder = ForwardDependenciesBuilder(project, AnalysisScope(project, files))
            builder.analyze()

            val edges = LinkedHashSet<Edge>()
            for ((fromFile, toFiles) in builder.dependencies) {
                val sources = fqns(fromFile)
                if (sources.isEmpty()) continue
                for (toFile in toFiles) {
                    if (toFile == fromFile) continue
                    for (source in sources) {
                        for (target in fqns(toFile)) {
                            if (source != target) edges.add(Edge(source, target))
                        }
                    }
                }
            }
            edges.toList()
        } catch (t: Throwable) {
            rethrowControlFlow(t)
            LOG.warn("Dependency analysis failed; continuing without edges", t)
            emptyList()
        }
    }

    private fun fqns(file: PsiFile?): List<String> {
        if (file !is PsiClassOwner) return emptyList()
        return file.classes.mapNotNull { it.qualifiedName }
    }

    private fun jvmSourceFiles(project: Project): Collection<VirtualFile> {
        val index = ProjectFileIndex.getInstance(project)
        val psiManager = PsiManager.getInstance(project)
        val files = ArrayList<VirtualFile>()
        index.iterateContent { file ->
            if (!file.isDirectory &&
                index.isInSourceContent(file) &&
                !index.isInTestSourceContent(file)
            ) {
                try {
                    if (psiManager.findFile(file) is PsiClassOwner) files.add(file)
                } catch (t: Throwable) {
                    rethrowControlFlow(t)
                }
            }
            true
        }
        return files
    }

    /** Serializes edges to a JSON array: [{"source":"...","target":"..."}, ...]. */
    fun toJson(edges: List<Edge>): String = buildString {
        append('[')
        edges.forEachIndexed { index, edge ->
            if (index > 0) append(',')
            append("{\"source\":").append(Json.str(edge.source))
            append(",\"target\":").append(Json.str(edge.target))
            append('}')
        }
        append(']')
    }
}
