package moe.reimu.models

import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.bson.types.ObjectId
import java.io.File

@Serializable
data class Firmware(
    @SerialName("_id") @Contextual val id: ObjectId?,
    val brand: String,
    val product: String,
    val fingerprint: String,
    val securityPatch: String,
    val release: Int,
    val path: String,
    val isBaseline: Boolean,
) {
    companion object {
        fun fromDirectory(dir: File, isBaseline: Boolean): Firmware {
            return Firmware(
                id = null,
                brand = File(dir, "brand.txt").readText().trim(),
                product = File(dir, "product.txt").readText().trim(),
                fingerprint = File(dir, "fingerprint.txt").readText().trim(),
                securityPatch = File(dir, "security_patch.txt").readText().trim(),
                release = File(dir, "release.txt").readText().trim().toInt(),
                path = dir.absolutePath,
                isBaseline = isBaseline,
            )
        }
    }
}
