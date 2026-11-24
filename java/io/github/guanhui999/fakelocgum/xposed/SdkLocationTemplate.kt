package io.github.guanhui999.fakelocgum.xposed

import java.lang.reflect.Method

/**
 * Copy a vendor's actual payload rather than manufacture an empty one that resets
 * satellite/GPS-quality fields. Prefer a public SDK copy constructor, then clone.
 * No guessed private fields, no hard-coded satellite quality.
 */
internal class SdkLocationTemplate(private val type: Class<*>) {
    private val copyConstructor = runCatching { type.getConstructor(type) }.getOrNull()
    private val cloneMethod: Method? = runCatching { type.getMethod("clone") }
        .getOrNull()?.takeIf { type.isAssignableFrom(it.returnType) || it.returnType == Any::class.java }
    @Volatile private var latest: Any? = null

    fun capture(value: Any) {
        if (!type.isInstance(value)) return
        copy(value)?.let { latest = it }
    }

    fun snapshot(): Any? = latest?.let(::copy)

    private fun copy(value: Any): Any? = runCatching {
        val copied = copyConstructor?.newInstance(value) ?: cloneMethod?.invoke(value)
        copied?.takeIf { type.isInstance(it) && it !== value }
    }.getOrNull()
}
