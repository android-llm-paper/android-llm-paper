package moe.reimu

import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import jadx.api.JavaMethod
import jadx.api.metadata.ICodeAnnotation
import jadx.api.metadata.annotations.NodeDeclareRef
import jadx.core.dex.attributes.AType
import jadx.core.dex.instructions.args.ArgType
import jadx.core.utils.exceptions.JadxRuntimeException
import org.slf4j.LoggerFactory
import soot.SootMethod
import java.io.File

class Decompiler(val inputFiles: List<File>) {
    private val defaultDecompiler: JadxDecompiler

    init {
        val jadxArgs = JadxArgs()
        jadxArgs.inputFiles = inputFiles
        jadxArgs.isShowInconsistentCode = true
        defaultDecompiler = JadxDecompiler(jadxArgs).apply {
            load()
        }
    }

    private fun getSourceCode(jadx: JadxDecompiler, method: SootMethod): String {
        val className = method.declaringClass.name.replace("$", ".")
        var clz = jadx.searchJavaClassByOrigFullName(className)
        if (clz == null) {
            // Try to find the class by the original name
            clz = jadx.searchJavaClassByOrigFullName(method.declaringClass.name)
        }
        if (clz == null) {
            throw RuntimeException("Cannot find class $className")
        }

        var codeClz = clz
        while (codeClz?.codeParent != null) {
            codeClz = codeClz.codeParent!!
        }

        val codeInfo = try {
            codeClz.codeInfo
        } catch (e: JadxRuntimeException) {
            throw RuntimeException("Failed to decompile $className", e)
        }
        val codeStr = codeInfo.codeStr!!
        val codeMeta = codeInfo.codeMetadata!!

        var jadxMethod = clz.getMethods().find {
            it.removeAlias()

            if (it.name != method.name || it.arguments.size != method.parameterTypes.size) {
                return@find false
            }

            for (i in it.arguments.indices) {
                if (!isSameType(it.arguments[i], method.parameterTypes[i])) {
                    return@find false
                }
            }

            true
        }
        if (jadxMethod == null) {
            throw RuntimeException("Cannot find method ${method.name} in $className")
        }

        if (jadxMethod.methodNode.contains(AType.METHOD_REPLACE)) {
            val replaced = jadxMethod.methodNode.get(AType.METHOD_REPLACE)
            if (replaced != null) {
                jadxMethod = replaced.replaceMth.javaNode
            }
        }

        val startPos = jadxMethod.defPos
        if (startPos == 0) {
            throw RuntimeException("Cannot find start position for method ${method.name} in $className")
        }
        var nesting = 0
        val endPos = codeMeta.searchDown(startPos) { pos, annotation ->
            when (annotation.annType) {
                ICodeAnnotation.AnnType.END -> {
                    nesting--
                    if (nesting == 0) {
                        return@searchDown pos
                    }
                }

                ICodeAnnotation.AnnType.DECLARATION -> {
                    val node = (annotation as NodeDeclareRef).node
                    if (node.annType == ICodeAnnotation.AnnType.CLASS || node.annType == ICodeAnnotation.AnnType.METHOD) {
                        nesting++
                    }
                }

                else -> {}
            }

            return@searchDown null
        }

        if (endPos == null) {
            throw RuntimeException("Cannot find end position for method ${method.name} in $className")
        }

        var sourceLines = codeStr.split("\n")
        var startLine = codeStr.substring(0, startPos).count { it == '\n' }
        var endLine = codeStr.substring(0, endPos).count { it == '\n' }
        return dedentText(sourceLines.subList(startLine, endLine + 1).joinToString("\n"))
    }

    /**
     * Get the source code of a method with the default decompiler.
     */
    fun getSourceCode(method: SootMethod): String {
        return getSourceCode(defaultDecompiler, method)
    }

    /**
     * Get the source code of a method with a specific file and a new decompiler.
     */
    fun getSourceCode(file: File, method: SootMethod): String {
        val jadxArgs = JadxArgs()
        jadxArgs.inputFiles = listOf(file)
        jadxArgs.isShowInconsistentCode = true
        val jadx = JadxDecompiler(jadxArgs).apply {
            load()
        }

        return getSourceCode(jadx, method)
    }

    private fun isSameType(a: ArgType, b: soot.Type): Boolean {
        val bStr = b.toString()
        if (a.toString() == bStr) {
            return true
        }
        if (a.isObject && a.`object` == bStr) {
            return true
        }
        return false
    }

    private fun dedentText(text: String): String {
        val lines = text.lines()
        if (lines.isEmpty()) {
            return ""
        }

        val firstLine = lines.first()
        val indent = firstLine.takeWhile { it.isWhitespace() }
        return lines.joinToString("\n") {
            it.removePrefix(indent)
        }
    }

    companion object {
        private val LOGGER = LoggerFactory.getLogger(Decompiler::class.java)
    }
}