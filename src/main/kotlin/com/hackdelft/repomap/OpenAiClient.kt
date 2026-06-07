package com.hackdelft.repomap

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Duration

/**
 * Minimal OpenAI Chat Completions client. The API key is read from the OPENAI_API_KEY
 * environment variable (set it before launching the IDE). Never hardcode a key.
 */
object OpenAiClient {

    private val LOG = logger<OpenAiClient>()
    private const val ENDPOINT = "https://api.openai.com/v1/chat/completions"
    private const val MODEL = "gpt-4o"

    /**
     * Resolves the key, in order: OPENAI_API_KEY env var, `openai.api.key` system property,
     * then a file at ~/.repomap/openai_key. The file fallback works regardless of how the
     * IDE is launched (terminal, Gradle tool window, run configuration).
     */
    fun apiKey(): String? {
        System.getenv("OPENAI_API_KEY")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        System.getProperty("openai.api.key")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return readKeyFile()
    }

    private fun readKeyFile(): String? = try {
        val path = Paths.get(System.getProperty("user.home"), ".repomap", "openai_key")
        if (Files.isRegularFile(path)) Files.readString(path).trim().takeIf { it.isNotEmpty() } else null
    } catch (t: Throwable) {
        null
    }

    fun isConfigured(): Boolean = apiKey() != null

    /** Sends a chat completion and returns the assistant message content, or null on failure. */
    fun complete(systemPrompt: String, userPrompt: String): String? {
        val key = apiKey() ?: return null
        return try {
            val payload = JsonObject().apply {
                addProperty("model", MODEL)
                addProperty("temperature", 0.2)
                add("response_format", JsonObject().apply { addProperty("type", "json_object") })
                add("messages", JsonArray().apply {
                    add(message("system", systemPrompt))
                    add(message("user", userPrompt))
                })
            }

            val client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build()

            val request = HttpRequest.newBuilder()
                .uri(URI.create(ENDPOINT))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $key")
                .POST(HttpRequest.BodyPublishers.ofString(Gson().toJson(payload)))
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                LOG.warn("OpenAI request failed (${response.statusCode()}): ${response.body()?.take(300)}")
                return null
            }

            JsonParser.parseString(response.body()).asJsonObject
                .getAsJsonArray("choices").get(0).asJsonObject
                .getAsJsonObject("message").get("content").asString
        } catch (t: Throwable) {
            LOG.warn("OpenAI request error", t)
            null
        }
    }

    private fun message(role: String, content: String): JsonObject = JsonObject().apply {
        addProperty("role", role)
        addProperty("content", content)
    }
}
