package moe.reimu

import soot.Body
import soot.Local
import soot.PackManager
import soot.RefType
import soot.Scene
import soot.SootClass
import soot.SootField
import soot.SootMethod
import soot.Value
import soot.baf.BafASMBackend
import soot.jimple.IntConstant
import soot.jimple.InvokeExpr
import soot.jimple.Jimple
import soot.jimple.Stmt
import soot.jimple.internal.JAssignStmt
import soot.jimple.internal.JEqExpr
import soot.jimple.internal.JGeExpr
import soot.jimple.internal.JIdentityStmt
import soot.jimple.internal.JInstanceFieldRef
import soot.jimple.internal.JNeExpr
import soot.jimple.internal.JReturnStmt
import soot.jimple.internal.JReturnVoidStmt
import soot.jimple.internal.JimpleLocal
import soot.options.Options
import soot.shimple.Shimple
import soot.shimple.ShimpleBody
import soot.toolkits.graph.Block
import java.io.File
import java.util.LinkedList

fun findLocalRef(method: SootMethod, local: JimpleLocal): SootField? {
    val body = method.activeBody
    for (unit in body.units) {
        if (unit !is JAssignStmt) {
            continue
        }

        if (unit.leftOp != local) {
            continue
        }

        val rightOp = unit.rightOp
        if (rightOp is JInstanceFieldRef && rightOp.base == body.thisLocal) {
            return rightOp.field
        }
    }

    return null
}

fun findAssignmentTo(clz: SootClass, field: SootField): SootClass? {
    for (met in clz.methods) {
        if (met.isStatic) {
            continue
        }
        val body = met.activeBody
        for (unit in body.units) {
            if (unit !is JAssignStmt) {
                continue
            }

            val leftOp = unit.leftOp
            val rightOp = unit.rightOp

            if (leftOp is JInstanceFieldRef && leftOp.field == field) {
                return (rightOp.type as RefType).sootClass
            }
        }
    }

    return null
}

fun javaNameToDexName(javaName: String): String {
    return "L" + javaName.replace(".", "/") + ";"
}

fun soot.Hierarchy.resolveConcreteDispatchOrNull(clz: SootClass, target: SootMethod): SootMethod? {
    return try {
        resolveConcreteDispatch(clz, target)
    } catch (e: Exception) {
        logger.warn("Failed to resolve concrete dispatch for $clz", e)
        null
    }
}

fun callGraphOf(method: SootMethod): soot.jimple.toolkits.callgraph.CallGraph {
    Scene.v().entryPoints = listOf(method)
    try {
        PackManager.v().runPacks()
    } catch (e: Exception) {
        logger.warn("Failed to generate CG for $method", e)
    }
    return Scene.v().callGraph
}

enum class ReachableDirection {
    FORWARD,
    BACKWARD
}

fun Block.getReachableBlocks(direction: ReachableDirection): Set<Block> {
    val reachable = mutableSetOf<Block>()
    val queue = LinkedList<Block>()
    queue.add(this)

    while (queue.isNotEmpty()) {
        val current = queue.poll()
        if (reachable.add(current)) {
            // Not visited
            when (direction) {
                ReachableDirection.FORWARD -> queue.addAll(current.succs)
                ReachableDirection.BACKWARD -> queue.addAll(current.preds)
            }
        }
    }

    return reachable
}

fun extractIntConstantFromCondition(condition: Value): Int? {
    if (condition is JEqExpr) {
        if (condition.op1 is IntConstant) {
            return (condition.op1 as IntConstant).value
        }
        if (condition.op2 is IntConstant) {
            return (condition.op2 as IntConstant).value
        }
    }

    if (condition is JNeExpr) {
        if (condition.op1 is IntConstant) {
            return (condition.op1 as IntConstant).value
        }
        if (condition.op2 is IntConstant) {
            return (condition.op2 as IntConstant).value
        }
    }

    return null
}

fun isGe0(condition: Value): Boolean {
    if (condition is JGeExpr) {
        if (condition.op2 is IntConstant) {
            return (condition.op2 as IntConstant).value == 0
        }
    }
    return false
}

