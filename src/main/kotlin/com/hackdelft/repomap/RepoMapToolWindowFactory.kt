package com.hackdelft.repomap

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.concurrency.AppExecutorUtil

class RepoMapToolWindowFactory : ToolWindowFactory {

    private val log = logger<RepoMapToolWindowFactory>()

    override fun shouldBeAvailable(project: Project): Boolean = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val browser = JBCefBrowser()

        val content = ContentFactory.getInstance()
            .createContent(browser.component, null, false)
        toolWindow.contentManager.addContent(content)

        // Placeholder while we analyze (PSI scan + optional AI grouping happen off the EDT).
        browser.loadHTML(RepoMapHtml.render(loadingJson()))

        AppExecutorUtil.getAppExecutorService().execute {
            val data = try {
                RepoMapBuilder.build(project)
            } catch (t: Throwable) {
                log.warn("Repo map build failed", t)
                RepoMapBuilder.MapData(RepoTreeSamples.sampleProjectJson(project.name), "[]")
            }
            ApplicationManager.getApplication().invokeLater {
                try {
                    browser.loadHTML(RepoMapHtml.render(data.treeJson, data.edgesJson))
                } catch (t: Throwable) {
                    log.warn("Failed to load repo map into the browser", t)
                }
            }
        }
    }

    private fun loadingJson(): String = RepoTreeNode(
        name = "Analyzing project…",
        kind = "repository",
        summary = "Scanning classes, grouping them, and mapping dependencies."
    ).toJson()
}
