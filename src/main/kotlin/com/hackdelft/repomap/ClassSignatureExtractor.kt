package com.hackdelft.repomap

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiManager

/**
 * A compact, token-friendly description of a class: enough for an LLM to understand what
 * the class is responsible for (its members + supertypes), without sending source code.
 */
data class ClassSignature(
    val fqn: String,
    val packageName: String,
    val name: String,
    val kind: String,
    val methods: List<String>,
    val fields: List<String>,
    val supertypes: List<String>,
    val file: String = ""
)

object ClassSignatureExtractor {

    private const val MAX_MEMBERS = 16

    // Synthetic members the compiler generates (data-class plumbing, accessors, object
    // singletons). They add noise without telling the model what a class is *for*.
    private val SYNTHETIC_METHOD = Regex("^(component\\d+|copy|toString|hashCode|equals|writeReplace)$")
    private val ACCESSOR = Regex("^(get|set|is)[A-Z].*")
    private val SYNTHETIC_FIELD = setOf("INSTANCE", "Companion", "\$stable", "serialVersionUID")

    /** Must be called inside a read action. */
    fun extract(project: Project): List<ClassSignature> {
        val result = mutableListOf<ClassSignature>()
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
                            signatureOf(psiClass, packageName)?.let { result += it }
                        }
                    }
                } catch (t: Throwable) {
                    rethrowControlFlow(t) // Skip a single unparseable file, but honor cancellation.
                }
            }
            true
        }
        return result
    }

    private fun signatureOf(cls: PsiClass, packageName: String): ClassSignature? {
        val name = cls.name ?: return null
        // Skip Kotlin file-facade classes (top-level functions compiled into "<File>Kt").
        if (name.endsWith("Kt")) return null
        val fqn = cls.qualifiedName ?: if (packageName.isEmpty()) name else "$packageName.$name"

        val kind = when {
            cls.isAnnotationType -> "annotation"
            cls.isEnum -> "enum"
            cls.isInterface -> "interface"
            else -> "class"
        }

        val methods = cls.methods.asSequence()
            .filter { !it.isConstructor }
            .mapNotNull { it.name }
            .filter { !SYNTHETIC_METHOD.matches(it) && !ACCESSOR.matches(it) }
            .distinct()
            .take(MAX_MEMBERS)
            .toList()

        val fields = cls.fields.asSequence()
            .mapNotNull { it.name }
            .filter { it !in SYNTHETIC_FIELD && !it.startsWith("$") }
            .distinct()
            .take(MAX_MEMBERS)
            .toList()

        val supertypes = cls.supers.asSequence()
            .mapNotNull { it.name }
            .filter { it != "Object" }
            .distinct()
            .take(6)
            .toList()

        val file = cls.containingFile?.virtualFile?.path ?: ""
        return ClassSignature(fqn, packageName, name, kind, methods, fields, supertypes, file)
    }
}
