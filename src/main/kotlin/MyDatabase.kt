package moe.reimu

import com.mongodb.client.model.Filters.and
import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.Indexes
import com.mongodb.kotlin.client.MongoClient
import com.mongodb.kotlin.client.MongoCollection
import moe.reimu.models.BinderInterface
import moe.reimu.models.BinderService
import moe.reimu.models.Firmware
import org.bson.types.ObjectId

class MyDatabase(url: String, private val druRun: Boolean) {
    private val client: MongoClient
    private val firmwareCollection: MongoCollection<Firmware>
    private val serviceCollection: MongoCollection<BinderService>
    private val interfaceCollection: MongoCollection<BinderInterface>

    init {
        client = MongoClient.create(url)
        val mainDb = client.getDatabase("binder_analyzer")

        // Initialize collections
        firmwareCollection = mainDb.getCollection("firmware")
        firmwareCollection.createIndex(eq(Firmware::fingerprint.name, 1))

        serviceCollection = mainDb.getCollection("binder_service")

        interfaceCollection = mainDb.getCollection("binder_interface")
        // Text index for searching
        interfaceCollection.createIndex(eq(BinderInterface::source.name, "text"))
        // fw id + service name + callee name
        interfaceCollection.createIndex(
            Indexes.ascending(
                BinderInterface::firmwareId.name,
                BinderInterface::serviceName.name,
                "${BinderInterface::callee.name}.name"
            )
        )
    }

    fun findOrInsertFirmware(firmware: Firmware): Firmware {
        val existing = firmwareCollection.find(eq(Firmware::fingerprint.name, firmware.fingerprint)).firstOrNull()
        if (existing != null) {
            return existing
        }
        val insertRes = firmwareCollection.insertOne(firmware)
        return firmware.copy(id = insertRes.insertedId!!.asObjectId().value)
    }

    fun findBaselineByRelease(release: Int): Firmware? {
        return firmwareCollection.find(
            and(
                eq(Firmware::release.name, release),
                eq(Firmware::isBaseline.name, true)
            )
        ).firstOrNull()
    }

    fun firmwareExists(firmwareId: ObjectId): Boolean {
        return firmwareCollection.find(eq("_id", firmwareId)).firstOrNull() != null
    }

    fun clearByFirmware(firmwareId: ObjectId) {
        if (druRun) {
            return
        }
        serviceCollection.deleteMany(eq(BinderService::firmwareId.name, firmwareId))
        interfaceCollection.deleteMany(eq(BinderInterface::firmwareId.name, firmwareId))
    }

    fun insertService(binderService: BinderService): ObjectId {
        if (druRun) {
            return ObjectId()
        }
        return serviceCollection.insertOne(binderService).insertedId!!.asObjectId().value
    }

    fun insertInterface(binderInterface: BinderInterface): ObjectId {
        if (druRun) {
            return ObjectId()
        }
        return interfaceCollection.insertOne(binderInterface).insertedId!!.asObjectId().value
    }

    fun existsServiceByName(firmwareId: ObjectId, serviceName: String): Boolean {
        return serviceCollection.find(
            and(
                eq(BinderService::firmwareId.name, firmwareId),
                eq(BinderService::name.name, serviceName)
            )
        ).firstOrNull() != null
    }

    fun existsInterfaceByNames(firmwareId: ObjectId, serviceName: String, calleeName: String): Boolean {
        return interfaceCollection.find(
            and(
                eq(BinderInterface::firmwareId.name, firmwareId),
                eq(BinderInterface::serviceName.name, serviceName),
                eq("${BinderInterface::callee.name}.name", calleeName)
            )
        ).firstOrNull() != null
    }
}