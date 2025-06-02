package moe.reimu.binder

import soot.SootMethod
import java.io.File

data class ProcessedTransaction(
    val location: SootMethod,
    val callee: SootMethod,
    val source: String?,
    val dexSrc: Pair<File, String>?,
    val isCustom: Boolean,
    val isEmpty: Boolean
)