fun methodFromBasicBlock(
    originalMethod: SootMethod,
    originalBody: ShimpleBody,
    block: Block,
    name: String
): SootMethod {
    val newMethod = SootMethod(name, originalMethod.parameterTypes, originalMethod.returnType)
    val body = Shimple.v().newBody(newMethod)
    val units = body.units

    val forwardBlocks = block.getReachableBlocks(ReachableDirection.FORWARD)
    val backwardBlocks = block.getReachableBlocks(ReachableDirection.BACKWARD)
    val blocks = forwardBlocks.union(backwardBlocks)
    val oldUnits = blocks.flatMap { it }.sortedBy { originalBody.units.indexOf(it) }

    val localMap = mutableMapOf<Local, Local>()

    // Step 1: Copy Locals from the original method that are used in the subgraph
    for (unit in oldUnits) {
        for (box in unit.useAndDefBoxes) {
            val oldLocal = box.value
            if (oldLocal !is Local) {
                continue
            }
            if (localMap.containsKey(oldLocal)) {
                continue
            }
            val newLocal = JimpleLocal(oldLocal.name, oldLocal.type)
            localMap[oldLocal] = newLocal
            body.locals.add(newLocal)
        }
    }

    // Step 2: Copy Units from each block in the subgraph
    val unitMap = mutableMapOf<soot.Unit, soot.Unit>()
    for (unit in oldUnits) {
        val newUnit = unit.clone() as soot.Unit

        // Fix locals
        for (box in newUnit.useAndDefBoxes) {
            val oldLocal = box.value
            if (oldLocal !is Local) {
                continue
            }
            box.value = localMap[oldLocal]!!
        }

        units.add(newUnit)
        unitMap[unit] = newUnit
    }

    val fakeReturn = Jimple.v().newReturnVoidStmt()
    units.add(fakeReturn)

    // Step 3: Fix jumps
    for (unit in units.snapshotIterator()) {
        // Fix Phi
        Shimple.getPhiExpr(unit)?.let { expr ->
            for (unit in expr.preds) {
                val newUnit = unitMap[unit]
                if (newUnit != null) {
                    expr.setPred(expr.getArgIndex(unit), newUnit)
                } else {
                    // This pred is no longer reachable
                    expr.removeArg(unit)
                }
            }
        }

        when (unit) {
            is soot.jimple.IfStmt -> {
                val target = unit.target as soot.Unit
                val newTarget = unitMap[target]
                if (newTarget != null) {
                    unit.setTarget(newTarget)
                } else {
                    if (unit.containsInvokeExpr()) {
                        throw IllegalStateException("Cannot handle IfStmt with InvokeExpr")
                    }
                    units.remove(unit)
                }
            }

            is soot.jimple.GotoStmt -> {
                val target = unit.target as soot.Unit
                val newTarget = unitMap[target]
                if (newTarget != null) {
                    unit.setTarget(newTarget)
                } else {
                    units.remove(unit)
                }
            }

            is soot.jimple.LookupSwitchStmt -> {
                val newLookupValues = mutableListOf<IntConstant>()
                val newTargets = mutableListOf<soot.Unit>()

                for ((value, target) in unit.lookupValues zip unit.targets) {
                    val newTarget = unitMap[target]
                    if (newTarget != null) {
                        newLookupValues.add(value)
                        newTargets.add(newTarget)
                    }
                }

                var newDefault = if (unit.defaultTarget != null) {
                    unitMap[unit.defaultTarget]
                } else {
                    null
                }
                if (newDefault == null) {
                    newDefault = fakeReturn
                }

                val newStmt = Jimple.v().newLookupSwitchStmt(unit.key, newLookupValues, newTargets, newDefault)
                // Replace the old statement
                units.insertBefore(newStmt, unit)
                units.remove(unit)
            }
        }
    }
    newMethod.activeBody = body.toJimpleBody()
    return newMethod
}

