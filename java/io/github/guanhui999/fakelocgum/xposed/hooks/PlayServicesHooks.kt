package io.github.guanhui999.fakelocgum.xposed.hooks

import android.location.Location
import android.os.Looper
import io.github.guanhui999.fakelocgum.xposed.HookKit
import io.github.guanhui999.fakelocgum.xposed.HookState
import io.github.guanhui999.fakelocgum.xposed.LocationPayload
import io.github.guanhui999.fakelocgum.xposed.LiveLocationPusher
import io.github.guanhui999.fakelocgum.xposed.SdkDispatch
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executor

/** Keep the result hooks and push fresh fixes to successfully registered continuous callbacks. */
internal object PlayServicesHooks {
    private const val TAG = "Gms"
    private const val LOCATION_RESULT = "com.google.android.gms.location.LocationResult"
    private val resultClasses = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>()))
    private val clientClasses = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>()))
    private val factories = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>()))
    private val registrations = Collections.synchronizedMap(WeakHashMap<Any, Any>())

    fun install(classLoader: ClassLoader) {
        val resultClass = HookKit.findClass(classLoader, LOCATION_RESULT) ?: return
        if (resultClasses.add(resultClass)) {
            for (methodName in arrayOf("getLocations", "getLastLocation")) {
                HookKit.hookAllLogged(resultClass, methodName, TAG) { chain ->
                    val result = chain.proceed()
                    if (HookState.active()) LocationPayload.overwriteAll(result, "gms")
                    result
                }
            }
        }
        // Modern Play Services uses an abstract public client and a concrete implementation.
        // Hook its observed factory return, not guessed obfuscated implementation names.
        val factory = HookKit.findClass(classLoader, "com.google.android.gms.location.LocationServices")
        if (factory != null && factories.add(factory)) {
            HookKit.hookAllLogged(factory, "getFusedLocationProviderClient", TAG) { chain ->
                val client = chain.proceed()
                if (client != null) installClient(client.javaClass, classLoader, resultClass)
                client
            }
        }
        HookKit.findClass(classLoader, "com.google.android.gms.location.FusedLocationProviderClient")?.let {
            if (!Modifier.isAbstract(it.modifiers)) installClient(it, classLoader, resultClass)
        }
    }

    private fun installClient(client: Class<*>, loader: ClassLoader, resultClass: Class<*>) {
        if (!clientClasses.add(client)) return
        val callbackClass = HookKit.findClass(loader, "com.google.android.gms.location.LocationCallback")
        val listenerClass = HookKit.findClass(loader, "com.google.android.gms.location.LocationListener")
        val create = HookKit.findMethod(resultClass, "create", List::class.java)
        HookKit.hookAllLogged(client, "requestLocationUpdates", TAG) { chain ->
            val result = chain.proceed()
            val executor = chain.args.firstOrNull { it is Executor } as? Executor
            val looper = chain.args.firstOrNull { it is Looper } as? Looper
            for (listener in chain.args) {
                if (listener == null) continue
                val pusher = if (callbackClass?.isInstance(listener) == true && create != null) {
                    val callback = HookKit.findMethod(listener.javaClass, "onLocationResult", resultClass) ?: continue
                    LiveLocationPusher(listener, callback, executor, looper, "fused", create)
                } else if (listenerClass?.isInstance(listener) == true) {
                    val callback = HookKit.findMethod(listener.javaClass, "onLocationChanged", Location::class.java) ?: continue
                    LiveLocationPusher(listener, callback, executor, looper, "fused")
                } else continue
                val generation = Any()
                registrations[listener] = generation
                val weak = WeakReference(listener)
                onRegistrationComplete(result, loader) { success ->
                    val target = weak.get()
                    // A late Task completion cannot resurrect a cancelled subscription.
                    if (target != null && registrations[target] === generation) {
                        if (success) SdkDispatch.register(target, pusher)
                        else { registrations.remove(target); SdkDispatch.unregister(target) }
                    }
                }
            }
            result
        }
        HookKit.hookAllLogged(client, "removeLocationUpdates", TAG) { chain ->
            chain.args.filterNotNull().forEach { arg ->
                if (callbackClass?.isInstance(arg) == true || listenerClass?.isInstance(arg) == true) {
                    registrations.remove(arg)
                    SdkDispatch.unregister(arg)
                }
            }
            chain.proceed()
        }
    }

    private fun onRegistrationComplete(task: Any?, loader: ClassLoader, complete: (Boolean) -> Unit) {
        if (task == null) { complete(true); return }
        try {
            val taskClass = Class.forName("com.google.android.gms.tasks.Task", false, loader)
            if (!taskClass.isInstance(task)) { complete(false); return }
            val isComplete = taskClass.getMethod("isComplete")
            val success = taskClass.getMethod("isSuccessful")
            if (isComplete.invoke(task) == true) { complete(success.invoke(task) == true); return }
            val listenerClass = Class.forName("com.google.android.gms.tasks.OnCompleteListener", false, loader)
            val completion = Proxy.newProxyInstance(loader, arrayOf(listenerClass)) { proxy, method, args ->
                when (method.name) {
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    "toString" -> "FakeLoc registration completion"
                    "onComplete" -> {
                        val finished = args?.firstOrNull()
                        complete(finished != null && success.invoke(finished) == true)
                        null
                    }
                    else -> null
                }
            }
            taskClass.getMethod("addOnCompleteListener", listenerClass).invoke(task, completion)
        } catch (error: Throwable) {
            // Preserve original SDK operation if completion APIs differ; do not fake an accepted request.
            HookKit.error(TAG, "cannot verify continuous subscription; native callbacks still rewritten", error)
            complete(false)
        }
    }
}
