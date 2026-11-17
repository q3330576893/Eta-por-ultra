package io.github.mangi.eta.agent.voice

import android.graphics.Rect
import android.graphics.Region
import android.view.View
import io.github.mangi.eta.core.AndroidAgentLogger
import java.io.Closeable
import java.lang.reflect.Proxy
import org.lsposed.hiddenapibypass.HiddenApiBypass

/** Restricts the overlay's input region to measured Compose content, excluding IME and outer margins. */
internal class AssistantTouchableRegion(
    private val view: View,
    private val exempt: () -> Unit = { HiddenApiBypass.addHiddenApiExemptions("Landroid/view/ViewTreeObserver;", "Landroid/view/ViewTreeObserver\$InternalInsetsInfo;") },
) : Closeable {
    private var bounds = Rect()
    private var listener: Any? = null
    private var listenerClass: Class<*>? = null

    fun update(rect: Rect) {
        if (bounds == rect) return
        bounds = Rect(rect)
        view.requestLayout()
    }

    fun attach(): Boolean = runCatching {
        if (listener != null) return@runCatching true
        exempt()
        val observer = view.viewTreeObserver
        val type = Class.forName("android.view.ViewTreeObserver\$OnComputeInternalInsetsListener")
        val proxy = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { instance, method, args ->
            when (method.name) {
                "onComputeInternalInsets" -> {
                    args?.firstOrNull()?.let { info -> runCatching {
                        info.javaClass.getMethod("setTouchableInsets", Int::class.javaPrimitiveType).invoke(info, 3)
                        val region = info.javaClass.getField("touchableRegion").get(info) as Region
                        region.set(bounds)
                    }.onFailure { AndroidAgentLogger.warn("Assistant input-region update unavailable: ${it.javaClass.simpleName}") } }
                    null
                }
                "hashCode" -> System.identityHashCode(instance)
                "equals" -> instance === args?.firstOrNull()
                "toString" -> "EtaAssistantTouchableRegion"
                else -> null
            }
        }
        observer.javaClass.getMethod("addOnComputeInternalInsetsListener", type).invoke(observer, proxy)
        listenerClass = type
        listener = proxy
        true
    }.getOrElse {
        AndroidAgentLogger.warn("Assistant input-region setup unavailable: ${it.javaClass.simpleName}")
        false
    }

    override fun close() {
        val value = listener ?: return
        val type = listenerClass ?: return
        listener = null
        listenerClass = null
        runCatching {
            val observer = view.viewTreeObserver
            if (observer.isAlive) observer.javaClass.getMethod("removeOnComputeInternalInsetsListener", type).invoke(observer, value)
        }
    }
}
