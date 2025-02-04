package kotlinx.fuzz.jazzer

import java.io.InputStream
import java.io.ObjectInputStream
import java.io.OutputStream
import java.lang.reflect.Method
import java.nio.file.Path
import kotlin.io.path.*
import kotlinx.fuzz.KFuzzConfig
import kotlinx.fuzz.KFuzzEngine
import kotlinx.fuzz.log.LoggerFacade
import kotlinx.fuzz.log.error

internal val Method.fullName: String
    get() = "${this.declaringClass.name}.${this.name}"

internal val KFuzzConfig.corpusDir: Path
    get() = workDir.resolve("corpus")

internal val KFuzzConfig.logsDir: Path
    get() = workDir.resolve("logs")

internal val KFuzzConfig.exceptionsDir: Path
    get() = workDir.resolve("exceptions")

@Suppress("unused")
class JazzerEngine(private val config: KFuzzConfig) : KFuzzEngine {
    private val log = LoggerFacade.getLogger<JazzerEngine>()
    private val jazzerConfig = JazzerConfig.fromSystemProperties()

    override fun initialise() {
        config.corpusDir.createDirectories()
        config.logsDir.createDirectories()
        config.exceptionsDir.createDirectories()
    }

    @OptIn(ExperimentalPathApi::class)
    override fun runTarget(instance: Any, method: Method): Throwable? {
        // spawn subprocess, redirect output to log and err files
        val classpath = System.getProperty("java.class.path")
        val javaCommand = System.getProperty("java.home") + "/bin/java"
        val properties = System.getProperties().map { (property, value) -> "-D$property=$value" }

        val pb = ProcessBuilder(
            javaCommand,
            "-classpath", classpath,
            *properties.toTypedArray(),
            JazzerLauncher::class.qualifiedName!!,
            method.declaringClass.name, method.name,
        )
        pb.redirectErrorStream(true)

        val process = pb.start()
        val stdoutFile = config.logsDir.resolve("${method.fullName}.log").toFile().outputStream()
        val stdoutThread = logProcessStream(process.inputStream, stdoutFile) {
            if (jazzerConfig.enableLogging) {
                log.info(it)
            }
        }
        val exitCode = process.waitFor()
        stdoutThread.join()

        return when (exitCode) {
            0 -> null
            else -> {
                val deserializedException = deserializeException(config.exceptionPath(method))
                deserializedException ?: run {
                    log.error { "Failed to deserialize exception for target '${method.fullName}'" }
                    Error("Failed to deserialize exception for target '${method.fullName}'")
                }
            }
        }
    }

    override fun finishExecution() {
        collectStatistics()
    }

    private fun collectStatistics() {
        val statsDir = config.workDir.resolve("stats").createDirectories()
        config.logsDir.listDirectoryEntries("*.err").forEach { file ->
            val csvText = jazzerLogToCsv(file, config.maxSingleTargetFuzzTime)
            statsDir.resolve("${file.nameWithoutExtension}.csv").writeText(csvText)
        }
    }

    private fun logProcessStream(
        inputStream: InputStream,
        outputStream: OutputStream,
        log: (String?) -> Unit,
    ): Thread = Thread {
        inputStream.bufferedReader().use { reader ->
            outputStream.bufferedWriter().use { writer ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    log(line)
                }
            }
        }
    }.also { it.start() }
}

internal fun KFuzzConfig.exceptionPath(method: Method): Path =
    exceptionsDir.resolve("${method.fullName}.exception")

/**
 * Reads a Throwable from the specified [path].
 */
private fun deserializeException(path: Path): Throwable? {
    path.inputStream().buffered().use { inputStream ->
        ObjectInputStream(inputStream).use { objectInputStream ->
            return objectInputStream.readObject() as? Throwable
        }
    }
}
