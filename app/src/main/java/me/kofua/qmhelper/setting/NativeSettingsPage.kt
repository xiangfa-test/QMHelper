package me.kofua.qmhelper.setting

import android.app.Activity
import android.content.Context
import android.os.Bundle
import me.kofua.qmhelper.classLoader
import me.kofua.qmhelper.from
import me.kofua.qmhelper.hookInfo
import me.kofua.qmhelper.qmPackage
import me.kofua.qmhelper.utils.*
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 把模块设置装进 Q音 **原生设置框架**（21.x 版）。
 *
 * 框架（反编译核对过，类名/方法名未混淆）：
 *
 * ```
 * fragment.debug.BaseRecyclerSettingFragment（抽象类，无参构造）
 *   title():String                       抽象 —— 标题栏文字
 *   settingPackage():BaseSettingPackage  抽象 —— 条目来源
 * settings.base.BaseSettingPackage（抽象类）
 *   <init>(Context, SettingEvent, Bundle)
 *     ⚠️ 构造器内部就会回调 createSettingProvider() 并强校验：
 *        列表为空/null 直接抛 IllegalArgumentException("settingProviders == null")
 *   createSettingProvider():CopyOnWriteArrayList
 * settings.base.SettingProvider（接口） getSetting():Setting / shouldShown()…
 * settings.base.SettingEvent（接口）    包构造器要的事件回调
 * settings.view.SettingView$d（接口）   开关回调 isSwitchOn()/onSwitchStatusChange(Z)
 * ```
 *
 * ## handler 挂载时机（两次实机崩溃换来的教训）
 *
 * `ProxyBuilder.setInvocationHandler` 只接受代理实例，且 dexmaker 生成的构造器体
 * 只是 `super(...)` —— handler 字段不会被自动设置。
 *
 * - 挂在 Class 上 → "Not a valid proxy instance"；
 * - new() 之后再挂 → 对 Fragment 勉强够（title/settingPackage 在 createView 才调），
 *   但对 Pack 是死路：**super 构造器执行期间就回调 createSettingProvider()**，
 *   那时 handler 还没挂，虚拟调用落空返回 null → 构造器抛异常 → settingPackage()
 *   返回 null → 宿主 initRecyclerView 里 onCreate() NPE → 崩。
 *
 * 唯一正确姿势（上游 1.1.4 同款）：`hookBefore` 挂到**代理类自己的构造器**上，
 * 在构造器体（super 调用）执行之前把 handler 写进实例的 `$__handler` 字段。
 * 钩子常驻不 unhook —— 只对这两个动态类的实例生效，代价可忽略。
 */
object NativeSettingsPage {

    private const val PAGE_TITLE = "Q音助手"
    private const val FRAGMENT_CLASS = "com.tencent.qqmusic.fragment.debug.BaseRecyclerSettingFragment"
    private const val PACKAGE_CLASS = "com.tencent.qqmusic.fragment.morefeatures.settings.base.BaseSettingPackage"
    private const val EVENT_CLASS = "com.tencent.qqmusic.fragment.morefeatures.settings.base.SettingEvent"
    private const val PROVIDER_CLASS = "com.tencent.qqmusic.fragment.morefeatures.settings.base.SettingProvider"

    @Volatile
    private var fragProxyClass: Class<*>? = null
    @Volatile
    private var packProxyClass: Class<*>? = null
    @Volatile
    private var hooksInstalled = false

    /** 打开页面时所在 Activity；Pack/Provider 构造 Setting 时用作 context。 */
    @Volatile
    private var hostActivity: Activity? = null

    fun isAvailable(): Boolean = hookInfo.setting.nativeUi.fragmentClass.isNotEmpty()

