package com.hackdelft.repomap

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.concurrency.AppExecutorUtil
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.util.concurrent.Callable

class RepoMapToolWindowFactory : ToolWindowFactory {

    private val log = logger<RepoMapToolWindowFactory>()

    override fun shouldBeAvailable(project: Project): Boolean = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val browser = JBCefBrowser()

        // Bridge: the webview calls window.openInIde(fqn) when a leaf is clicked;
        // resolve that class and open its source file in the editor.
        val openQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
        openQuery.addHandler { request ->
            openInIde(project, request)
            null
        }
        // Define window.openInIde after every page load (loadHTML runs twice:
        // the loading placeholder, then the real tree).
        val bridgeJs = "window.openInIde = function(p) { ${openQuery.inject("p")} };"
        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                if (cefBrowser != null) {
                    cefBrowser.executeJavaScript(bridgeJs, cefBrowser.url, 0)
                }
            }
        }, browser.cefBrowser)

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
                RepoMapBuilder.MapData(
                    RepoTreeNode(
                        name = "Repo Map failed",
                        kind = "repository",
                        summary = "Analysis threw: ${t.message ?: t.javaClass.simpleName}. See the IDE log (Help ▸ Show Log)."
                    ).toJson(),
                    "[]"
                )
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

    /**
     * Resolves a fully-qualified class name to its source and opens it in the editor.
     * PSI resolution runs off the EDT in a cancellable read action; navigation happens
     * back on the EDT. Project scope is tried first (the user's own code), then all scope.
     */
    private fun openInIde(project: Project, request: String) {
        val raw = request.trim()
        if (raw.isEmpty()) return

        // The webview sends "fqn|file". Prefer resolving the Java class (line-precise);
        // fall back to opening the source file directly (works for any language, e.g. C#).
        val separator = raw.indexOf('|')
        val fqn = (if (separator >= 0) raw.substring(0, separator) else raw).trim()
        val file = (if (separator >= 0) raw.substring(separator + 1) else "").trim()

        AppExecutorUtil.getAppExecutorService().execute {
            val target: PsiClass? = if (fqn.isEmpty()) null else try {
                ReadAction.nonBlocking(Callable {
                    val facade = JavaPsiFacade.getInstance(project)
                    val inProject = facade.findClass(fqn, GlobalSearchScope.projectScope(project))
                    val resolved = inProject ?: facade.findClass(fqn, GlobalSearchScope.allScope(project))
                    resolved?.takeIf { it.canNavigate() }
                }).inSmartMode(project).executeSynchronously()
            } catch (t: Throwable) {
                log.warn("Repo Map: failed to resolve class '$fqn'", t)
                null
            }

            if (target != null) {
                ApplicationManager.getApplication().invokeLater {
                    try {
                        target.navigate(true)
                    } catch (t: Throwable) {
                        log.warn("Repo Map: failed to open '$fqn'", t)
                    }
                }
                return@execute
            }

            // Fall back to opening the file by path.
            val virtualFile = if (file.isEmpty()) null else {
                LocalFileSystem.getInstance().findFileByPath(file.replace('\\', '/'))
            }
            if (virtualFile == null) {
                log.info("Repo Map: no navigable target for '$fqn' / '$file'")
                return@execute
            }
            ApplicationManager.getApplication().invokeLater {
                try {
                    FileEditorManager.getInstance(project).openFile(virtualFile, true)
                } catch (t: Throwable) {
                    log.warn("Repo Map: failed to open file '$file'", t)
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
