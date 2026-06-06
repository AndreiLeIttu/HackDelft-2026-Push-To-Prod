package com.hackdelft.repomap

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Tools -> "Export Repository Tree (JSON)".
 *
 * Analyzes the project (AI-organized when a key is set) and writes the architecture tree
 * plus dependency edges to `repo-map.tree.json` in the project root, as:
 *   { "tree": <node>, "edges": [ {"source": "...", "target": "..."} ] }
 */
class ExportRepoTreeAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Building repository tree", true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true

                    val data = RepoMapBuilder.build(project)
                    val json = "{\"tree\":${data.treeJson},\"edges\":${data.edgesJson}}"

                    val basePath = project.basePath
                    if (basePath == null) {
                        notify(project, "Could not determine the project base path.", NotificationType.ERROR)
                        return
                    }

                    val target = Paths.get(basePath, "repo-map.tree.json")
                    Files.writeString(target, json)
                    LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target)

                    notify(project, "Repository tree exported to $target", NotificationType.INFORMATION)
                }
            }
        )
    }

    private fun notify(project: Project, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Repo Map")
            .createNotification(message, type)
            .notify(project)
    }
}
