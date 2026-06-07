package com.hackdelft.repomap

/**
 * A single node in the hierarchical architecture tree.
 *
 * This is the contract the UI/UX team consumes. Every node has:
 *  - [name]       human-friendly label (e.g. "Database API")
 *  - [kind]       "repository" (root) | "module" (a group/package) | "class" (a leaf)
 *  - [summary]    short description shown in the details panel
 *  - [path]       fully-qualified package/class path (useful for navigation)
 *  - [classCount] total number of classes contained in this subtree
 *  - [children]   nested nodes (groups within groups, then classes)
 *
 * Levels of detail map naturally onto the tree depth: the root is the whole repo,
 * each level down narrows from a service-like group to sub-groups to individual classes.
 */
data class RepoTreeNode(
    val name: String,
    val kind: String,
    val summary: String,
    val path: String = "",
    val classCount: Int = 0,
    val children: List<RepoTreeNode> = emptyList()
) {
    /** Serializes this node (and all descendants) into the JSON the webview expects. */
    fun toJson(): String = StringBuilder().also { appendJson(it) }.toString()

    private fun appendJson(sb: StringBuilder) {
        sb.append('{')
        sb.append("\"name\":").append(Json.str(name))
        sb.append(",\"kind\":").append(Json.str(kind))
        sb.append(",\"summary\":").append(Json.str(summary))
        sb.append(",\"path\":").append(Json.str(path))
        sb.append(",\"classCount\":").append(classCount)
        if (children.isNotEmpty()) {
            sb.append(",\"children\":[")
            children.forEachIndexed { index, child ->
                if (index > 0) sb.append(',')
                child.appendJson(sb)
            }
            sb.append(']')
        }
        sb.append('}')
    }
}

/**
 * Re-throws control-flow exceptions (cancellation) so cancellable read actions can restart.
 * Call this first in broad `catch (t: Throwable)` blocks before swallowing the error.
 */
internal fun rethrowControlFlow(t: Throwable) {
    if (t is com.intellij.openapi.progress.ProcessCanceledException) throw t
    if (t is kotlin.coroutines.cancellation.CancellationException) throw t
}

/** Minimal JSON string escaping so we don't pull in a serialization dependency. */
internal object Json {
    fun str(value: String): String = buildString {
        append('"')
        for (c in value) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) {
                    append("\\u").append(c.code.toString(16).padStart(4, '0'))
                } else {
                    append(c)
                }
            }
        }
        append('"')
    }
}
