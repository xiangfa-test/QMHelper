package me.kofua.qmhelper.utils

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import android.content.res.Resources
import android.os.Looper
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference

/**
 * 单进程内共享的运行时状态。
 *
 * 老 API（de.robv.android.xposed）是"静态桥"：XposedBridge / AndroidAppHelper 到处能用，
 * 不需要谁把框架对象传给你。现代 libxposed（API 101/102）换成了**模块实例**模型：
 * 框架构造 `XposedModule` 的子类、注入接口，然后回调 `onModuleLoaded` / `onPackageLoaded` /
 * `onPackageReady`。所以过去那些"全局量"必须自己收拢成一个容器 —— 就是这里。
 *
 * 收拢的东西：
 *  - [module]：框架给的本模块实例，hook / log / getModuleApplicationInfo 都从它走
 *  - [classLoader]：宿主（Q音）的 classloader
 *  - [appContext]：宿主 Application 的 context（**不能**再用 AndroidAppHelper 了）
 *  - [moduleApkPath] / [moduleApplicationInfo]：本模块 APK 的路径与信息（资源、so 都靠它）
 *  - [moduleResources]：本模块 APK 里的字符串/数组，在宿主进程里也要能读
 */
object HookEnv {

    const val TAG = "QMHelper"

    @Volatile
    var module: XposedModule? = null
        private set

    /** 宿主进程的 classloader。 */
    @Volatile
    var classLoader: ClassLoader? = null

    /** 宿主进程的 Application context。 */
    @Volatile
    var appContext: Context? = null

    /** 本模块 APK 里的 Resources（可以读本模块的 string-array / drawable）。 */
    @Volatile
    var moduleResources: Resources? = null

    /** 本模块 APK 的路径。 */
    @Volatile
    var moduleApkPath: String = ""

    /** 本模块自己的 ApplicationInfo（nativeLibraryDir 从这里拿）。 */
    @Volatile
    var moduleApplicationInfo: ApplicationInfo? = null

    /** 当前进程名（框架在 onModuleLoaded 里给出）。 */
    @Volatile
    var processName: String = ""

    /** 宿主当前处于前台（已 resume 未 finish）的 Activity，NULL 表示没有。 */
    @Volatile
    private var activityRef: WeakReference<Any>? = null

    val currentActivity: android.app.Activity?
        get() = (activityRef?.get() as? android.app.Activity)
            ?.takeIf { !it.isFinishing && !it.isDestroyed }

    fun recordActivity(activity: android.app.Activity) {
        activityRef = WeakReference(activity)
    }

    /**
     * 挂载框架给的模块实例。
     *
     * 必须在装任何 hook 之前调用 —— 后面所有 hook 都要走 `module.hook(...)`。
     */
    fun attach(instance: XposedModule, process: String? = null) {
        module = instance
        if (!process.isNullOrBlank()) processName = process
        if (processName.isEmpty()) processName = readProcessName()
        moduleApplicationInfo = runCatching { instance.moduleApplicationInfo }.getOrNull()
        moduleApkPath = moduleApplicationInfo?.sourceDir ?: moduleApplicationInfo?.publicSourceDir ?: ""
    }

    private fun readProcessName(): String = runCatching {
        java.io.File("/proc/self/cmdline").readBytes()
            .takeWhile { it != 0.toByte() }
            .toByteArray()
            .toString(Charsets.UTF_8)
    }.getOrDefault("")

    /**
     * 把本模块 APK 的资源挂到宿主进程里。
     *
     * 老 API 那个 `XModuleResources.createInstance(modulePath, null)` 在现代 API 里没有对应物，
     * 所以自己用隐藏 API `AssetManager#addAssetPath` 拼一个 Resources 出来。
     */
    fun loadModuleResources(context: Context): Resources? = runCatching {
        val path = moduleApkPath.ifEmpty { context.packageCodePath }
        val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
        AssetManager::class.java
            .getDeclaredMethod("addAssetPath", String::class.java)
            .apply { isAccessible = true }
            .invoke(assets, path)
        val base = context.resources
        Resources(assets, base.displayMetrics, base.configuration)
    }.onFailure {
        log(android.util.Log.WARN, TAG, "载入模块资源失败: ${it.message}", it)
    }.getOrNull()

    val xposed: XposedModule
        get() = module ?: throw IllegalStateException("XposedModule 尚未挂载")

    fun log(priority: Int, message: String) = log(priority, TAG, message, null)

    /**
     * 统一日志出口。
     *
     * 优先走框架的 [XposedInterface.log]（会进 LSPosed 的模块日志，跟 logcat 都看得到），
     * 框架还没挂上时退回 android.util.Log。
     */
    fun log(priority: Int, tag: String?, message: String, tr: Throwable? = null) {
        val text = if (tr == null) message else "$message\n${android.util.Log.getStackTraceString(tr)}"
        val instance = module
        if (instance != null) {
            runCatching { instance.log(priority, tag ?: TAG, text) }
                .onFailure { android.util.Log.println(priority, tag ?: TAG, text) }
        } else {
            android.util.Log.println(priority, tag ?: TAG, text)
        }
    }
}

/**
 * 宿主进程里拿当前 Application。
 *
 * 老 API 的 `AndroidAppHelper.currentApplication()` 现代 API 不再提供，这里按
 * `ActivityThread.currentApplication()` 取；那条路取不到时退回 hook 里捕获的 [HookEnv.appContext]。
 */
fun currentApplication(): Application? {
    (HookEnv.appContext as? Application)?.let { return it }
    runCatching {
        val activityThread = Class.forName("android.app.ActivityThread")
        val current = activityThread.getMethod("currentApplication").invoke(null)
        (current as? Application)
    }.onFailure {
        HookEnv.log(android.util.Log.DEBUG, HookEnv.TAG, "ActivityThread#currentApplication 失败: ${it.message}")
    }
    return runCatching {
        val activityThread = Class.forName("android.app.ActivityThread")
        val thread = activityThread.getMethod("currentActivityThread").invoke(null)
        val application = activityThread.getMethod("getApplication").invoke(thread)
        application as? Application
    }.getOrNull() ?: HookEnv.appContext as? Application
}

/** 是不是主线程 —— 有些 hook 必须回到主线程再动 UI。 */
fun isMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()