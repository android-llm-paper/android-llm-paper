package moe.reimu.oem

import soot.Body
import soot.BodyTransformer
import soot.jimple.InvokeExpr
import soot.jimple.Jimple
import soot.jimple.Stmt
import soot.jimple.internal.JInterfaceInvokeExpr
import soot.jimple.internal.JVirtualInvokeExpr

class MiuiStubTransformer : BodyTransformer() {
    override fun internalTransform(
        body: Body,
        phaseName: String,
        poptions: Map<String, String>
    ) {
        for (unit in body.units) {
            if (unit !is Stmt || !unit.containsInvokeExpr()) {
                continue
            }

            val ieb = unit.invokeExprBox
            val ie = ieb.value as InvokeExpr
            val origMethod = ie.method
            val mappedMethod = MiuiStubAnalyzer.mapToImpl(origMethod)
            if (mappedMethod == null) {
                continue
            }

            when (ie) {
                is JVirtualInvokeExpr -> {
                    ie.methodRef = mappedMethod.makeRef()
                }

                is JInterfaceInvokeExpr -> {
                    ieb.value = JVirtualInvokeExpr(
                        Jimple.cloneIfNecessary(ie.base),
                        mappedMethod.makeRef(),
                        ie.args.map { Jimple.cloneIfNecessary(it) }
                    )
                }

                else -> {
                    throw IllegalStateException("Unexpected InvokeExpr type: ${ie.javaClass}")
                }
            }
        }
    }

    companion object {
        private val instance: MiuiStubTransformer by lazy { MiuiStubTransformer() }
        fun v() = instance
    }
}