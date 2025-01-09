package kotlinx.fuzz.junit

import com.code_intelligence.jazzer.agent.AgentInstaller
import com.code_intelligence.jazzer.driver.FuzzTargetHolder
import com.code_intelligence.jazzer.driver.FuzzTargetRunner
import com.code_intelligence.jazzer.driver.LifecycleMethodsInvoker
import com.code_intelligence.jazzer.driver.Opt
import com.code_intelligence.jazzer.utils.Log
import kotlinx.fuzz.KFuzzTest
import org.junit.platform.commons.support.AnnotationSupport
import org.junit.platform.commons.support.ReflectionSupport
import org.junit.platform.engine.*
import org.junit.platform.engine.discovery.ClassSelector
import org.junit.platform.engine.discovery.ClasspathRootSelector
import org.junit.platform.engine.discovery.MethodSelector
import org.junit.platform.engine.discovery.PackageSelector
import org.junit.platform.engine.support.descriptor.EngineDescriptor
import java.lang.reflect.Method
import java.net.URI
import java.util.concurrent.atomic.AtomicReference


class KFuzzJunitEngine : TestEngine {
    val isKFuzzTestContainer: (Class<*>) -> Boolean = { klass ->
        AnnotationSupport.isAnnotated(klass, KFuzzTest::class.java)
    }

    override fun getId(): String = "kotlinx.fuzz-test"

    override fun discover(
        discoveryRequest: EngineDiscoveryRequest,
        uniqueId: UniqueId
    ): TestDescriptor? {
        println("discovering...")
        val engineDescriptor = EngineDescriptor(uniqueId, "kotlinx.fuzz-test")
        discoveryRequest.getSelectorsByType(ClasspathRootSelector::class.java).forEach { selector ->
            appendTestsInClasspathRoot(selector.classpathRoot, engineDescriptor)
        }

        discoveryRequest.getSelectorsByType(PackageSelector::class.java).forEach { selector ->
            appendTestsInPackage(selector!!.packageName, engineDescriptor)
        }

        discoveryRequest.getSelectorsByType(ClassSelector::class.java).forEach { selector ->
            appendTestsInClass(selector!!.getJavaClass(), engineDescriptor)
        }

        discoveryRequest.getSelectorsByType(MethodSelector::class.java)

        discoveryRequest.getSelectorsByType(MethodSelector::class.java).filter { methodSelector ->
            AnnotationSupport.isAnnotated(methodSelector.javaMethod, KFuzzTest::class.java)
        }
            .groupBy({ it.javaClass }) { it.javaMethod }
            .forEach { (javaClass, methods) ->
                val classDescriptor = ClassTestDescriptor(javaClass, engineDescriptor)
                engineDescriptor.addChild(classDescriptor)
                methods.forEach { method ->
                    classDescriptor.addChild(MethodTestDescriptor(method, classDescriptor))
                }
            }

        return engineDescriptor
    }

    private fun appendTestsInClasspathRoot(uri: URI, engineDescriptor: EngineDescriptor) {
        ReflectionSupport.findAllClassesInClasspathRoot(uri, isKFuzzTestContainer) { true }
            .map { klass -> ClassTestDescriptor(klass, engineDescriptor) }
            .forEach { testDescriptor -> engineDescriptor.addChild(testDescriptor) }
    }

    private fun appendTestsInPackage(packageName: String?, engineDescriptor: TestDescriptor) {
        ReflectionSupport.findAllClassesInPackage(
            packageName, isKFuzzTestContainer
        ) { true } //
            .map { aClass -> ClassTestDescriptor(aClass!!, engineDescriptor) }  //
            .forEach { descriptor -> engineDescriptor.addChild(descriptor) }
    }

    private fun appendTestsInClass(javaClass: Class<*>, engineDescriptor: TestDescriptor) {
        engineDescriptor.addChild(
            ClassTestDescriptor(javaClass, engineDescriptor).also { it.addAllChildren() }
        )
    }

    override fun execute(request: ExecutionRequest) {
        val root = request.rootTestDescriptor
        root.children.forEach { child -> executeImpl(request, child) }
    }

    private fun executeImpl(request: ExecutionRequest, descriptor: TestDescriptor) {
        when (descriptor) {
            is ClassTestDescriptor -> {
                request.engineExecutionListener.executionStarted(descriptor)
                descriptor.children.forEach { child -> executeImpl(request, child) }
                request.engineExecutionListener.executionFinished(
                    descriptor,
                    TestExecutionResult.successful()
                )
            }

            is MethodTestDescriptor -> {
                request.engineExecutionListener.executionStarted(descriptor)
                val method = descriptor.testMethod
                val instance = method.declaringClass.kotlin.objectInstance!!

                val finding = jazzerDoFuzzing(instance, method)
                val result = if (finding == null) {
                    TestExecutionResult.successful()
                } else {
                    TestExecutionResult.failed(finding)
                }
                request.engineExecutionListener.executionFinished(descriptor, result)
            }
        }
    }

    private fun jazzerDoFuzzing(instance: Any, method: Method): Throwable? {
        val libFuzzerArgs = mutableListOf<String>("fake_argv0")
        val corpusDir = kotlin.io.path.createTempDirectory("jazzer-corpus")

        Log.fixOutErr(System.out, System.err)

        libFuzzerArgs += corpusDir.toString()
        libFuzzerArgs += "-max_total_time=10"
//        libFuzzerArgs += "-runs=10"
        libFuzzerArgs += "-rss_limit_mb=0"

        Opt.customHookExcludes.setIfDefault(
            listOf(
                "com.google.testing.junit.**",
                "com.intellij.**",
                "org.jetbrains.**",
                "io.github.classgraph.**",
                "junit.framework.**",
                "net.bytebuddy.**",
                "org.apiguardian.**",
                "org.assertj.core.**",
                "org.hamcrest.**",
                "org.junit.**",
                "org.opentest4j.**",
                "org.mockito.**",
                "org.apache.maven.**",
                "org.gradle.**"
            )
        )
//        Opt.instrument.setIfDefault(mutableListOf())
        Opt.hooks.setIfDefault(false)

        Opt.instrument.setIfDefault(listOf("kotlinx.fuzz.test.**"))

//        Opt.instrument.setIfDefault(determineInstrumentationFilters(extensionContext));
//        Opt.customHookIncludes.setIfDefault(Opt.instrument.get());
//        Opt.instrumentationIncludes.setIfDefault(Opt.instrument.get());

        AgentInstaller.install(Opt.hooks.get())

        FuzzTargetHolder.fuzzTarget = FuzzTargetHolder.FuzzTarget(
            method,
            LifecycleMethodsInvoker.noop(instance)
        )

        val atomicFinding = AtomicReference<Throwable>()
        FuzzTargetRunner.registerFatalFindingHandlerForJUnit { finding ->
            println("fatal finding handler invoked")
            atomicFinding.set(finding)
//            throw finding
        }


        // api stats ???
        val exitCode = FuzzTargetRunner.startLibFuzzer(libFuzzerArgs)
        return atomicFinding.get()
    }
}

