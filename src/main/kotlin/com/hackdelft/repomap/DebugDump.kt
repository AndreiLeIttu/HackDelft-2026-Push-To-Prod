package com.hackdelft.repomap

import com.intellij.openapi.diagnostic.logger
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Writes diagnostic artifacts (the LLM prompt/response and the final tree JSON) to
 * ~/.repomap/ so they can be inspected. Best-effort; never throws.
 */
object DebugDump {

    private val LOG = logger<DebugDump>()

    fun write(name: String, content: String) {
        try {
            val dir = Paths.get(System.getProperty("user.home"), ".repomap")
            Files.createDirectories(dir)
            val path = dir.resolve(name)
            Files.writeString(path, content)
            LOG.info("repo-map debug written: $path")
        } catch (t: Throwable) {
            LOG.warn("repo-map debug dump failed for $name", t)
        }
    }
}
