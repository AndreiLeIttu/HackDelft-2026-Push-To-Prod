package com.hackdelft.repomap

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefBrowser

class RepoMapToolWindowFactory : ToolWindowFactory {
    override fun shouldBeAvailable(project: Project): Boolean = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val browser = JBCefBrowser()
        val html = RepoMapHtml.render(RepoTreeSamples.sampleProjectJson(project.name))
        browser.loadHTML(html)

        val content = ContentFactory.getInstance()
            .createContent(browser.component, null, false)
        toolWindow.contentManager.addContent(content)
    }
}
