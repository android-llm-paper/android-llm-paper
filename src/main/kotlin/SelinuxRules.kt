package moe.reimu

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.util.regex.Pattern

class SelinuxRules(baseDir: File) {
    val logger: Logger = LoggerFactory.getLogger(SelinuxRules::class.java)

    val allowedToUntrustedApp = mutableSetOf<String>()
    val typeAttributes = mutableMapOf<String, MutableSet<String>>()
    val serviceToContext = mutableMapOf<String, String>()

    init {
        val platServicesFile = File(baseDir, "plat_service_contexts")
        val platCilFile = File(baseDir, "plat_sepolicy.cil")
        val extServicesFile = File(baseDir, "system_ext_service_contexts")
        val extCilFile = File(baseDir, "system_ext_sepolicy.cil")

        if (platServicesFile.exists() && platCilFile.exists() && extServicesFile.exists() && extCilFile.exists()) {
            logger.info("Found all required SELinux files")

            parseServices(platServicesFile)
            parseServices(extServicesFile)

            parseCil(platCilFile)
            parseCil(extCilFile)

            println()
        }
    }

    private fun parseServices(file: File) {
        file.forEachLine {
            val matcher = SERVICE_PATTERN.matcher(it.trim())
            if (matcher.matches()) {
                val name = matcher.group(1)
                val context = matcher.group(2)
                serviceToContext[name] = context
            } else {
                logger.warn("Failed to parse service line: [$it]")
            }
        }
    }

    private fun parseCil(file: File) {
        file.forEachLine {
            val attrMatch = ATTR_PATTERN.matcher(it.trim())
            if (attrMatch.matches()) {
                val name = attrMatch.group(1)
                val ta = typeAttributes.getOrPut(name) { mutableSetOf() }
                ta.addAll(attrMatch.group(2).split(SPACE_PATTERN))
            }

            val allowMatch = ALLOW_PATTERN.matcher(it.trim())
            if (allowMatch.find()) {
                val target = allowMatch.group(1)
                allowedToUntrustedApp.add(target)
            }
        }
    }

    fun isAccessible(serviceName: String): Boolean {
        if (serviceName == "window") return true

        val context = serviceToContext[serviceName]
        if (context == null) {
            logger.warn("No context found for service: $serviceName")
            return false
        }

        if (allowedToUntrustedApp.contains(context)) {
            return true
        }

        for (name in allowedToUntrustedApp) {
            val typeAttrs = typeAttributes[name]
            if (typeAttrs != null && typeAttrs.contains(context)) {
                return true
            }
        }

        return false
    }

    companion object {
        private val SERVICE_PATTERN = Pattern.compile("^(.*?)\\s+u:object_r:(.*?):s0\$")
        private val ATTR_PATTERN = Pattern.compile("^\\(typeattributeset\\s+(.*?)\\s+\\(\\s*(.*?)\\s*\\)\\s*\\)\$")
        private val SPACE_PATTERN = Pattern.compile("\\s+")
        private val ALLOW_PATTERN = Pattern.compile("^\\(allow\\s+untrusted_app_all\\s+(.*?)\\s+\\(service_manager\\s+")
    }
}