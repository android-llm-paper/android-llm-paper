package moe.reimu.binder

import moe.reimu.Decompiler
import moe.reimu.getSingleImpl
import moe.reimu.getSingleInvokeExpr
import moe.reimu.isBodyEmpty
import moe.reimu.isSyntheticBridge
import moe.reimu.javaNameToDexName
import moe.reimu.logger
import moe.reimu.methodFromBasicBlock
import moe.reimu.oem.MiuiStubAnalyzer
import moe.reimu.writeToFile
import soot.Body
import soot.Scene
import soot.SootClass
import soot.SootMethod
import soot.shimple.ShimpleBody
import soot.toolkits.graph.Block
import java.io.File

sealed class FoundTransaction {
    protected fun getSingleInvokeChain(body: Body): List<SootMethod> {
        val chain = mutableListOf<SootMethod>()
        val chainSet = mutableSetOf<SootMethod>()
        var currentBody = body

        for (i in 0 until 10) {
            val singleInvokeExpr = getSingleInvokeExpr(currentBody)
            if (singleInvokeExpr == null) {
                break
            }

            var invokeCallee = getSingleImpl(singleInvokeExpr.method)
            val miuiResolved = MiuiStubAnalyzer.mapToImpl(invokeCallee)
            if (miuiResolved != null) {
                invokeCallee = miuiResolved
            }

            if (!invokeCallee.isConcrete) {
                break
            }

            chain.add(invokeCallee)
            if (!chainSet.add(invokeCallee)) {
                // Loop detected
                break
            }

            currentBody = invokeCallee.retrieveActiveBody()
        }

        return chain
    }

    data class Standard(val location: SootMethod, val method: SootMethod) : FoundTransaction() {
        override fun process(
            code: Int,
            clz: SootClass,
            concreteClz: SootClass,
            classToDex: Map<String, Pair<File, String>>,
            dec: Decompiler,
            tempDir: File,
        ): ProcessedTransaction {
            var concreteCallee = method
            try {
                concreteCallee = Scene.v().activeHierarchy.resolveConcreteDispatch(concreteClz, method)
            } catch (e: Exception) {
                logger.warn("Could not resolve concrete dispatch of $method for $clz", e)
            }

            val dexClassName = javaNameToDexName(concreteCallee.declaringClass.name)
            val filePath = classToDex[dexClassName]

            var source = ""
            var isEmpty = false
            if (filePath != null && concreteCallee.isConcrete) {
                isEmpty = isBodyEmpty(concreteCallee.retrieveActiveBody())
                source = try {
                    dec.getSourceCode(concreteCallee)
                } catch (e: Exception) {
                    logger.error("Failed to decompile $concreteCallee", e)
                    ""
                }
            }

            if (source.isNotEmpty()) {
                val invokeChain = getSingleInvokeChain(concreteCallee.retrieveActiveBody())
                if (invokeChain.isNotEmpty()) {
                    logger.info("Found single invoke chain in $method: $invokeChain")
                }

                for (invokeCallee in invokeChain) {
                    val invokeDexClassName = javaNameToDexName(invokeCallee.declaringClass.name)
                    val invokeFilePath = classToDex[invokeDexClassName]

                    if (invokeFilePath != null && !invokeCallee.isSyntheticBridge()) {
                        try {
                            val invokeSource = dec.getSourceCode(invokeCallee)
                            source += "\n// ${invokeCallee}\n"
                            source += invokeSource
                        } catch (e: Exception) {
                            logger.warn("Failed to decompile single invoke target $invokeCallee", e)
                        }
                    }
                }
            }

            return ProcessedTransaction(
                location, concreteCallee, if (source.isEmpty()) {
                    null
                } else {
                    source
                }, filePath, false, isEmpty
            )
        }
    }

    data class Custom(val location: SootMethod, val body: ShimpleBody, val block: Block) : FoundTransaction() {
        override fun process(
            code: Int,
            clz: SootClass,
            concreteClz: SootClass,
            classToDex: Map<String, Pair<File, String>>,
            dec: Decompiler,
            tempDir: File,
        ): ProcessedTransaction {
            val dexClassName = javaNameToDexName(location.declaringClass.name)
            val filePath = classToDex[dexClassName]

            val methodName = "do_txn_code_${code}"
            val syntheicMethod = methodFromBasicBlock(location, body, block, methodName)
            location.declaringClass.addMethod(syntheicMethod)

            val shortName = location.declaringClass.shortName

            val classFileDir = File(tempDir, "synthetic_${shortName}_$code")
            classFileDir.mkdirs()

            val classFile = File(classFileDir, "${shortName}.class")
            syntheicMethod.declaringClass.writeToFile(classFile)

            var source = try {
                dec.getSourceCode(classFile, syntheicMethod)
            } catch (e: Exception) {
                logger.error("Failed to decompile synthetic $syntheicMethod", e)
                null
            }

            if (source != null) {
                val invokeChain = getSingleInvokeChain(syntheicMethod.retrieveActiveBody())
                if (invokeChain.isNotEmpty()) {
                    logger.info("Found single invoke chain in $syntheicMethod: $invokeChain")
                }

                for (invokeCallee in invokeChain) {
                    val invokeDexClassName = javaNameToDexName(invokeCallee.declaringClass.name)
                    val invokeFilePath = classToDex[invokeDexClassName]

                    if (invokeFilePath != null && !invokeCallee.isSyntheticBridge()) {
                        try {
                            val invokeSource = dec.getSourceCode(invokeCallee)
                            source += "\n// ${invokeCallee}\n"
                            source += invokeSource
                        } catch (e: Exception) {
                            logger.warn("Failed to decompile single invoke target $invokeCallee", e)
                        }
                    }
                }
            }

            return ProcessedTransaction(
                location, syntheicMethod, source, filePath, true, isBodyEmpty(syntheicMethod.retrieveActiveBody())
            )
        }
    }

    abstract fun process(
        code: Int,
        clz: SootClass,
        concreteClz: SootClass,
        classToDex: Map<String, Pair<File, String>>,
        dec: Decompiler,
        tempDir: File,
    ): ProcessedTransaction
}