    /**
     * 在宿主当前窗口打开模块的原生设置页。
     *
     * @param addSecondFragment 宿主的打开方法（`BaseFragmentActivity#addSecondFragment`）
     * @return true = 已走原生通道；false = 框架不可用（调用方退回 Dialog 兜底）
     */
    fun openInHost(activity: Activity, addSecondFragment: me.kofua.qmhelper.data.Method): Boolean {
        if (!isAvailable()) return false
        val (fragClass, packClass) = ensureProxyClasses() ?: return false
        return runCatching {
            val nativeUi = hookInfo.setting.nativeUi
            hostActivity = activity

            // Pack handler：createSettingProvider → 模块条目列表。
            // Pack 构造器（super）执行期间就会调它并校验非空。
            val packHandler = InvocationHandler { _, method, _ ->
                when (method.name) {
                    nativeUi.createSettingProviderMethod -> buildProviders()
                    else -> null
                }
            }
            // Fragment handler：title / settingPackage。
            val fragHandler = InvocationHandler { _, method, _ ->
                when (method.name) {
                    nativeUi.titleMethod -> PAGE_TITLE
                    nativeUi.settingPackageMethod -> newPackInstance(packClass, activity)
                    else -> null
                }
            }

            // 构造器 hookBefore：构造器体（super 调用）执行前把 handler 写进实例。
            // 常驻不 unhook —— 只对这两个动态类的实例生效。
            packClass.hookBeforeAllConstructors { param ->
                val inst = param.thisObject ?: return@hookBeforeAllConstructors
                runCatching { inst.invocationHandler(packHandler) }
                    .onFailure { Log.e("原生设置页：Pack handler 预挂载失败"); Log.e(it) }
            }
            fragClass.hookBeforeAllConstructors { param ->
                val inst = param.thisObject ?: return@hookBeforeAllConstructors
                runCatching { inst.invocationHandler(fragHandler) }
                    .onFailure { Log.e("原生设置页：Fragment handler 预挂载失败"); Log.e(it) }
            }

            activity.callMethod(addSecondFragment.name, fragClass, null)
            Log.d("模块设置：已打开原生设置页（${fragClass.name}）")
            true
        }.onFailure {
            Log.e("模块设置：原生页打开失败")
            Log.e(it)
        }.getOrDefault(false)
    }

    /** 开关回调代理（SettingView$d 接口）：读/写配置。 */
    fun switchListenerProxy(isSwitchOn: () -> Boolean, onChange: (Boolean) -> Unit): Any? {
        val viewD = qmPackage.switchListenerClass ?: return null
        return Proxy.newProxyInstance(classLoader, arrayOf(viewD)) { _, method, args ->
            when (method.name) {
                hookInfo.setting.switchListener.isSwitchOn.name -> isSwitchOn()
                hookInfo.setting.switchListener.onSwitchStatusChange.name ->
                    onChange(args?.getOrNull(0) as? Boolean ?: false)
            }
            null
        }
    }

    // ------------------------------------------------------------------ 内部

    /** 生成（或复用）两个动态子类。失败返回 null。 */
    private fun ensureProxyClasses(): Pair<Class<*>, Class<*>>? {
        fragProxyClass?.let { f -> packProxyClass?.let { p -> return f to p } }
        synchronized(this) {
            fragProxyClass?.let { f -> packProxyClass?.let { p -> return f to p } }
            val nativeUi = hookInfo.setting.nativeUi
            val baseFrag = nativeUi.fragmentClass.from(classLoader) ?: return null
            val basePack = nativeUi.packageClass.from(classLoader) ?: return null
            return runCatching {
                val p = basePack.proxy(nativeUi.createSettingProviderMethod)
                val f = baseFrag.proxy(nativeUi.titleMethod, nativeUi.settingPackageMethod)
                packProxyClass = p
                fragProxyClass = f
                f to p
            }.onFailure {
                Log.e("原生设置页：动态子类生成失败")
                Log.e(it)
            }.getOrNull()
        }
    }

    /** new 一个 Pack 实例。handler 已由常驻构造器钩子预挂载。 */
    private fun newPackInstance(packClass: Class<*>, context: Context): Any? {
        val eventClass = EVENT_CLASS.from(classLoader) ?: run {
            Log.e("原生设置页：SettingEvent 接口加载失败")
            return null
        }
        val event = Proxy.newProxyInstance(classLoader, arrayOf(eventClass)) { _, _, _ -> null }
        return try {
            val inst = packClass.new(context, event, Bundle())
            Log.d("原生设置页：Pack 构造成功")
            inst
        } catch (t: Throwable) {
            Log.e("原生设置页：Pack 构造失败（context=${context.javaClass.name}）")
            Log.e(t)
            null
        }
    }

    /** 本页条目：每条规格包成一个 SettingProvider 动态代理。 */
    private fun buildProviders(): CopyOnWriteArrayList<Any?> {
        val list = CopyOnWriteArrayList<Any?>()
        val providerInterface = PROVIDER_CLASS.from(classLoader) ?: return list
        val context: android.content.Context = hostActivity ?: currentApplication() ?: return list
        val specs = runCatching { SettingPack().getSettings() }
            .onFailure { Log.e("原生设置页：读取规格失败"); Log.e(it) }
            .getOrDefault(emptyList())
        for (spec in specs) {
            val hostSetting = spec.toHostSetting(context) ?: continue
            list.add(Proxy.newProxyInstance(classLoader, arrayOf(providerInterface)) { _, method, _ ->
                when (method.name) {
                    "getSetting" -> hostSetting
                    "shouldShown" -> true
                    else -> null
                }
            })
        }
        Log.d("原生设置页：已构建 ${list.size} 条设置项")
        return list
    }
}