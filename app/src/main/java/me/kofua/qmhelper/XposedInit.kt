package me.kofua.qmhelper

import android.app.Application
import android.app.Instrumentation
import android.content.res.Resources
import android.os.Build
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import me.kofua.qmhelper.hook.*
import me.kofua.qmhelper.utils.*
import java.util.concurrent.atomic.AtomicBoolean

const val TARGET_PACKAGE = "com.tencent.qqmusic"

val classLoader get() = XposedInit.classLoader

/**
 * 模块入口（现代 libxposed，API 102）。
 *
 * 与上游 1.1.4 那版（`de.robv.android.xposed`）的对应关系：
 *
 * | 老 API                                   | 现在                                        |
 * | ---------------------------------------- | ------------------------------------------- |
 * | `assets/xposed_init` 里写入口类          | `META-INF/xposed/java_init.list`            |
 * | `IXposedHookZygoteInit#initZygote`       | **没有了**（zygote 注入已取消）             |
 * | `IXposedHookLoadPackage#handleLoadPackage` | `onPackageLoaded` / `onPackageReady`      |
 * | `XModuleResources.createInstance`        | 自己用 `AssetManager#addAssetPath` 拼       |
 * | `AndroidAppHelper.currentApplication()`  | `HookEnv.appContext`（在 onCreate 前拿到）  |
 *
 * 注入时机没变：仍然是"Application 即将创建"这一步落 hook，所以宿主侧的行为、初始化
 * 顺序都和以前一致（对 Q音 这种带 Tinker + Cyclone 的重型 App 很重要 —— 太早 hook 会把
 * 它的初始化顺序打乱，启动直接崩）。
 */
class XposedInit : XposedModule() {

    private val prepared = AtomicBoolean(false)

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        HookEnv.attach(this, param.processName)
        Log.d("模块已加载：process=${param.processName} api=$apiVersion framework=$frameworkName $frameworkVersion")
        Log.d("模块路径：${HookEnv.moduleApkPath}")
    }

    /** 包的默认 classloader 出现时触发（老 Android 上先于 onPackageReady）。 */
    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (param.packageName != TARGET_PACKAGE) return
        HookEnv.attach(this, HookEnv.processName)
        prepare(param.defaultClassLoader, param.packageName)
    }

    /** 真正可以工作的时间点：classloader 就绪、Application 还没创建。 */
    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != TARGET_PACKAGE) return
        HookEnv.attach(this, HookEnv.processName)
        prepare(param.classLoader, param.packageName)
    }

    private fun prepare(packageClassLoader: ClassLoader, packageName: String) {
        if (!prepared.compareAndSet(false, true)) return
        HookEnv.classLoader = packageClassLoader
        disableTinker()
        installApplicationHook(packageName)
    }

    /**
     * 挂在 `Instrumentation#callApplicationOnCreate` 上 —— 等价于老 API 的
     * "handleLoadPackage 之后、Application.onCreate 之前"。
     *
     * 这里只捕获 Context / 载资源 / 装 hook，**不动任何 Q音 的类**（除了 Tinker 那个必须
     * 提前关掉的开关），避免把宿主的初始化顺序打乱。
     */
    private fun installApplicationHook(packageName: String) = try {
        Instrumentation::class.java.hookBeforeMethod(
            "callApplicationOnCreate",
            Application::class.java,
        ) { param ->
            val application = param.args.getOrNull(0) as? Application ?: return@hookBeforeMethod
            HookEnv.appContext = application
            HookEnv.loadModuleResources(application)?.let { HookEnv.moduleResources = it }

            if (!HookEnv.processName.contains(":")) {
                if (shouldSaveLog) startLog()

                Log.d("QQMusic process launched ...")
                Log.d("QMHelper version: ${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) from ${HookEnv.moduleApkPath}${if (isBuiltIn) " (BuiltIn)" else ""}")
                Log.d("QQMusic version: ${getPackageVersion(packageName)} (${if (is64) "64" else "32"}bit)")
                Log.d("SDK: ${Build.VERSION.RELEASE}(${Build.VERSION.SDK_INT}); Phone: ${Build.BRAND} ${Build.MODEL}")
                Log.d("Config: ${sPrefs.all}")

                preloadProxyClasses()
                startHook(allHooks())
            }
        }
        Log.d("已挂 Instrumentation#callApplicationOnCreate")
    } catch (t: Throwable) {
        Log.e("挂 callApplicationOnCreate 失败")
        Log.e(t)
    }

    private fun allHooks(): List<BaseHook> {
        val debugHooks = if (BuildConfig.DEBUG) {
            listOf(SSLHook, DebugHook)
        } else listOf()
        val buildInHooks = if (isBuiltIn) {
            listOf(WebLoginHook)
        } else listOf()
        val normalHooks = listOf(
            SettingsHook,
            SplashHook,
            HomeTopTabHook,
            HomePageHook,
            CgiHook,
            CopyHook,
            MiscHook,
            CommonAdsHook,
            WebViewHook,
            JceHook,
        )
        return buildList {
            addAll(debugHooks)
            addAll(buildInHooks)
            addAll(normalHooks)
        }
    }

    private fun startHook(hooks: List<BaseHook>) {
        hooks.forEach {
            try {
                it.hook()
            } catch (t: Throwable) {
                Log.e(t)
                val errorMessage = t.message ?: ""
                BannerTips.error(string(R.string.hook_error, errorMessage))
            }
        }
    }

    private fun startLog() = try {
        if (logFile.exists()) {
            if (oldLogFile.exists())
                oldLogFile.delete()
            logFile.renameTo(oldLogFile)
        }
        logFile.delete()
        logFile.createNewFile()
        Runtime.getRuntime().exec(arrayOf("logcat", "-T", "100", "-f", logFile.absolutePath))
    } catch (e: Throwable) {
        Log.e(e)
        null
    }

    /**
     * 关掉 Tinker 的热修复加载器。
     *
     * `TinkerApplication#<init>(int tinkerFlags, ...)`：把第一个参数（tinkerFlags）改成 0，
     * Tinker 就不再尝试加载 patch dex —— 否则我们 hook 到的类可能是补丁里的，行为不可预期。
     * 这段和上游一致，只是老 API 的 `hookAllConstructors` 换成了新 shim 的同名函数。
     */
    private fun disableTinker() {
        runCatching {
            "com.tencent.tinker.loader.app.TinkerApplication".from(classLoader)
                ?.hookBeforeAllConstructors { param ->
                    if (param.args.isNotEmpty()) param.args[0] = 0
                }
        }.onFailure {
            Log.d("禁用 Tinker 失败（可能没有 Tinker）：${it.message}")
        }
    }

    companion object {
        /** 本模块 APK 的路径（老的 `modulePath`）。 */
        val modulePath: String get() = HookEnv.moduleApkPath

        /** 本模块 APK 的 Resources（老的 `moduleRes`）。 */
        val moduleRes: Resources get() = HookEnv.moduleResources
            ?: throw IllegalStateException("模块资源尚未载入")

        /** 宿主的 classloader。 */
        val classLoader: ClassLoader get() = HookEnv.classLoader
            ?: throw IllegalStateException("宿主 classloader 尚未就绪")
    }
}