package moe.reimu.models

import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.bson.types.ObjectId

@Serializable
data class BinderInterface(
    @SerialName("_id") @Contextual val id: ObjectId?,
    @Contextual val firmwareId: ObjectId,
    val serviceName: String,
    val interfaceCode: Int,
    val location: InterfaceLocation,
    val caller: JavaMethod,
    val callee: JavaMethod,
    val source: String?,
    val jimpleSource: String?,
    val isCustom: Boolean,
    val isEmpty: Boolean,
    val inBaseline: Boolean?,
    val isAccessible: Boolean,
)
