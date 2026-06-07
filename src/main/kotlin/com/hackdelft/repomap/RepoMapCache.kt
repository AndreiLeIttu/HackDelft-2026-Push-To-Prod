package com.hackdelft.repomap

import com.intellij.openapi.diagnostic.logger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Tiny on-disk cache for built maps, under ~/.repomap/cache/. Keyed by the set of classes
 * (+ grouping mode/prompt version), so an unchanged project reuses the previous result and
 * skips the slow, paid LLM call and the dependency scan — including across IDE relaunches.
 */
object RepoMapCache {

    private val LOG = logger<RepoMapCache>()

    private fun dir(): Path = Paths.get(System.getProperty("user.home"), ".repomap", "cache")

    private fun sanitize(key: String): String = key.replace(Regex("[^A-Za-z0-9._-]"), "_")

    fun get(key: String): RepoMapBuilder.MapData? = try {
        val tree = dir().resolve("${sanitize(key)}.tree.json")
        val edges = dir().resolve("${sanitize(key)}.edges.json")
        if (Files.isRegularFile(tree) && Files.isRegularFile(edges)) {
            RepoMapBuilder.MapData(Files.readString(tree), Files.readString(edges))
        } else {
            null
        }
    } catch (t: Throwable) {
        LOG.warn("Repo map cache read failed", t)
        null
    }

    fun put(key: String, data: RepoMapBuilder.MapData) {
        try {
            val directory = dir()
            Files.createDirectories(directory)
            Files.writeString(directory.resolve("${sanitize(key)}.tree.json"), data.treeJson)
            Files.writeString(directory.resolve("${sanitize(key)}.edges.json"), data.edgesJson)
        } catch (t: Throwable) {
            LOG.warn("Repo map cache write failed", t)
        }
    }
}
