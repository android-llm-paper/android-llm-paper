package moe.reimu.models

import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.bson.types.ObjectId

@Serializable
data class BinderService(
    @SerialName("_id") @Contextual val id: ObjectId? = null,
    @Contextual val firmwareId: ObjectId,
    val name: String,
    val status: String,
    val entryPoint: JavaMethod?,
    val inBaseline: Boolean?,
    val isAccessible: Boolean,
)
