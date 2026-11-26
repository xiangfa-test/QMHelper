package me.kofua.qmhelper.setting

import android.app.Activity
import android.content.Context
import android.os.Bundle
import me.kofua.qmhelper.classLoader
import me.kofua.qmhelper.from
import me.kofua.qmhelper.hookInfo
import me.kofua.qmhelper.qmPackage
import me.kofua.qmhelper.utils.*
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
 *   createSettingProvider():CopyOnWriteArrayList
 * settings.base.SettingProvider（接口） getSetting():Setting / shouldShown()…
 * settings.base.SettingEvent（接口）    包构造器要的事件回调
 * settings.view.SettingView$d（接口）   开关回调 isSwitchOn()/onSwitchStatusChange(Z)
 * ```
 *
 * ## 实例化手法（关键，踩过坑）
 *
 * `ProxyBuilder.setInvocationHandler` 只接受代理**实例**，挂在 Class 上会抛
 * "Not a valid proxy instance"。而页面由宿主通过 `addSecondFragment(Class)` 反射实例化，
 * 我们拿不到"创建后"的时机 —— 所以用上游 1.1.4 的老手法：
 *
 * 1. hook 基类构造器（before）；
 * 2. 调 `addSecondFragment(我们的代理类)`，宿主反射 new 的瞬间被钩住；
 * 3. 判断 `thisObject` 是不是我们的代理类，是就当场 `setInvocationHandler`；
 * 4. unhook。
 *
 * Pack 实例由 fragment 的 `settingPackage()` 回调自己创建，创建后立刻挂实例 handler。
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

    fun isAvailable(): Boolean = hookInfo.setting.nativeUi.fragmentClass.isNotEmpty()

    /**
     * 在宿主当前窗口打开模块的原生设置页。
     *
     * @param addSecondFragment 宿主的打开方法（`BaseFragmentActivity#addSecondFragment`）
     * @return true = 已走原生通道；false = 框架不可用（调用方退回 Dialog 兜底）
     */
    fun openInHost(activity: Activity, addSecondFragment: me.kofua.qmhelper.data.Method): Boolean {
        if (!isAvailable()) return false
        val (fragClass, _) = ensureProxyClasses() ?: return false
        return runCatching {
            val context = activity
            val nativeUi = hookInfo.setting.nativeUi

            // Pack handler：createSettingProvider → 模块条目列表。
            val packHandler = java.lang.reflect.InvocationHandler { _, method, _ ->
                when (method.name) {
                    nativeUi.createSettingProviderMethod -> buildProviders(context)
                    else -> null
                }
            }
            val packClass = packProxyClass ?: return false

            // Fragment handler：title / settingPackage。
            // settingPackage 里当场 new Pack 实例并挂实例级 handler。
            val fragHandler = java.lang.reflect.InvocationHandler { _, method, _ ->
                when (method.name) {
                    nativeUi.titleMethod -> PAGE_TITLE
                    nativeUi.settingPackageMethod -> newPackInstance(packClass, context, packHandler)
                    else -> null
                }
            }

            // 构造器钩子：宿主 new 我们代理类的瞬间挂 handler（上游 1.1.4 同款手法）。
            // 构造器钩子挂在**代理子类**自己身上（而不是基类）：dexmaker 生成的类由模块
            // InMemoryDexClassLoader 加载，基类构造器 hook 在某些框架版本下拦不到子类的
            // super() 链；直接 hook 子类构造器最直接、必触发。
            // 另外不 unhook：条目页构造完成后这条钩子就闲置了，只做一次类名/代价可忽略。
            val unhooks = fragClass.hookBeforeAllConstructors { param ->
                val inst = param.thisObject ?: return@hookBeforeAllConstructors
                Log.d("原生设置页：代理 Fragment 构造触发，挂 handler")
                runCatching { inst.invocationHandler(fragHandler) }
                    .onFailure { Log.e("原生设置页：handler 挂载失败"); Log.e(it) }
            }
            // ⚠️ 不能在 callMethod 返回后立刻 unhook：addSecondFragment 是把 Fragment 事务
            // post 到主线程队列，真正 new 出代理实例发生在返回之后。构造器钩子只对
            // "类名匹配的实例"生效，留着不碍事（页面构造完就没用了，进程内常驻代价可忽略）。
            activity.callMethod(addSecondFragment.name, fragClass, null)
            Log.d("模块设置：已打开原生设置页（${fragClass.name}）")
            true
        }.onFailure {
            Log.e("模块设置：原生页打开失败")
            Log.e(it)
        }.getOrDefault(false)
    }

    /** 供 Dialog 兜底前的可用性判断 + 入口点击的退路。 */
    fun switchListenerProxy(isSwitchOn: () -> Boolean, onChange: (Boolean) -> Unit): Any? {
        val viewD = qmPackage.switchListenerClass ?: return null
        return Proxy.newProxyInstance(classLoader, arrayOf(viewD)) { _, method, args ->
            when (method.name) {
                hookInfo.setting.switchListener.isSwitchOn.name -> isSwitchOn()
                hookInfo.setting.switchListener.onSwitchStatusChange.name -> onChange(args?.getOrNull(0) as? Boolean ?: false)
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

    /** new 一个 Pack 实例并挂上实例级 handler。 */
    private fun newPackInstance(
        packClass: Class<*>,
        context: Context,
        handler: java.lang.reflect.InvocationHandler,
    ): Any? {
        val eventClass = EVENT_CLASS.from(classLoader) ?: run {
            Log.e("原生设置页：SettingEvent 接口加载失败")
            return null
        }
        val event = Proxy.newProxyInstance(classLoader, arrayOf(eventClass)) { _, _, _ -> null }
        val instance: Any? = try {
            packClass.new(context, event, Bundle())
        } catch (t: Throwable) {
            Log.e("原生设置页：Pack 构造失败（context=${context.javaClass.name}）")
            Log.e(t)
            null
        } ?: return null
        runCatching { instance!!.invocationHandler(handler) }
            .onFailure { Log.e("原生设置页：Pack handler 挂载失败"); Log.e(it) }
        return instance
        return instance
    }

    /** 本页条目：每条规格包成一个 SettingProvider 动态代理。 */
    private fun buildProviders(context: Context): CopyOnWriteArrayList<Any?> {
        val list = CopyOnWriteArrayList<Any?>()
        val providerInterface = PROVIDER_CLASS.from(classLoader) ?: return list
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
