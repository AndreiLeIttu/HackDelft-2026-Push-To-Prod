package com.hackdelft.repomap

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import java.awt.Color
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
        // Explainability: open the child file and highlight the members the AI
        // cited for putting it in its group (payload is JSON: fqn/file/reason/evidence).
        val explainQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
        explainQuery.addHandler { request ->
            explainInIde(project, request)
            null
        }
        // Clear the evidence highlights painted by the last edge-click.
        val clearQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
        clearQuery.addHandler { _ ->
            ApplicationManager.getApplication().invokeLater { clearActiveHighlights() }
            null
        }
        // Explain a dependency edge: the AI describes how two groups communicate.
        // Answered asynchronously by calling back into window.onRelationExplained.
        val relationQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
        relationQuery.addHandler { request ->
            explainRelation(browser, request)
            null
        }
        // Define the bridges after every page load (loadHTML runs twice:
        // the loading placeholder, then the real tree).
        val bridgeJs =
            "window.openInIde = function(p) { ${openQuery.inject("p")} };" +
            "window.explainInIde = function(p) { ${explainQuery.inject("p")} };" +
            "window.clearHighlights = function() { ${clearQuery.inject("''")} };" +
            "window.explainRelation = function(p) { ${relationQuery.inject("p")} };"
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

    /** A file to open plus the member ranges (and caret) to highlight. */
    private data class ExplainTarget(val file: VirtualFile, val ranges: List<TextRange>, val caret: Int)

    /**
     * Opens the child file for a clicked edge and highlights the exact members the AI
     * cited as evidence for the grouping. Payload is JSON {fqn, file, reason, evidence[]}.
     * PSI resolution + range lookup run off the EDT; opening/highlighting on the EDT.
     */
    private fun explainInIde(project: Project, payload: String) {
        val request = try {
            JsonParser.parseString(payload).asJsonObject
        } catch (t: Throwable) {
            log.warn("Repo Map: bad explain payload", t)
            return
        }
        val fqn = request.get("fqn")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
        val file = request.get("file")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
        val evidence = request.getAsJsonArray("evidence")
            ?.mapNotNull { if (it.isJsonNull) null else it.asString.trim().ifEmpty { null } }
            ?.toHashSet()
            ?: hashSetOf()
        if (fqn.isEmpty() && file.isEmpty()) return

        AppExecutorUtil.getAppExecutorService().execute {
            val target: ExplainTarget? = try {
                ReadAction.nonBlocking(Callable { resolveExplainTarget(project, fqn, file, evidence) })
                    .inSmartMode(project).executeSynchronously()
            } catch (t: Throwable) {
                log.warn("Repo Map: failed to resolve explain target '$fqn'", t)
                null
            }
            if (target == null) {
                log.info("Repo Map: nothing to explain for '$fqn' / '$file'")
                return@execute
            }
            ApplicationManager.getApplication().invokeLater { openAndHighlight(project, target) }
        }
    }

    /** Read-action: resolve the class, collect the evidence members' ranges. */
    private fun resolveExplainTarget(
        project: Project,
        fqn: String,
        file: String,
        evidence: Set<String>
    ): ExplainTarget? {
        val facade = JavaPsiFacade.getInstance(project)
        val psiClass: PsiClass? = if (fqn.isEmpty()) null else
            facade.findClass(fqn, GlobalSearchScope.projectScope(project))
                ?: facade.findClass(fqn, GlobalSearchScope.allScope(project))

        val virtualFile = psiClass?.containingFile?.virtualFile
        if (psiClass != null && virtualFile != null) {
            val ranges = ArrayList<TextRange>()
            if (evidence.isNotEmpty()) {
                // Highlight the FULL member (all its lines), not just the name.
                for (method in psiClass.methods) {
                    if (method.name in evidence) ranges += method.textRange
                }
                for (field in psiClass.fields) {
                    if (field.name in evidence) ranges += field.textRange
                }
                psiClass.extendsList?.referenceElements?.forEach { ref ->
                    if (ref.referenceName != null && ref.referenceName in evidence) ranges += ref.textRange
                }
                psiClass.implementsList?.referenceElements?.forEach { ref ->
                    if (ref.referenceName != null && ref.referenceName in evidence) ranges += ref.textRange
                }
            }
            val caret = ranges.minOfOrNull { it.startOffset } ?: psiClass.textOffset
            return ExplainTarget(virtualFile, ranges, caret)
        }

        // No JVM class (e.g. another language): open the file by path, no highlight.
        val byPath = if (file.isEmpty()) null else
            LocalFileSystem.getInstance().findFileByPath(file.replace('\\', '/'))
        return byPath?.let { ExplainTarget(it, emptyList(), 0) }
    }

    // Highlights painted by the last edge-click, cleared before the next one so they
    // don't accumulate. Touched only on the EDT.
    private val activeHighlights = mutableListOf<Pair<Editor, RangeHighlighter>>()

    // Soft violet line background, adapting to light/dark themes.
    private val evidenceAttributes = TextAttributes().apply {
        backgroundColor = JBColor(Color(0xEA, 0xE3, 0xFF), Color(0x3B, 0x2F, 0x5C))
    }

    /** EDT: open the editor at the first evidence member and fill those lines with colour. */
    private fun openAndHighlight(project: Project, target: ExplainTarget) {
        try {
            val descriptor = OpenFileDescriptor(project, target.file, target.caret)
            val editor = FileEditorManager.getInstance(project).openTextEditor(descriptor, true) ?: return
            clearActiveHighlights() // drop the previous explanation's highlights
            if (target.ranges.isEmpty()) return
            val markup = editor.markupModel
            for (range in target.ranges) {
                val highlighter = markup.addRangeHighlighter(
                    range.startOffset,
                    range.endOffset,
                    HighlighterLayer.SELECTION - 1,
                    evidenceAttributes,
                    HighlighterTargetArea.LINES_IN_RANGE
                )
                activeHighlights += editor to highlighter
            }
        } catch (t: Throwable) {
            log.warn("Repo Map: failed to open/highlight explanation", t)
        }
    }

    private fun clearActiveHighlights() {
        for ((editor, highlighter) in activeHighlights) {
            try {
                if (!editor.isDisposed) editor.markupModel.removeHighlighter(highlighter)
            } catch (t: Throwable) {
                log.warn("Repo Map: failed to clear a highlight", t)
            }
        }
        activeHighlights.clear()
    }

    /**
     * Asks the AI how two groups communicate, then pushes the answer back into the webview
     * via window.onRelationExplained(id, text). Runs off the EDT; on any failure it returns a
     * plain computed summary so the popup always shows something.
     */
    private fun explainRelation(browser: JBCefBrowser, payload: String) {
        val request = try {
            JsonParser.parseString(payload).asJsonObject
        } catch (t: Throwable) {
            log.warn("Repo Map: bad relation payload", t)
            return
        }
        val id = request.get("id")?.takeIf { it.isJsonPrimitive }?.asInt ?: return

        AppExecutorUtil.getAppExecutorService().execute {
            val text = (try {
                RelationExplainer.explain(payload)
            } catch (t: Throwable) {
                log.warn("Repo Map: relation explanation failed", t)
                null
            }) ?: fallbackRelation(request)

            val js = "if (window.onRelationExplained) window.onRelationExplained($id, ${Gson().toJson(text)});"
            ApplicationManager.getApplication().invokeLater {
                try {
                    browser.cefBrowser.executeJavaScript(js, browser.cefBrowser.url, 0)
                } catch (t: Throwable) {
                    log.warn("Repo Map: failed to deliver relation explanation", t)
                }
            }
        }
    }

    private fun fallbackRelation(request: JsonObject): String {
        val source = request.get("source")?.takeIf { !it.isJsonNull }?.asString ?: "This group"
        val target = request.get("target")?.takeIf { !it.isJsonNull }?.asString ?: "another group"
        val count = request.get("count")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
        val refs = if (count == 1) "1 class-level reference" else "$count class-level references"
        return "$source uses $target ($refs)."
    }

    private fun loadingJson(): String = RepoTreeNode(
        name = "Analyzing project…",
        kind = "repository",
        summary = "Scanning classes, grouping them, and mapping dependencies."
    ).toJson()
}
