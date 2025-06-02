package moe.reimu

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.boolean
import com.github.ajalt.clikt.parameters.types.file
import io.github.cdimascio.dotenv.dotenv
import moe.reimu.models.BinderInterface
import moe.reimu.models.BinderService
import moe.reimu.models.Firmware
import moe.reimu.models.InterfaceLocation
import moe.reimu.models.JavaMethod
import moe.reimu.oem.MiuiStubAnalyzer
import moe.reimu.oem.SamsungServiceCreatorAnalyzer
import org.bson.types.ObjectId
import org.jf.dexlib2.DexFileFactory
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import soot.G
import soot.RefType
import soot.Scene
import soot.SootClass
import soot.SootMethod
import soot.Type
import soot.jimple.ClassConstant
import soot.jimple.Stmt
import soot.jimple.StringConstant
import soot.jimple.internal.JAssignStmt
import soot.jimple.internal.JVirtualInvokeExpr
import soot.jimple.internal.JimpleLocal
import soot.options.Options
import java.io.File
import java.util.*
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

val logger: Logger = LoggerFactory.getLogger("MAIN")

const val INTERFACE_TRANSACTION: Int = 1598968902 /* IBinder.INTERFACE_TRANSACTION ("_NTF") */  // 0x5f4e5446

fun findAsBinder(method: SootMethod, local: JimpleLocal): SootClass? {
    val body = method.activeBody
    for (unit in body.units) {
        if (unit !is JAssignStmt) {
            continue
        }

        val leftOp = unit.leftOp
        val rightOp = unit.rightOp
        if (leftOp == local && rightOp is JVirtualInvokeExpr && rightOp.method.name == "asBinder") {
            return (rightOp.base.type as RefType).sootClass
        }
    }

    return null
}

fun fillClassesMap(classes: MutableMap<String, Pair<File, String>>, file: File) {
    val container = DexFileFactory.loadDexContainer(file, null)
    for (entryName in container.dexEntryNames) {
        val entry = container.getEntry(entryName)?.dexFile!!
        val p = Pair(file, entryName)

        for (cls in entry.classes) {
            classes[cls.type] = p
        }
    }
}

class App : CliktCommand() {
    val inputDir by argument().file(mustExist = true, canBeFile = false, canBeDir = true, canBeSymlink = true)
    val isBaseline by option("-b", "--baseline").flag()
    val dryRun by option("-d", "--dry").flag()

