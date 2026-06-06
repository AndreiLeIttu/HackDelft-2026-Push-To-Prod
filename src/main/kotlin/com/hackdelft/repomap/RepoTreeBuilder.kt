package com.hackdelft.repomap

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiManager

/**
 * Builds a multi-level architecture tree from the classes in a project.
 *
 * The tree has three conceptual levels of detail:
 *   repository  ->  semantic group (e.g. "Database / Persistence", "UI / UX")
 *               ->  package sub-groups (feature areas)
 *               ->  individual classes
 *
 * Level 1 (the semantic groups) is derived from each class's *meaning* — inferred from
 * its name and package using heuristics — so the top of the map reads like an
 * architecture overview ("API / Web", "Services", "Domain / Model", ...) regardless of
 * how the packages happen to be named. Deeper levels fall back to the package structure
 * for fine-grained detail.
 *
 * Deterministic and offline (no network). For sharper, truly semantic naming an optional
 * LLM pass can post-process this tree later.
 *
 * NOTE: must be called inside a read action (see callers).
 */
object RepoTreeBuilder {

    /** Lowercase tokens that should be fully upper-cased in labels. */
    private val ACRONYMS = setOf(
        "api", "id", "db", "ui", "io", "ml", "ai", "http", "https", "sql", "url", "uri",
        "dto", "dao", "jwt", "jpa", "orm", "css", "html", "json", "xml", "yaml", "cli",
        "sdk", "jvm", "os", "vm", "ftp", "tcp", "udp", "ip", "cpu", "gpu", "rest", "grpc",
        "ssh", "tls", "ssl", "cdn", "dns", "qa"
    )

    private val LOG = logger<RepoTreeBuilder>()

    private data class ClassInfo(val packageName: String, val className: String)

    /**
     * A semantic bucket. A class belongs to the first category whose [classKeywords]
     * appear in its name; failing that, the first whose [packageKeywords] match a
     * package segment. Class-name signal wins over package signal.
     */
    private class Category(
        val id: String,
        val label: String,
        val classKeywords: List<String> = emptyList(),
        val packageKeywords: List<String> = emptyList()
    )

    private val CATEGORIES: List<Category> = listOf(
        Category(
            "config", "Configuration",
            listOf("config", "configuration", "properties", "settings", "options"),
            listOf("config", "configuration", "settings")
        ),
        Category(
            "security", "Security / Auth",
            listOf("auth", "security", "token", "jwt", "login", "credential", "permission", "oauth", "principal", "guard"),
            listOf("security", "auth", "authentication", "authorization")
        ),
        Category(
            "persistence", "Database / Persistence",
            listOf("repository", "repo", "dao", "entity", "jdbc", "jpa", "hibernate", "migration", "schema"),
            listOf("repository", "repositories", "dao", "entity", "entities", "persistence", "db", "database", "jpa", "orm", "store", "stores")
        ),
        Category(
            "api", "API / Web",
            listOf("controller", "resource", "endpoint", "servlet", "router", "graphql", "webhook"),
            listOf("controller", "controllers", "api", "rest", "web", "endpoint", "endpoints", "route", "routes", "http")
        ),
        Category(
            "messaging", "Messaging / Events",
            listOf("event", "listener", "publisher", "subscriber", "consumer", "producer", "saga"),
            listOf("event", "events", "messaging", "message", "messages", "kafka", "queue", "pubsub", "stream")
        ),
        Category(
            "ui", "UI / UX",
            listOf("view", "component", "activity", "fragment", "screen", "page", "panel", "dialog", "widget", "layout", "window"),
            listOf("ui", "ux", "view", "views", "component", "components", "screen", "screens", "page", "pages", "widget", "gui", "frontend", "compose")
        ),
        Category(
            "client", "Networking / Clients",
            listOf("client", "gateway", "connector", "feign", "retrofit"),
            listOf("client", "clients", "gateway", "integration", "integrations", "external", "remote")
        ),
        Category(
            "service", "Services / Business Logic",
            listOf("service", "manager", "usecase", "interactor", "processor", "scheduler", "worker", "engine", "handler"),
            listOf("service", "services", "business", "usecase", "usecases")
        ),
        Category(
            "model", "Domain / Model",
            listOf("model", "dto", "pojo", "request", "response", "payload", "bean", "record"),
            listOf("model", "models", "domain", "dto", "dtos", "data", "pojo")
        ),
        Category(
            "util", "Utilities / Helpers",
            listOf("util", "utils", "helper", "factory", "builder", "converter", "mapper", "extension", "constants"),
            listOf("util", "utils", "helper", "helpers", "common", "shared", "support", "core")
        )
    )

    private val OTHER = Category("other", "Other")

    fun build(project: Project): RepoTreeNode {
        val classes = collectClasses(project)

        // Bucket classes by semantic category (preserving CATEGORIES order).
        val byCategory = LinkedHashMap<Category, MutableList<ClassInfo>>()
        for (info in classes) {
            byCategory.getOrPut(categorize(info)) { mutableListOf() }.add(info)
        }

        val categoryNodes = byCategory.entries
            .map { (category, members) -> buildCategoryNode(category, members) }
            .sortedByDescending { it.classCount }

        return RepoTreeNode(
            name = project.name.ifBlank { "Project" },
            kind = "repository",
            summary = summaryFor(classes.size, categoryNodes.size, path = "", isRoot = true),
            path = "",
            classCount = classes.size,
            children = categoryNodes
        )
    }

