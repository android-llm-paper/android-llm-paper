package moe.reimu.models

import kotlinx.serialization.Serializable
import soot.SootMethod

@Serializable
data class JavaMethod(val name: String, val className: String, val signature: String) {
    constructor(method: SootMethod) : this(method.name, method.declaringClass.name, method.signature)
}
