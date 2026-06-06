package com.hackdelft.repomap

object RepoTreeSamples {
    fun sampleProjectJson(projectName: String): String {
        val name = projectName.ifBlank { "Current Project" }.jsonString()
        return """
            {
              "name": $name,
              "kind": "repository",
              "summary": "High-level architecture view generated from an example tree. Replace this with Junie's repository tree output.",
              "children": [
                {
                  "name": "Plugin Shell",
                  "kind": "module",
                  "summary": "IntelliJ entry points, tool windows, actions, settings, and project lifecycle integration.",
                  "children": [
                    { "name": "Tool Window", "kind": "feature", "summary": "Hosts the interactive repository map inside the IDE." },
                    { "name": "Agent Bridge", "kind": "feature", "summary": "Future integration point for requesting a repository tree from Junie." }
                  ]
                },
                {
                  "name": "Repo Tree Model",
                  "kind": "module",
                  "summary": "Normalized tree data used by the visualization layer.",
                  "children": [
                    { "name": "Nodes", "kind": "concept", "summary": "Repository, module, package, class, file, and concern nodes." },
                    { "name": "Edges", "kind": "concept", "summary": "Ownership, dependency, and containment relationships." },
                    { "name": "Metadata", "kind": "concept", "summary": "Descriptions, confidence, file counts, and source paths." }
                  ]
                },
                {
                  "name": "Map Webview",
                  "kind": "module",
                  "summary": "Zoomable, pannable visual map that starts broad and allows progressive drill-down.",
                  "children": [
                    { "name": "Layout", "kind": "feature", "summary": "Places larger architectural areas before individual files." },
                    { "name": "Interaction", "kind": "feature", "summary": "Click, search, focus, zoom, and reset behavior." },
                    { "name": "Details Panel", "kind": "feature", "summary": "Explains the selected node without forcing users into source files immediately." }
                  ]
                }
              ]
            }
        """.trimIndent()
    }

    private fun String.jsonString(): String = buildString {
        append('"')
        for (character in this@jsonString) {
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (character.code < 0x20) {
                        append("\\u")
                        append(character.code.toString(16).padStart(4, '0'))
                    } else {
                        append(character)
                    }
                }
            }
        }
        append('"')
    }
}
