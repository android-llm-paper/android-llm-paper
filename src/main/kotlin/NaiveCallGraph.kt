package moe.reimu

import moe.reimu.oem.MiuiStubAnalyzer
import soot.Body
import soot.SootMethod
import soot.jimple.Stmt

class NaiveCallGraph(entryPoint: SootMethod, depth: Int) {
    val allMethods: List<SootMethod>

    init {
        val currentMethods = mutableListOf<SootMethod>(entryPoint)
        val allMethods = mutableListOf<SootMethod>()

        for (i in 0 until depth) {
            val nextMethods = mutableListOf<SootMethod>()
            // Process all methods in the current depth
            for (method in currentMethods) {
                for (res in processSingleBody(method)) {
                    if (!allMethods.contains(res)) {
                        // Not visited yet
                        nextMethods.add(res)
                    }
                }
            }
            // Add all new methods to the set
            for (mth in nextMethods) {
                if (!allMethods.contains(mth)) {
                    allMethods.add(mth)
                }
            }

            currentMethods.clear()
            currentMethods.addAll(nextMethods)
        }

        this.allMethods = allMethods.toList()
    }

    private fun processSingleBody(method: SootMethod): List<SootMethod> {
        val body = try {
            method.retrieveActiveBody()
        } catch (e: Exception) {
            return emptyList()
        }

        val ret = mutableListOf<SootMethod>()

        for (unit in body.units.snapshotIterator()) {
            if (unit !is Stmt || !unit.containsInvokeExpr()) {
                continue
            }

            val mth = unit.invokeExpr.method
            val pkgName = mth.declaringClass.packageName
            if (pkgName.startsWith("java.") || pkgName.startsWith("sun.") || pkgName.startsWith("android.")) {
                continue
            }
            val mapped = MiuiStubAnalyzer.mapToImpl(mth)
            if (mapped != null) {
                if (!ret.contains(mapped)) {
                    ret.add(mapped)
                }
            } else {
                if (!ret.contains(mth)) {
                    ret.add(mth)
                }
            }
        }

        return ret
    }
}