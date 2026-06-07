package com.hackdelft.repomap

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import java.util.concurrent.Callable

/**
 * Orchestrates building the map: dependency edges + the architecture tree (AI-organized
 * when an OpenAI key is configured, otherwise the deterministic heuristic).
 *
 * Must be called OFF the EDT. PSI access uses cancellable non-blocking read actions so it
 * never freezes the UI thread; the network call happens between read actions.
 */
object RepoMapBuilder {

    data class MapData(val treeJson: String, val edgesJson: String)

    fun build(project: Project): MapData {
        // 1. Extract class signatures first (cheap). Prefer rich JVM PSI; fall back to a
        //    language-agnostic text scan for non-JVM projects (C#, Go, TS, ...).
        val psiSignatures = inSmart(project) { ClassSignatureExtractor.extract(project) }
        val signatures = if (psiSignatures.isNotEmpty()) {
            psiSignatures
        } else {
            inSmart(project) { SourceTextExtractor.extract(project) }
        }

        if (signatures.isEmpty()) {
            return MapData(RepoTreeSamples.sampleProjectJson(project.name), "[]")
        }

        // 2. Reuse a cached result when the class set + grouping mode are unchanged — avoids
        //    re-running the slow, paid LLM call and the dependency scan on every open/relaunch.
        val cacheKey = cacheKey(project, signatures)
        RepoMapCache.get(cacheKey)?.let { return it }

        // 3. Dependency edges only make sense for JVM classes; skip that scan entirely otherwise.
        val edges = if (psiSignatures.isNotEmpty()) {
            inSmart(project) { RepoDependencyAnalyzer.analyze(project) }
        } else {
            emptyList()
        }

        // 4. Build the tree (AI grouping when configured; deterministic fallback otherwise).
        val tree = if (OpenAiClient.isConfigured()) {
            LlmGrouping.build(project, signatures) ?: fallbackTree(project, psiSignatures, signatures)
        } else {
            fallbackTree(project, psiSignatures, signatures)
        }

        val data = if (tree.children.isEmpty()) {
            MapData(RepoTreeSamples.sampleProjectJson(project.name), "[]")
        } else {
            MapData(tree.toJson(), RepoDependencyAnalyzer.toJson(edges))
        }

        RepoMapCache.put(cacheKey, data)
        DebugDump.write("repo-map-tree.json", "{\"tree\":${data.treeJson},\"edges\":${data.edgesJson}}")
        return data
    }

    /** Cache identity: project + grouping mode/prompt + the (sorted) set of class names. */
    private fun cacheKey(project: Project, signatures: List<ClassSignature>): String {
        val mode = if (OpenAiClient.isConfigured()) "ai${LlmGrouping.promptVersion()}" else "heuristic"
        val classesHash = signatures.map { it.fqn }.sorted().joinToString("\n").hashCode()
        return "${project.name}-$mode-$classesHash"
    }

    /** Deterministic tree: JVM heuristic categories when available, else structural-by-folder. */
    private fun fallbackTree(
        project: Project,
        psiSignatures: List<ClassSignature>,
        signatures: List<ClassSignature>
    ): RepoTreeNode =
        if (psiSignatures.isNotEmpty()) {
            inSmart(project) { RepoTreeBuilder.build(project) }
        } else {
            RepoTreeBuilder.fromSignatures(project, signatures)
        }

    /**
     * Cancellable read action that runs once the project is in smart mode. If a write action
     * is requested while this runs, it is cancelled and restarted instead of blocking the EDT.
     */
    private fun <T> inSmart(project: Project, block: () -> T): T =
        ReadAction.nonBlocking(Callable { block() })
            .inSmartMode(project)
            .executeSynchronously()
}
