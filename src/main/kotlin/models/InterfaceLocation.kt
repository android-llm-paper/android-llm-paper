package moe.reimu.models

import kotlinx.serialization.Serializable

@Serializable
data class InterfaceLocation(
    val className: String,
    val concreteClassName: String?,
    val filePath: String?,
)