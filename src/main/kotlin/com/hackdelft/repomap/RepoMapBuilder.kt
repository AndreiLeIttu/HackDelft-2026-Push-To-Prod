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
        val edges = inSmart(project) { RepoDependencyAnalyzer.analyze(project) }

        val tree = if (OpenAiClient.isConfigured()) {
            val signatures = inSmart(project) { ClassSignatureExtractor.extract(project) }
            // Network call happens OUTSIDE the read action; falls back to heuristic on failure.
            LlmGrouping.build(project, signatures) ?: inSmart(project) { RepoTreeBuilder.build(project) }
        } else {
            inSmart(project) { RepoTreeBuilder.build(project) }
        }

        val data = if (tree.children.isEmpty()) {
            MapData(RepoTreeSamples.sampleProjectJson(project.name), "[]")
        } else {
            MapData(tree.toJson(), RepoDependencyAnalyzer.toJson(edges))
        }

        DebugDump.write("repo-map-tree.json", "{\"tree\":${data.treeJson},\"edges\":${data.edgesJson}}")
        return data
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