    override fun run() {
        val logDir = File(inputDir, "logs")
        logDir.mkdirs()
        configureLogging(logDir)

        val dotenv = dotenv()
        val db = MyDatabase(dotenv["MONGODB_URL"], dryRun)

        val selinuxRules = SelinuxRules(inputDir)

        val firmware = db.findOrInsertFirmware(Firmware.fromDirectory(inputDir, isBaseline))
        db.clearByFirmware(firmware.id!!)

        val baselineId = if (isBaseline) {
            null
        } else {
            db.findBaselineByRelease(firmware.release)?.id
        }

        if (!isBaseline && baselineId == null) {
            throw IllegalArgumentException("Baseline firmware not found")
        }

        logger.info("Processing firmware ${firmware.fingerprint} ${if (isBaseline) "baseline" else "based on $baselineId"}")

        val bootcp = File(inputDir, "bootclasspath.txt").readText().split(':')
        val servercp = File(inputDir, "systemserverclasspath.txt").readText().split(':')
        val classToDex = mutableMapOf<String, Pair<File, String>>()
        val jadxInputFiles = mutableListOf<File>()

        val bootClasspath = mutableListOf<String>()
        for (p in bootcp) {
            val dir = File(inputDir, "bootcp")
            val realPath = File(dir, p.removePrefix("/").trim())
            jadxInputFiles.add(realPath)
            fillClassesMap(classToDex, realPath)
            bootClasspath.add(realPath.canonicalPath)
        }
        val serverClasspath = mutableListOf<String>()
        for (p in servercp) {
            val dir = File(inputDir, "systemservercp")
            val f = File(dir, p.removePrefix("/").trim())
            jadxInputFiles.add(f)
            fillClassesMap(classToDex, f)
            serverClasspath.add(f.canonicalPath)
        }

        G.reset()

        Options.v().set_allow_phantom_refs(true)
        Options.v().set_whole_program(true)
        Options.v().set_prepend_classpath(true)
        Options.v().set_src_prec(Options.src_prec_apk_c_j)
        Options.v().set_soot_classpath(bootClasspath.joinToString(File.pathSeparator))
        Options.v().set_process_multiple_dex(true)
        Options.v().set_process_dir(serverClasspath)
        Options.v().set_throw_analysis(Options.throw_analysis_dalvik)
        Options.v().set_search_dex_in_archives(true)
        Options.v().set_ignore_resolving_levels(true)
        Options.v().set_ignore_resolution_errors(true)
        Options.v().set_ignore_methodsource_error(true)
        Options.v().set_android_api_version(34)

        Scene.v().loadNecessaryClasses()

        val systemServiceClass = Scene.v().loadClassAndSupport("com.android.server.SystemService")
        val binderInterfaceClass = Scene.v().loadClassAndSupport("android.os.IBinder")
        val binderClass = Scene.v().loadClassAndSupport("android.os.Binder")
        val baseOnTransactMethod = binderClass.getMethodByName("onTransact")
        val publishedServices = mutableMapOf<String, SootClass>()
        val publishedServicesWithoutImplementation = mutableMapOf<String, SootClass>()
        val standardBinderClasses = mutableMapOf<Type, SootClass>()
        val hierarchy = Scene.v().activeHierarchy

        MiuiStubAnalyzer.collectProviders()

        val publishBinderServiceMethods = systemServiceClass.methods.filter {
            it.name == "publishBinderService"
        }
        val addServiceMethods = Scene.v().loadClassAndSupport("android.os.ServiceManager").methods.filter {
            it.name == "addService"
        }

        val invalidCalls = mutableListOf<Pair<SootMethod, Stmt>>()
        val validServices = mutableMapOf<String, SootClass>()

        fun addServiceLocked(name: String, clz: SootClass) {
            synchronized(validServices) {
                val existing = validServices[name]
                if (existing != null && existing != clz) {
                    logger.error("Service $name already registered to ${validServices[name]}, ignoring ${clz}")
                    return
                }
                validServices[name] = clz
            }
        }

        fun handleInvoke(method: SootMethod, unit: Stmt) {
            val expr = unit.invokeExpr
            val serviceName = (expr.getArg(0) as? StringConstant)?.value ?: return

            val arg1 = expr.getArg(1)
            if (arg1 is ClassConstant) {
                addServiceLocked(serviceName, (arg1.toSootType() as RefType).sootClass)
                return
            }

            val samInferredClz = SamsungServiceCreatorAnalyzer.analyze(method.activeBody, unit)
            if (samInferredClz != null) {
                addServiceLocked(serviceName, samInferredClz)
                return
            }

            val serviceClass = (arg1.type as RefType).sootClass
            if (serviceClass != binderInterfaceClass) {
                addServiceLocked(serviceName, serviceClass)
                return
            }

            synchronized(invalidCalls) {
                invalidCalls.add(Pair(method, unit))
            }
        }

        val workerQueue = LinkedBlockingQueue<Runnable>(128)
        val workerPool = ThreadPoolExecutor(4, 16, 100, TimeUnit.SECONDS, workerQueue)
        val futures = LinkedList<Future<*>>()

        for (clz in Scene.v().applicationClasses.snapshotIterator()) {
            if (clz.isInterface || clz.isPhantom) {
                continue
            }

            val task = Runnable {
                for (met in clz.methods) {
                    if (met.isAbstract || met.isNative || met.isPhantom) {
                        continue
                    }

                    met.retrieveActiveBody()

                    val body = met.activeBody

                    for (unit in body.units) {
                        if (unit !is Stmt || !unit.containsInvokeExpr()) {
                            continue
                        }

                        val expr = unit.invokeExpr
                        val targetMethod = expr.method

                        if (targetMethod.name == "publishBinderService") {
                            handleInvoke(met, unit)
                            continue
                        }

                        if (addServiceMethods.contains(targetMethod)) {
                            handleInvoke(met, unit)
                            continue
                        }
                    }
                }
            }

            while (true) {
                try {
                    val fut = workerPool.submit(task)
                    futures.add(fut)
                    break
                } catch (e: RejectedExecutionException) {
                    Thread.sleep(100)
                }
            }

        }

        workerPool.shutdown()
        workerPool.awaitTermination(1, TimeUnit.HOURS)

        for (fut in futures) {
            fut.get()
        }
        futures.clear()

        invalidCalls.removeIf { (method, unit) ->
            val expr = unit.invokeExpr
            val serviceName = (expr.getArg(0) as? StringConstant)?.value ?: return@removeIf false

            val asBinderBase = findAsBinder(method, expr.getArg(1) as JimpleLocal)
            if (asBinderBase != null && asBinderBase != binderInterfaceClass) {
                addServiceLocked(serviceName, asBinderBase)
                return@removeIf true
            }

            val localRefField = findLocalRef(method, expr.getArg(1) as JimpleLocal)
            if (localRefField != null) {
                val ty = findAssignmentTo(method.declaringClass, localRefField)
                if (ty != null && ty != binderInterfaceClass) {
                    addServiceLocked(serviceName, ty)
                    return@removeIf true
                }
            }

            false
        }

        val decompiler = Decompiler(jadxInputFiles)

        val tempDir = File(inputDir, "temp")
        tempDir.mkdirs()

        for ((serviceName, clz) in validServices) {
            val inBaseline = baselineId?.let {
                db.existsServiceByName(it, serviceName)
            }
            val isAccessible = selinuxRules.isAccessible(serviceName)
            if (!isAccessible) {
                logger.info("[!] $serviceName is not accessible by SELinux")
            }

            if (clz.isInterface) {
                logger.warn("[!] $serviceName $clz is an interface, IGNORING")
                db.insertService(
                    BinderService(
                        firmwareId = firmware.id,
                        name = serviceName,
                        status = "interface",
                        entryPoint = null,
                        inBaseline = inBaseline,
                        isAccessible = isAccessible,
                    )
                )
                continue
            }
            val className = clz.name
            var concreteClz = clz

            val concreteClassName = if (clz.isAbstract) {
                logger.info("[!] $serviceName $clz is abstract")
                val subclasses = hierarchy.getSubclassesOf(clz)
                when (subclasses.size) {
                    0 -> {
                        logger.warn("[!] $serviceName $clz has no concrete subclasses")
                    }

                    1 -> concreteClz = subclasses.first()
                    else -> {
                        logger.info("[!] $serviceName $clz has multiple concrete subclasses, picking first")
                        concreteClz = subclasses.firstOrNull { !it.isAbstract } ?: clz
                    }
                }
                concreteClz.name
            } else null

            val firstOnTransact = try {
                hierarchy.resolveConcreteDispatch(concreteClz, baseOnTransactMethod)
            } catch (e: Exception) {
                logger.error("Could not resolve concrete dispatch of onTransact for $concreteClz", e)
                db.insertService(
                    BinderService(
                        firmwareId = firmware.id,
                        name = serviceName,
                        status = "resolve_error",
                        entryPoint = null,
                        inBaseline = inBaseline,
                        isAccessible = isAccessible,
                    )
                )
                continue
            }

            if (firstOnTransact == baseOnTransactMethod) {
                logger.info("$serviceName $concreteClz does NOT respond to Binder calls")
                db.insertService(
                    BinderService(
                        firmwareId = firmware.id,
                        name = serviceName,
                        status = "no_binder",
                        entryPoint = null, inBaseline = inBaseline,
                        isAccessible = isAccessible,
                    )
                )
                continue
            }

            val parsed = parseCustomBinder(firstOnTransact, 0)
            if (parsed.isEmpty()) {
                logger.warn("Failed to find any code for $serviceName $concreteClz")
                db.insertService(
                    BinderService(
                        firmwareId = firmware.id,
                        name = serviceName,
                        status = "parse_error",
                        entryPoint = JavaMethod(firstOnTransact), inBaseline = inBaseline,
                        isAccessible = isAccessible,
                    )
                )
                continue
            }

            db.insertService(
                BinderService(
                    firmwareId = firmware.id,
                    name = serviceName,
                    status = "ok",
                    entryPoint = JavaMethod(firstOnTransact), inBaseline = inBaseline,
                    isAccessible = isAccessible,
                )
            )

            for ((code, method) in parsed) {
                val processed = try {
                    method.process(code, clz, concreteClz, classToDex, decompiler, tempDir)
                } catch (e: Exception) {
                    logger.error("Failed to process $method", e)
                    continue
                }

                val resultInterface = BinderInterface(
                    id = null,
                    firmwareId = firmware.id,
                    serviceName = serviceName,
                    interfaceCode = code,
                    location = InterfaceLocation(
                        className = className,
                        concreteClassName = concreteClassName,
                        filePath = processed.dexSrc?.first?.canonicalPath,
                    ),
                    caller = JavaMethod(processed.location),
                    callee = JavaMethod(processed.callee),
                    source = processed.source,
                    jimpleSource = if (processed.callee.hasActiveBody()) {
                        processed.callee.activeBody.toString()
                    } else {
                        null
                    },
                    isCustom = processed.isCustom,
                    isEmpty = processed.isEmpty,
                    inBaseline = baselineId?.let {
                        db.existsInterfaceByNames(it, serviceName, processed.callee.name)
                    },
                    isAccessible = isAccessible,
                )
                db.insertInterface(resultInterface)
            }
        }
    }
}


fun main(args: Array<String>) {
    App().main(args)
}