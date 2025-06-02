package moe.reimu


import soot.SootMethod
import soot.Unit
import soot.jimple.Stmt
import soot.jimple.internal.JAssignStmt
import soot.jimple.internal.JEqExpr
import soot.jimple.internal.JIfStmt
import soot.jimple.internal.JLookupSwitchStmt
import soot.jimple.internal.JNeExpr
import soot.jimple.internal.JNewArrayExpr
import soot.shimple.Shimple
import soot.toolkits.graph.Block
import soot.toolkits.graph.BriefBlockGraph
import soot.toolkits.graph.CompleteBlockGraph
import soot.toolkits.graph.ExceptionalUnitGraph

import moe.reimu.binder.FoundTransaction
import moe.reimu.oem.MiuiStubTransformer


fun processBlock(method: SootMethod, block: Block): SootMethod? {
    val currentClass = method.declaringClass
    if (!currentClass.hasOuterClass()) {
        return null
    }
    val outerClass = currentClass.outerClass

    for (unit in block) {
        if (unit is Stmt && unit.containsInvokeExpr()) {
            val ie = unit.invokeExpr
            val callingMethod = ie.method

            if (
                callingMethod.declaringClass == currentClass &&
                callingMethod.name.startsWith("onTransact\$") &&
                callingMethod.name.endsWith("\$")
            ) {
                // Actual transaction has been extracted into a separate method
                val targetBody = callingMethod.retrieveActiveBody()
                val targetGraph = CompleteBlockGraph(targetBody)
                if (targetGraph.heads.isNotEmpty()) {
                    return processBlock(callingMethod, targetGraph.heads[0])
                } else {
                    logger.warn("Failed to find head of $callingMethod")
                }
            }

            if (callingMethod.declaringClass == outerClass || callingMethod.declaringClass == currentClass) {
                return ie.method
            }
        }
    }

    val tail = block.tail
    if (tail is JIfStmt && isGe0(tail.condition) && block.succs.size == 2 && block.succs[0].succs == block.succs[1].succs) {
        val target = tail.target
        if (target is JAssignStmt && target.rightOp is JNewArrayExpr && block.succs[0].succs.size == 1) {
            return processBlock(method, block.succs[0].succs[0])
        }
    }

    return null
}

fun parseCustomBinder(method: SootMethod, codeParamIndex: Int): Map<Int, FoundTransaction> {
    if (method.declaringClass.name == "android.os.Binder") {
        return emptyMap()
    }

    val ret = mutableMapOf<Int, FoundTransaction>()

    val body = Shimple.v().newBody(method.retrieveActiveBody())
    MiuiStubTransformer.Companion.v().transform(body)

    val codeLocalRef = body.parameterLocals[codeParamIndex]

    val localCfg = ExceptionalUnitGraph(body)
    val blocks = BriefBlockGraph(body)

    var heads = localCfg.heads.toMutableList()
    for (i in 0..100) {
        val newHeads = mutableListOf<Unit>()
        for (current in heads) {
            if (current is JLookupSwitchStmt && current.key == codeLocalRef) {
                for ((switchKey, dest) in current.lookupValues zip current.targets) {
                    if (switchKey.value == INTERFACE_TRANSACTION) {
                        // Ignore this
                        continue
                    }

                    val currentBlock = blocks.find { it.head == dest }
                    if (currentBlock == null) {
                        logger.warn("Failed to find basic block for $dest")
                        continue
                    }

                    val calledMethod = processBlock(method, currentBlock)
                    if (calledMethod != null) {
                        ret[switchKey.value] = FoundTransaction.Standard(method, calledMethod)
                    } else {
                        ret[switchKey.value] = FoundTransaction.Custom(method, body, currentBlock)
                        body.units
                    }
                }

                if (current.defaultTargetBox?.unit != null) {
                    newHeads.add(current.defaultTargetBox.unit)
                }

                continue
            }

            if (current is JIfStmt && current.condition.useBoxes.find { it.value == codeLocalRef } != null) {
                val condition = current.condition
                val succs = localCfg.getSuccsOf(current)

                val fallSucc = succs.find { it != current.target }!!
                val targetSucc = succs.find { it == current.target }!!

                when (condition) {
                    is JEqExpr -> {
                        // Positive branch contains code of this transaction, if eq goto target
                        newHeads.add(fallSucc) // This does not contain the transaction
                        val currentBlock = blocks.find { it.head == targetSucc }
                        if (currentBlock == null || currentBlock.preds.size != 1) {
                            // This is not a simple block
                            newHeads.add(targetSucc)
                            continue
                        }

                        val code = extractIntConstantFromCondition(condition)
                        if (code != null && code != INTERFACE_TRANSACTION) {
                            ret[code] = FoundTransaction.Custom(method, body, currentBlock)
                        }
                        continue
                    }

                    is JNeExpr -> {
                        // Negative branch contains code of this transaction, if ne goto
                        newHeads.add(targetSucc) // This does not contain the transaction
                        val currentBlock = blocks.find { it.head == fallSucc }
                        if (currentBlock == null || currentBlock.preds.size != 1) {
                            // This is not a simple block
                            newHeads.add(fallSucc)
                            continue
                        }

                        val code = extractIntConstantFromCondition(condition)
                        if (code != null && code != INTERFACE_TRANSACTION) {
                            ret[code] = FoundTransaction.Custom(method, body, currentBlock)
                        }
                        continue
                    }
                    // Anything else is not interesting
                }
            }

            if (current is Stmt && current.containsInvokeExpr()) {
                val ie = current.invokeExpr
                val argPosition = ie.args.indexOf(codeLocalRef)
                if (argPosition >= 0 && ie.method.name.lowercase().contains("transact")) {
                    // Delegate to onTransact
                    var calledMethod = current.invokeExpr.method

                    if (calledMethod.isConcrete) {
                        val parsed = parseCustomBinder(calledMethod, argPosition)
                        ret.putAll(parsed)
                    } else {
                        logger.warn("Method $calledMethod is not concrete")
                    }
                }
            }

            newHeads.addAll(localCfg.getSuccsOf(current))
        }

        if (newHeads.isEmpty()) {
            break
        }
        heads = newHeads
    }

    return ret
}
