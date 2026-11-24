package io.github.guanhui999.fakelocgum.xposed

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import io.github.guanhui999.fakelocgum.core.Keys
import io.github.guanhui999.fakelocgum.xposed.hooks.AntiMockHooks
import io.github.guanhui999.fakelocgum.xposed.hooks.DynamicLoaderHooks
import io.github.guanhui999.fakelocgum.xposed.hooks.FrameworkLocationHooks
import io.github.guanhui999.fakelocgum.xposed.hooks.LateArmHooks
import io.github.guanhui999.fakelocgum.xposed.hooks.ListenerHooks
import io.github.guanhui999.fakelocgum.xposed.hooks.GnssHealthHooks
import io.github.guanhui999.fakelocgum.xposed.hooks.LocationHooks
import io.github.guanhui999.fakelocgum.xposed.hooks.MapSdkHooks
import io.github.guanhui999.fakelocgum.xposed.hooks.PlayServicesHooks
import io.github.guanhui999.fakelocgum.xposed.hooks.GpsHealthHooks
import io.github.guanhui999.fakelocgum.xposed.hooks.SourceLockHooks
import io.github.guanhui999.fakelocgum.xposed.hooks.SensorHooks
import io.github.guanhui999.fakelocgum.xposed.hooks.WebBridgeHooks

/**
 * LibXposed entry point. Configuration acquisition and hook installation are
 * deliberately independent: a temporary remote-preference failure must not make
 * this process look as if the module was never injected.
 */
class ModuleEntry : XposedModule() {

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        HookKit.bind(this)
        HookState.bindProcess(param.processName)
        HookKit.info(TAG, "module loaded process=${param.processName ?: "<unknown>"}")
    }

    /**
     * Remote preferences are normally available here. If LSPosed is still
     * starting, keep the state object alive with a safe disabled snapshot; the
     * retry in onPackageReady can acquire the same group before hooks are used.
     */
    override fun onPackageLoaded(param: PackageLoadedParam) {
        HookKit.info(TAG, "package loaded process=${HookState.processName()} package=${param.packageName}")
        // Retain a supplier: a one-shot ready retry still left late-bound services dead forever.
        HookState.connect(param.packageName) { getRemotePreferences(Keys.REMOTE_GROUP) }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        val packageName = param.packageName
        HookKit.info(
            TAG,
            "package ready process=${HookState.processName() ?: "<unknown>"} package=$packageName first=${param.isFirstPackage} prefs=${HookState.hasPreferences()}",
        )

        if (!HookState.hasPreferences()) HookState.reload()

        // Hooks are process-wide; only the first package announcement installs the
        // set. The state/logging path above still runs for every package.
        if (!param.isFirstPackage) return

        val loader = param.classLoader
        HookKit.info(TAG, "scope decision package=$packageName ${HookState.selfCheck()}")

        when (packageName) {
            "android", "system" -> {
                HookKit.info(TAG, "system_server hooks for $packageName")
                installSystemHooks(loader)
            }

            "com.android.providers.settings" -> {
                if (!HookKit.firstTime("settings-provider")) return
                install("AntiMockHooks") { AntiMockHooks.installSettingsProvider(loader) }
            }

            "com.android.phone" -> Unit

            else -> {
                if (!HookKit.firstTime("app")) return
                // Keep the original order: framework getters and listeners first,
                // SDK/result hooks next, late-loader arms last.
                install("LocationHooks") { LocationHooks.install(loader) }
                install("ListenerHooks") { ListenerHooks.install(loader) }
                install("PlayServicesHooks") { PlayServicesHooks.install(loader) }
                install("AntiMockHooks") { AntiMockHooks.installApp(loader) }
                install("SourceLockHooks") { SourceLockHooks.install(loader) }
                install("GpsHealthHooks") { GpsHealthHooks.install(loader) }
                install("GnssHealthHooks") { GnssHealthHooks.install(loader) }
                install("SensorHooks") { SensorHooks.install(loader) }
                install("MapSdkHooks") { MapSdkHooks.install(loader) }
                install("WebBridgeHooks") { WebBridgeHooks.install() }
                install("DynamicLoaderHooks") { DynamicLoaderHooks.install() }
                install("LateArmHooks") { LateArmHooks.install(loader) }
                HookKit.info(TAG, "app hook set installed package=$packageName ${HookState.selfCheck()}")
            }
        }
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        HookKit.info(TAG, "system_server starting process=${HookState.processName() ?: "<unknown>"}")
        HookState.connect("android") { getRemotePreferences(Keys.REMOTE_GROUP) }
        installSystemHooks(param.classLoader)
    }

    private fun installSystemHooks(loader: ClassLoader) {
        if (!HookKit.firstTime("system")) return
        install("FrameworkLocationHooks") { FrameworkLocationHooks.install(loader) }
        install("AntiMockHooks") { AntiMockHooks.installSystemServer(loader) }
        HookKit.info(TAG, "system hook set installed ${HookState.selfCheck()}")
    }

    private inline fun install(name: String, block: () -> Unit) {
        runCatching(block)
            .onSuccess { HookKit.info(TAG, "installer completed=$name") }
            .onFailure { HookKit.error(TAG, "installer failed=$name; continuing remaining modules", it) }
    }

    private companion object {
        const val TAG = "Entry"
    }
}
