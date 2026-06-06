package com.hackdelft.repomap

object RepoMapHtml {
    fun render(repoTreeJson: String, repoEdgesJson: String = "[]"): String {
        val template = javaClass.getResource("/webview/repo-map.html")
            ?.readText()
            ?: error("Missing webview/repo-map.html resource")

        return template
            .replace("__REPO_TREE_JSON__", repoTreeJson)
            .replace("__REPO_EDGES_JSON__", repoEdgesJson)
    }
}