fun SootClass.writeToFile(file: File) {
    val backend = BafASMBackend(this, Options.v().java_version())
    file.outputStream().use {
        backend.generateClassFile(it)
    }
}

fun getSingleInvokeExpr(body: soot.Body): InvokeExpr? {
    var res: InvokeExpr? = null
    val ignoredClasses = setOf(
        "android.util.Log",
        "android.util.Slog",
        "android.os.Binder",
        "android.os.Parcel",
        "android.os.UserHandle",
        "android.os.PermissionEnforcer",
        "android.os.RemoteCallbackList",
        "android.content.Context",
    )
    val ignoredMethods = setOf("getInstance", "asInterface", "printStackTrace")

    for (unit in body.units) {
        if (unit is Stmt && unit.containsInvokeExpr()) {
            val ie = unit.invokeExpr
            val mth = ie.method
            val clz = mth.declaringClass

            if (clz.name.startsWith("java.") || mth.name.startsWith("-\$\$Nest\$f") || mth.name.startsWith("-\$\$Nest\$sf")) {
                // Built-in classes or synthetic methods
                continue
            }

            val isMiuiStubGetInstance =
                clz.shortName.endsWith("Stub") && (mth.name == "getInstance" || mth.name == "get")
            if (clz.name in ignoredClasses || mth.name in ignoredMethods || isMiuiStubGetInstance) {
                continue
            }

            if (res != null) {
                // Multiple invoke exprs
                return null
            }
            res = unit.invokeExpr
        }
    }
    return res
}

/**
 * Extract the single concrete implementation from a list of implementations.
 * If there are no implementations, return null.
 * If there are multiple implementations, log a warning and return null.
 */
fun extractSingleImpl(origMethod: SootMethod, impls: List<SootMethod>): SootMethod? {
    return when (impls.size) {
        0 -> {
            logger.warn("No concrete implementation found for $origMethod")
            null
        }

        1 -> impls.first()
        in 2..5 -> {
            logger.warn("${impls.size} concrete implementations found for $origMethod: $impls")
            null
        }

        else -> {
            logger.warn("${impls.size} concrete implementations found for $origMethod")
            null
        }
    }
}

/**
 * Get the single concrete implementation of a method.
 */
fun getSingleImpl(method: SootMethod): SootMethod {
    if (method.isConcrete || method.isNative) {
        return method
    }

    if (method.declaringClass.isInterface) {
        val declIf = method.declaringClass
        val impls = Scene.v().activeHierarchy.getImplementersOf(method.declaringClass).mapNotNull {
            if (it.name.startsWith("${declIf.name}\$Stub") || it.name.startsWith("${declIf.name}\$Default")) {
                return@mapNotNull null
            }
            val implMth =
                it.getMethodUnsafe(method.name, method.parameterTypes, method.returnType) ?: return@mapNotNull null
            if (implMth.isConcrete) {
                implMth
            } else null
        }

        return extractSingleImpl(method, impls) ?: method
    }

    if (method.declaringClass.isAbstract) {
        val declClz = method.declaringClass
        val impls = Scene.v().activeHierarchy.getSubclassesOf(declClz).mapNotNull {
            val implMth =
                it.getMethodUnsafe(method.name, method.parameterTypes, method.returnType) ?: return@mapNotNull null
            if (implMth.isConcrete) {
                implMth
            } else null
        }

        return extractSingleImpl(method, impls) ?: method
    }

    return method
}

fun isBodyEmpty(body: Body): Boolean {
    for (unit in body.units.snapshotIterator()) {
        if (unit is Stmt && unit.containsInvokeExpr()) {
            val mth = unit.invokeExpr.method
            if (mth.declaringClass.name != "android.util.Log" && mth.declaringClass.name != "android.util.Slog") {
                return false
            }
            continue
        }

        when (unit) {
            is JIdentityStmt -> {}
            is JReturnStmt -> {}
            is JReturnVoidStmt -> {}
            else -> {
                return false
            }
        }
    }

    return true
}

fun SootMethod.isSyntheticBridge(): Boolean {
    return name.startsWith("-\$\$Nest\$")
}