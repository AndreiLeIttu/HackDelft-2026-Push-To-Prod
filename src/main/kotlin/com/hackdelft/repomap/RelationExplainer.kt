package com.hackdelft.repomap

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger

/**
 * Explains, in plain language, how one architecture group depends on another — used by the
 * "communicates with" popup when the user clicks a dependency edge.
 *
 * Input is the JSON the webview sends for a clicked edge: the two group names, the number of
 * class-level usages, and a sample of caller→callee class pairs. Output is one or two
 * sentences. Returns null on any failure so the caller can fall back to a plain summary.
 */
object RelationExplainer {

    private val LOG = logger<RelationExplainer>()

    private const val SYSTEM =
        "You are a software architect giving a developer a high-level mental model of a codebase. " +
        "Explain the relationship between two architectural areas in 2-3 clear, plain-English " +
        "sentences. Cover: (1) what capability the second area provides, (2) why the first area " +
        "relies on it, and (3) the nature of the interaction (e.g. reads/writes data, sends " +
        "requests, renders results, listens for events, configures it).\n" +
        "Stay high-level: describe roles and responsibilities, NOT individual class names, method " +
        "names, or call counts. Write for someone skimming the architecture, not reading code. " +
        "Be specific and concrete, but never invent details you cannot infer. Start directly with " +
        "the explanation (no \"This relationship...\" preamble). " +
        "Respond with JSON only: {\"explanation\":\"...\"}."

    /** Builds the explanation from the webview payload. */
    fun explain(payload: String): String? {
        val obj = try {
            JsonParser.parseString(payload).asJsonObject
        } catch (t: Throwable) {
            return null
        }
        val source = obj.string("source") ?: return null
        val target = obj.string("target") ?: return null
        val sourceSummary = obj.string("sourceSummary")
        val targetSummary = obj.string("targetSummary")
        val pairs = obj.getAsJsonArray("pairs")?.mapNotNull { element ->
            val pair = element.asJsonObject
            val caller = pair.string("s")?.substringAfterLast('.') ?: return@mapNotNull null
            val callee = pair.string("t")?.substringAfterLast('.') ?: return@mapNotNull null
            "$caller → $callee"
        } ?: emptyList()

        val user = buildString {
            appendLine("The area \"$source\" depends on the area \"$target\".")
            appendLine()
            appendLine("What \"$source\" is responsible for: ${sourceSummary ?: "(no description)"}")
            appendLine("What \"$target\" is responsible for: ${targetSummary ?: "(no description)"}")
            appendLine()
            if (pairs.isNotEmpty()) {
                appendLine("Concrete code dependencies observed (a class in the first area uses a class in the second):")
                pairs.take(20).forEach { appendLine("- $it") }
                appendLine()
            }
            appendLine("Explain, at a high level, how and why \"$source\" relies on \"$target\".")
        }

        val content = OpenAiClient.complete(SYSTEM, user) ?: return null
        return try {
            JsonParser.parseString(content).asJsonObject.string("explanation")
        } catch (t: Throwable) {
            LOG.warn("Failed to parse relation explanation", t)
            null
        }
    }

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { !it.isJsonNull }?.asString?.trim()?.ifEmpty { null }
}
