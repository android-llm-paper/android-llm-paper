package moe.reimu.oem

import org.slf4j.LoggerFactory
import soot.Body
import soot.RefType
import soot.Scene
import soot.SootClass
import soot.jimple.ReturnStmt
import soot.jimple.Stmt

object SamsungServiceCreatorAnalyzer {
    private val LOGGER = LoggerFactory.getLogger(SamsungServiceCreatorAnalyzer::class.java)

    private var initialized = false
    private var interfaceClz: SootClass? = null

    fun analyze(body: Body, stmt: Stmt): SootClass? {
        if (initialized && interfaceClz == null || !stmt.containsInvokeExpr()) {
            return null
        }

        synchronized(this) {
            if (!initialized) {
                if (Scene.v().containsClass("android.os.IServiceCreator")) {
                    interfaceClz = Scene.v().loadClassAndSupport("android.os.IServiceCreator")
                }
                initialized = true
            }
        }

        val creatorClz = interfaceClz
        if (creatorClz == null) {
            return null
        }

        val ie = stmt.invokeExpr
        if (ie.method.parameterTypes.size != 2 || ie.method.parameterTypes[1] != creatorClz.type) {
            return null
        }

        val creatorImplClz = (ie.args[1].type as RefType).sootClass
        val createServiceMethod = creatorImplClz.getMethodByName("createService")

        val actualType = inferReturnType(createServiceMethod.retrieveActiveBody())
        if (actualType == null) {
            LOGGER.warn("Failed to infer return type of createService")
            return null
        }
        LOGGER.info("Inferred service type of $stmt: $actualType")
        return actualType
    }

    private fun inferReturnType(body: Body): SootClass? {
        for (unit in body.units) {
            if (unit !is ReturnStmt) {
                continue
            }
            val opType = unit.op.type
            if (opType is RefType && !opType.sootClass.isInterface) {
                return opType.sootClass
            }
        }
        return null
    }
}