package kotlinx.fuzz.jazzer

import java.lang.invoke.MethodHandle
import java.util.concurrent.atomic.AtomicReference
import kotlinx.fuzz.KFuzzerImpl

internal object JazzerTarget {
    private val target: AtomicReference<MethodHandle> = AtomicReference()
    private val instance: AtomicReference<Any> = AtomicReference()

    fun reset(target: MethodHandle, instance: Any) {
        this.target.set(target)
        this.instance.set(instance)
    }

    fun fuzzTargetOne(data: ByteArray) {
        target.get()(instance.get(), KFuzzerImpl(data))
    }
}
