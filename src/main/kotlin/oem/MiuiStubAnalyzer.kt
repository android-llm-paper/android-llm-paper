package moe.reimu.oem

import org.slf4j.LoggerFactory
import soot.RefType
import soot.Scene
import soot.SootClass
import soot.SootFieldRef
import soot.SootMethod

object MiuiStubAnalyzer {
    private val LOGGER = LoggerFactory.getLogger(MiuiStubAnalyzer::class.java)

    data class ImplClass(val clz: SootClass, val instanceFieldRef: SootFieldRef?)

    private lateinit var implClasses: List<ImplClass>

    fun collectProviders() {
        val scene = Scene.v()

        val classes = mutableListOf<ImplClass>()
        if (scene.containsClass("com.miui.base.MiuiStubRegistry")) {
            val implProvider = scene.loadClassAndSupport("com.miui.base.MiuiStubRegistry\$ImplProvider")
            val providers = scene.activeHierarchy.getImplementersOf(implProvider)
            for (prov in providers) {
                if (scene.containsClass("${prov.name}\$SINGLETON")) {
                    val singleton = scene.loadClassAndSupport("${prov.name}\$SINGLETON")
                    if (singleton.declaresFieldByName("INSTANCE")) {
                        val instanceField = singleton.getFieldByName("INSTANCE")
                        val instanceClass = (instanceField.type as RefType).sootClass
                        val fieldRef = instanceField.makeRef()
                        classes.add(ImplClass(instanceClass, fieldRef))
                        continue
                    }
                }

                LOGGER.warn("Cannot find singleton instance for $prov, scanning methods instead")

                for (meth in prov.methods) {
                    val rt = meth.returnType
                    // We somehow got 2 methods with the sam                        e name and signature, so we need to filter them out
                    if (meth.name == "provideNewInstance" && rt is RefType && rt != RefType.v("java.lang.Object")) {
                        classes.add(ImplClass(rt.sootClass, null))
                    }
                }
            }
        }
        this.implClasses = classes
    }

    fun mapToImpl(method: SootMethod): SootMethod? {
        val calleeClass = method.declaringClass

        if (!calleeClass.name.endsWith("Stub") || calleeClass.name.endsWith("\$Stub")) {
            return null
        }

        val hierarchy = Scene.v().activeHierarchy
        var ret: SootMethod? = null
        for (implClz in implClasses) {
            val clz = implClz.clz
            if (calleeClass.isInterface) {
                if (clz.implementsInterface(calleeClass.name)) {
                    ret = clz.getMethodUnsafe(method.name, method.parameterTypes, method.returnType)
                    break
                }
            } else if (hierarchy.isClassSubclassOf(clz, calleeClass)) {
                ret = clz.getMethodUnsafe(method.name, method.parameterTypes, method.returnType)
                break
            }
        }

        if (ret != null) {
            LOGGER.info("Mapped $method to $ret")
        }

        return ret
    }
}