    private fun collectClasses(project: Project): List<ClassInfo> {
        val result = mutableListOf<ClassInfo>()
        val index = ProjectFileIndex.getInstance(project)
        val psiManager = PsiManager.getInstance(project)

        index.iterateContent { file ->
            if (!file.isDirectory &&
                index.isInSourceContent(file) &&
                !index.isInTestSourceContent(file)
            ) {
                try {
                    val psiFile = psiManager.findFile(file)
                    if (psiFile is PsiClassOwner) {
                        val packageName = psiFile.packageName
                        for (psiClass in psiFile.classes) {
                            val className = psiClass.name ?: continue
                            result += ClassInfo(packageName, className)
                        }
                    }
                } catch (t: Throwable) {
                    // A single unparseable / stale-stub file shouldn't break the whole map.
                    rethrowControlFlow(t)
                    LOG.warn("Skipping file during repo-map analysis: ${file.path}", t)
                }
            }
            true
        }
        return result
    }

    private fun categorize(info: ClassInfo): Category {
        val name = info.className.lowercase()
        CATEGORIES.firstOrNull { category -> category.classKeywords.any { name.contains(it) } }
            ?.let { return it }

        val segments = info.packageName.lowercase().split('.').toHashSet()
        CATEGORIES.firstOrNull { category -> category.packageKeywords.any { it in segments } }
            ?.let { return it }

        return OTHER
    }

    /** A semantic group node, with package-based sub-groups beneath it for finer detail. */
    private fun buildCategoryNode(category: Category, members: List<ClassInfo>): RepoTreeNode {
        val trie = PkgTrie()
        for (info in members) trie.insert(info.packageName, info.className)

        // Skip the common leading package chain so sub-groups start at the meaningful level.
        var effective = trie
        val basePackage = StringBuilder()
        while (effective.classNames.isEmpty() && effective.children.size == 1) {
            val (segment, child) = effective.children.entries.first()
            if (basePackage.isNotEmpty()) basePackage.append('.')
            basePackage.append(segment)
            effective = child
        }

        val basePath = basePackage.toString()
        val children = buildChildren(effective, basePath)
        val classCount = trie.totalClassCount()
        val subgroups = children.count { it.kind == "module" }

        return RepoTreeNode(
            name = category.label,
            kind = "module",
            summary = "${category.label} — " + summaryFor(classCount, subgroups, basePath, isRoot = false).trimStart(),
            path = basePath,
            classCount = classCount,
            children = children
        )
    }

    /** Converts a trie level into child nodes: groups first (largest first), then classes. */
    private fun buildChildren(trie: PkgTrie, parentPath: String): List<RepoTreeNode> {
        val groupNodes = trie.children.entries
            .map { (segment, child) -> convertGroup(segment, child, parentPath) }
            .sortedByDescending { it.classCount }

        val classNodes = trie.classNames.sorted().map { className ->
            RepoTreeNode(
                name = className,
                kind = "class",
                summary = "Class in " + parentPath.ifEmpty { "(default package)" },
                path = if (parentPath.isEmpty()) className else "$parentPath.$className",
                classCount = 1
            )
        }

        return groupNodes + classNodes
    }

    private fun convertGroup(segment: String, trie: PkgTrie, parentPath: String): RepoTreeNode {
        // Collapse single-child chains: database -> api becomes one "database.api" group.
        var label = segment
        var node = trie
        while (node.classNames.isEmpty() && node.children.size == 1) {
            val (childSegment, child) = node.children.entries.first()
            label = "$label.$childSegment"
            node = child
        }

        val fullPath = if (parentPath.isEmpty()) label else "$parentPath.$label"
        val children = buildChildren(node, fullPath)
        val classCount = node.totalClassCount()
        val subgroups = children.count { it.kind == "module" }

        return RepoTreeNode(
            name = prettify(label),
            kind = "module",
            summary = summaryFor(classCount, subgroups, fullPath, isRoot = false),
            path = fullPath,
            classCount = classCount,
            children = children
        )
    }

    private fun summaryFor(classCount: Int, subgroups: Int, path: String, isRoot: Boolean): String {
        val parts = mutableListOf<String>()
        parts += if (classCount == 1) "1 class" else "$classCount classes"
        if (subgroups > 0) parts += if (subgroups == 1) "1 subgroup" else "$subgroups subgroups"

        val location = when {
            path.isNotEmpty() -> " · package `$path`"
            isRoot -> ""
            else -> " · default package"
        }
        val prefix = if (isRoot) "Project architecture overview — " else ""
        return prefix + parts.joinToString(" · ") + location
    }

    /** "database.api" -> "Database API", "user_service" -> "User Service". */
    private fun prettify(segment: String): String {
        val words = segment
            .split('.', '_', '-')
            .flatMap { splitCamelCase(it) }
            .filter { it.isNotBlank() }
        if (words.isEmpty()) return segment
        return words.joinToString(" ") { word ->
            if (word.lowercase() in ACRONYMS) word.uppercase()
            else word.replaceFirstChar { it.uppercaseChar() }
        }
    }

    private val CAMEL = Regex("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")

    private fun splitCamelCase(token: String): List<String> =
        if (token.isEmpty()) emptyList() else token.split(CAMEL)

    /** Mutable intermediate structure: a prefix tree of package segments. */
    private class PkgTrie {
        val children = sortedMapOf<String, PkgTrie>()
        val classNames = mutableListOf<String>()

        fun insert(packageName: String, className: String) {
            var node = this
            if (packageName.isNotEmpty()) {
                for (segment in packageName.split('.')) {
                    node = node.children.getOrPut(segment) { PkgTrie() }
                }
            }
            node.classNames.add(className)
        }

        fun totalClassCount(): Int =
            classNames.size + children.values.sumOf { it.totalClassCount() }
    }
}
