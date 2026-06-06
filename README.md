# HackDelft 2026 - Repo Map

An IntelliJ plugin prototype that shows a high-level, zoomable map of a repository.

The project assumes an agent has already summarized the current repository into a tree-shaped model. This implementation focuses on the visualization layer: a `Repo Map` tool window inside IntelliJ that renders that tree as an interactive map.

## Current prototype

- Kotlin IntelliJ Platform plugin shell.
- `Repo Map` tool window hosted in the IDE.
- JCEF-backed HTML/CSS/JavaScript visualization.
- Dependency-free zoom, pan, search, reset, focus, and details-panel interactions.
- Sample tree data with a clear replacement point for a future Junie-generated tree.

## Why this shape?

The plugin-side code stays native to IntelliJ, while the map can evolve like a small web app. That makes it easier to prototype visual interactions without coupling them to the agent logic that will eventually produce the tree.

## Next integration point

Replace `RepoTreeSamples.sampleProjectJson(...)` with a service that calls the Junie-backed repository analysis flow and returns the same tree JSON shape:

```json
{
  "name": "Repository",
  "kind": "repository",
  "summary": "Short description",
  "children": []
}
```

## Development

Open the repository as a Gradle project in IntelliJ IDEA and run the `runIde` Gradle task from the IDE, or from a shell with Gradle installed:

```powershell
gradle runIde
```
