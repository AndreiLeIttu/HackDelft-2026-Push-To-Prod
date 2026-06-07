package com.hackdelft.repomap

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project

/**
 * Uses an LLM to organize classes into a named, responsibility-based hierarchy.
 *
 * Sends compact class signatures (names + members + supertypes) and asks the model to
 * return a JSON tree of groups. The result is converted into [RepoTreeNode]s whose class
 * leaves carry the fully-qualified name as `path` (so dependency edges still line up).
 *
 * Returns null on any problem (no key, network/parse failure, empty result) so callers can
 * fall back to the deterministic [RepoTreeBuilder].
 */
object      LlmGrouping {

    private val LOG = logger<LlmGrouping>()
    private const val MAX_CLASSES = 500

    private const val SYSTEM =
        "You are a senior software architect organizing a project's classes into a clear, " +
        "navigable architecture map. Group classes by responsibility/domain and use-case " +
        "(what they do, judging from their names, members and supertypes). " +
        "Build a hierarchy with clearly distinct levels, using however many groups and levels " +
        "the project NATURALLY needs — do not pad or force any count. Two groups is fine if " +
        "that is what fits; more is fine if warranted.\n" +
        "- Each level must be clearly coarser than the level below it (broad areas higher up, " +
        "more specific groups lower down).\n" +
        "- Place classes ONLY inside the most specific (leaf) group; do not attach classes " +
        "directly to a higher-level group that also has sub-groups.\n" +
        "Group by responsibility/use-case from names, members and supertypes. Use concise, " +
        "human-friendly names (e.g. \"Order Checkout\", \"Authentication\", \"Database / " +
        "Persistence\"). Every class appears in exactly one leaf group. Respond with JSON only."

    /** Changes whenever the prompt changes, so the cache invalidates on prompt edits. */
    fun promptVersion(): Int = SYSTEM.hashCode()

    fun build(project: Project, signatures: List<ClassSignature>): RepoTreeNode? {
        if (signatures.isEmpty()) return null
        val limited = signatures.take(MAX_CLASSES)
        val byFqn = limited.associateBy { it.fqn }

        val prompt = userPrompt(limited)
        DebugDump.write("repo-map-llm-prompt.txt", "SYSTEM:\n$SYSTEM\n\nUSER:\n$prompt")

        val content = OpenAiClient.complete(SYSTEM, prompt) ?: return null
        DebugDump.write("repo-map-llm-response.json", content)

        return try {
            val root = JsonParser.parseString(content).asJsonObject
            val groupsArray = root.getAsJsonArray("groups") ?: return null

            val assigned = HashSet<String>()
            val groups = groupsArray
                .mapNotNull { convert(it.asJsonObject, limited, byFqn, assigned, "") }
                .filter { it.classCount > 0 }
                .sortedByDescending { it.classCount }
                .toMutableList()

            // Classes the model forgot to place go into a final "Other" group.
            val leftovers = limited.filter { it.fqn !in assigned }
            if (leftovers.isNotEmpty()) {
                groups += RepoTreeNode(
                    name = "Other",
                    kind = "module",
                    summary = "${leftovers.size} unclassified classes",
                    path = "Other",
                    classCount = leftovers.size,
                    children = leftovers.sortedBy { it.name }.map { classLeaf(it.fqn, it.name) }
                )
            }

            if (groups.isEmpty()) return null

            RepoTreeNode(
                name = project.name.ifBlank { "Project" },
                kind = "repository",
                summary = "AI-organized architecture · ${groups.sumOf { it.classCount }} classes · ${groups.size} groups",
                path = "",
                classCount = groups.sumOf { it.classCount },
                children = groups
            )
        } catch (t: Throwable) {
            LOG.warn("Failed to parse LLM grouping response", t)
            null
        }
    }

    private fun convert(
        json: JsonObject,
        list: List<ClassSignature>,
        byFqn: Map<String, ClassSignature>,
        assigned: MutableSet<String>,
        parentPath: String
    ): RepoTreeNode? {
        val name = json.get("name")?.takeIf { !it.isJsonNull }?.asString ?: "Group"
        val path = if (parentPath.isEmpty()) name else "$parentPath/$name"
        val summary = json.get("summary")?.takeIf { !it.isJsonNull }?.asString

        val childGroups = json.getAsJsonArray("children")?.mapNotNull {
            if (it.isJsonObject) convert(it.asJsonObject, list, byFqn, assigned, path) else null
        } ?: emptyList()

        val classLeaves = json.getAsJsonArray("classes")?.mapNotNull { element ->
            val signature = resolveClass(element, list, byFqn) ?: return@mapNotNull null
            if (!assigned.add(signature.fqn)) return@mapNotNull null
            classLeaf(signature.fqn, signature.name)
        } ?: emptyList()

        val kids = childGroups + classLeaves
        if (kids.isEmpty()) return null

        val count = kids.sumOf { it.classCount }
        return RepoTreeNode(
            name = name,
            kind = "module",
            summary = summary ?: "$count classes",
            path = path,
            classCount = count,
            children = kids
        )
    }

    /** Resolves a class reference that may be a 1-based index number or a fully-qualified name. */
    private fun resolveClass(
        element: com.google.gson.JsonElement,
        list: List<ClassSignature>,
        byFqn: Map<String, ClassSignature>
    ): ClassSignature? {
        if (element.isJsonNull) return null
        val index = if (element.isJsonPrimitive && element.asJsonPrimitive.isNumber) {
            element.asInt
        } else {
            element.asString.trim().toIntOrNull()
        }
        if (index != null) return list.getOrNull(index - 1)
        return byFqn[element.asString]
    }

    private fun classLeaf(fqn: String, name: String) = RepoTreeNode(
        name = name,
        kind = "class",
        summary = "Class · $fqn",
        path = fqn,
        classCount = 1
    )

    private fun userPrompt(signatures: List<ClassSignature>): String = buildString {
        appendLine("Group the numbered classes below. Output JSON of exactly this shape:")
        appendLine("""{"groups":[{"name":"...","summary":"...","children":[ ...same shape... ],"classes":[<class numbers>]}]}""")
        appendLine("Rules:")
        appendLine("- Reference each class by its NUMBER (the integer in brackets), NOT its name. \"classes\" must contain integers only.")
        appendLine("- Every class number below must appear in exactly one leaf group's \"classes\".")
        appendLine("- Use as many or as few groups and levels as the project naturally needs; don't force a count (2 groups is fine if that fits).")
        appendLine("- Put classes only inside leaf groups; each level should be clearly coarser than the one below it.")
        appendLine("- Use \"children\" for sub-groups where it helps. Group/name by responsibility/use-case.")
        appendLine()
        appendLine("Classes ([n] fqn | kind | methods | fields | extends/implements):")
        signatures.forEachIndexed { i, s ->
            append('[').append(i + 1).append("] ")
            append(s.fqn).append(" | ").append(s.kind)
            append(" | methods: ").append(s.methods.joinToString(",").ifEmpty { "-" })
            append(" | fields: ").append(s.fields.joinToString(",").ifEmpty { "-" })
            append(" | supers: ").append(s.supertypes.joinToString(",").ifEmpty { "-" })
            appendLine()
        }
    }
}
