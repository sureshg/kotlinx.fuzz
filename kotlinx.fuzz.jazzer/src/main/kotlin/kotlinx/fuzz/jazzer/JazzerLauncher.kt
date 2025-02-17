package kotlinx.fuzz.jazzer

import com.code_intelligence.jazzer.agent.AgentInstaller
import com.code_intelligence.jazzer.driver.FuzzTargetHolder
import com.code_intelligence.jazzer.driver.FuzzTargetRunner
import com.code_intelligence.jazzer.driver.LifecycleMethodsInvoker
import com.code_intelligence.jazzer.driver.Opt
import com.code_intelligence.jazzer.utils.Log
import java.io.ObjectOutputStream
import java.lang.invoke.MethodHandles
import java.lang.reflect.Method
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.*
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.javaMethod
import kotlin.system.exitProcess
import kotlinx.fuzz.KFuzzConfig
import kotlinx.fuzz.RunMode
import kotlinx.fuzz.log.LoggerFacade
import kotlinx.fuzz.log.debug
import kotlinx.fuzz.log.error
import kotlinx.fuzz.log.warn

object JazzerLauncher {
    private val log = LoggerFacade.getLogger<JazzerLauncher>()
    private val config = KFuzzConfig.fromSystemProperties()
    private val jazzerConfig = JazzerConfig.fromSystemProperties()

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size != 2) {
            log.error { "Usage: <full.class.Name> <methodName>" }
            exitProcess(1)
        }
        // arg[0] - fully qualified name of the class containing fuzz target
        // arg[1] - method name of the fuzz target
        val className = args[0]
        val methodName = args[1]
        log.debug { "Running $className::$methodName" }

        val targetClass = Class.forName(className).kotlin
        val targetMethod = targetClass.memberFunctions.single { it.name == methodName }.javaMethod!!
        val instance = targetClass.objectInstance ?: targetClass.primaryConstructor!!.call()

        initJazzer()

        val error = runTarget(instance, targetMethod)
        error?.let {
            serializeException(error, config.exceptionPath(targetMethod))
            exitProcess(1)
        }
        exitProcess(0)
    }

    private fun configure(reproducerPath: Path, method: Method): List<String> {
        val libFuzzerArgs = mutableListOf("fake_argv0")
        val currentCorpus = config.corpusDir.resolve(method.fullName)
        currentCorpus.createDirectories()

        if (config.dumpCoverage) {
            val coverageFile = config.workDir
                .resolve("coverage")
                .createDirectories()
                .resolve("${method.fullName}.exec")
                .absolute()
                .toString()
            Opt.coverageDump.setIfDefault(coverageFile)
        }

        libFuzzerArgs += currentCorpus.toString()
        libFuzzerArgs += "-rss_limit_mb=${jazzerConfig.libFuzzerRssLimit}"
        libFuzzerArgs += "-artifact_prefix=${reproducerPath.absolute()}/"

        var keepGoing = when (RunMode.REGRESSION) {
            in config.runModes -> {
                val crashCount = reproducerPath.listCrashes().size
                if (crashCount == 0) {
                    log.warn { "No crashes found for regression mode at ${reproducerPath.absolute()}" }
                }
                crashCount.toLong()
            }
            else -> 0
        }
        if (config.runModes.contains(RunMode.FUZZING)) {
            libFuzzerArgs += "-max_total_time=${config.maxSingleTargetFuzzTime.inWholeSeconds}"
            keepGoing += config.keepGoing
        }

        Opt.keepGoing.setIfDefault(keepGoing)

        return libFuzzerArgs
    }

    @OptIn(ExperimentalPathApi::class)
    fun runTarget(instance: Any, method: Method): Throwable? {
        val reproducerPath =
            Path(Opt.reproducerPath.get(), method.declaringClass.simpleName, method.name).absolute()
        if (!reproducerPath.exists()) {
            reproducerPath.createDirectories()
        }

        val libFuzzerArgs = configure(reproducerPath, method)

        val atomicFinding = AtomicReference<Throwable>()
        FuzzTargetRunner.registerFatalFindingHandlerForJUnit { _, finding ->
            atomicFinding.set(finding)
        }

        JazzerTarget.reset(MethodHandles.lookup().unreflect(method), instance)

        if (config.runModes.contains(RunMode.REGRESSION)) {
            reproducerPath.listCrashes().forEach {
                FuzzTargetRunner.runOne(it.readBytes())
            }
        }

        if (config.runModes.contains(RunMode.FUZZING)) {
            FuzzTargetRunner.startLibFuzzer(libFuzzerArgs)
        }

        return atomicFinding.get()
    }

    fun initJazzer() {
        Log.fixOutErr(System.out, System.err)

        Opt.hooks.setIfDefault(config.hooks)
        Opt.instrumentationIncludes.setIfDefault(config.instrument)
        Opt.customHookIncludes.setIfDefault(config.instrument)
        Opt.customHookExcludes.setIfDefault(config.customHookExcludes)
        Opt.reproducerPath.setIfDefault(config.reproducerPath.absolutePathString())

        AgentInstaller.install(Opt.hooks.get())

        FuzzTargetHolder.fuzzTarget = FuzzTargetHolder.FuzzTarget(
            JazzerTarget::fuzzTargetOne.javaMethod,
            LifecycleMethodsInvoker.noop(JazzerTarget),
        )
    }
}

/**
 * Serializes [throwable] to the specified [path].
 */
private fun serializeException(throwable: Throwable, path: Path) {
    path.outputStream().buffered().use { outputStream ->
        ObjectOutputStream(outputStream).use { objectOutputStream ->
            objectOutputStream.writeObject(throwable)
        }
    }
}
