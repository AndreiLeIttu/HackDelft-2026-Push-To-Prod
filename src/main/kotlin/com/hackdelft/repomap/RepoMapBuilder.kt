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
            return MapData(
                errorNode("No classes found", "No source classes were detected in this project to map.").toJson(),
                "[]"
            )
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

        // 4. Build the tree with the LLM ONLY — no heuristic/sample fallback (per request).
        //    Any failure surfaces as a visible error node and is NOT cached, so a transient
        //    problem (network, rate limit) clears itself on the next open.
        if (!OpenAiClient.isConfigured()) {
            return MapData(
                errorNode(
                    "No OpenAI key found",
                    "Set OPENAI_API_KEY, the openai.api.key system property, or ~/.repomap/openai_key, then reopen the Repo Map."
                ).toJson(),
                "[]"
            )
        }

        val tree = LlmGrouping.build(project, signatures)
            ?: return MapData(
                errorNode(
                    "AI grouping failed",
                    "The OpenAI request returned no usable grouping. Check the key, network connectivity, and the IDE log (Help ▸ Show Log)."
                ).toJson(),
                "[]"
            )

        if (tree.children.isEmpty()) {
            return MapData(
                errorNode("AI grouping was empty", "The model returned a tree with no groups. Reopen the Repo Map to retry.").toJson(),
                "[]"
            )
        }

        val data = MapData(tree.toJson(), RepoDependencyAnalyzer.toJson(edges))

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

    /** A visible error/status node shown in place of a tree (no silent fallback). */
    private fun errorNode(title: String, detail: String): RepoTreeNode =
        RepoTreeNode(name = title, kind = "repository", summary = detail)

    /**
     * Cancellable read action that runs once the project is in smart mode. If a write action
     * is requested while this runs, it is cancelled and restarted instead of blocking the EDT.
     */
    private fun <T> inSmart(project: Project, block: () -> T): T =
        ReadAction.nonBlocking(Callable { block() })
            .inSmartMode(project)
            .executeSynchronously()
}
