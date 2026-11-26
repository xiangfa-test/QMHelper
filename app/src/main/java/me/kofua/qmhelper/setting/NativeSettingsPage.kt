package me.kofua.qmhelper.setting

import android.content.Context
import android.view.View
import androidx.annotation.StringRes
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
 * ## 页面从哪来
 *
 * 21.x 里宿主自己的设置页都继承 `fragment.debug.BaseRecyclerSettingFragment`，
 * 它只有两个抽象方法：
 *
 * ```kotlin
 * title():String                          // 页面标题（标题栏）
 * settingPackage():BaseSettingPackage     // 设置项来源
 * ```
 *
 * 所以"原生 UI" = dexmaker 生成它的子类，覆写这两个方法：
 *
 *  - `title()` → 「Q音助手」
 *  - `settingPackage()` → 一个 `BaseSettingPackage` 子类实例，
 *    其 `createSettingProvider()` 返回模块的设置项列表
 *
 * ## 设置项怎么造
 *
 * 每条设置 = 一个 **`SettingProvider` 的动态代理**（它是个接口，`java.lang.reflect.Proxy` 就够，
 * 不需要 dexmaker）：
 *
 * ```
 * getSetting()  → 宿主 Setting$Builder 构造的 Setting 对象（原生渲染、原生样式）
 * shouldShown() → true
 * ```
 *
 * 开关回调 = `SettingView$d` 的动态代理（同样只是接口）：
 * `isSwitchOn()` 读配置，`onSwitchStatusChange(boolean)` 写配置。
 *
 * ## 与旧版（1.1.4）的差异
 *
 * 旧版代理的是 `BaseSettingFragment/BaseSettingPack/BaseSettingProvider` 三件套 —— 21.x 里
 * `BaseSettingFragment` 没了，等价物是 `BaseRecyclerSettingFragment`；`BaseSettingPack` 改名
 * `BaseSettingPackage`；`BaseSettingProvider` 从类变成了接口（Proxy 反而更省事）。
 * 老的三件套解析（baseSettingFragment/baseSettingPack/baseSettingProvider）在 21.x 定位不到
 * 是正常的，这里走的是 `hookInfo.setting.nativeUi` 这套新定位。
 */
object NativeSettingsPage {

    private const val PAGE_TITLE = "Q音助手"

    /** 页面类；解析或生成失败时为 null（入口会退回自绘 Dialog）。 */
    @Volatile
    private var fragmentClass: Class<*>? = null

    /** 本页 Package 子类缓存（同一个页面只生成一次）。 */
    @Volatile
    private var packProxyClass: Class<*>? = null

    fun isAvailable(): Boolean = hookInfo.setting.nativeUi.fragmentClass.isNotEmpty()

    /**
     * 创建（或复用）模块设置页的 Fragment 实例。
     *
     * 调用方（SettingsHook 的入口点击）拿到后直接
     * `context.callMethod("addSecondFragment", fragmentClass, null)` 打开。
     */
    fun newFragmentInstance(context: Context): Any? {
        val clazz = ensureFragmentClass(context) ?: return null
        // BaseRecyclerSettingFragment 构造器是无参的（<init>()V）
        return runCatchingOrNull { clazz.new() }
    }

    /** 拿页面类（给 addSecondFragment 用 —— 宿主要的是 Class 而不是实例）。 */
    fun fragmentClass(context: Context): Class<*>? = ensureFragmentClass(context)

    // ------------------------------------------------------------------ 内部

    private fun ensureFragmentClass(context: Context): Class<*>? {
        fragmentClass?.let { return it }
        synchronized(this) {
            fragmentClass?.let { return it }
            val nativeUi = hookInfo.setting.nativeUi
            if (nativeUi.fragmentClass.isEmpty()) return null
            val baseFragmentClass = nativeUi.fragmentClass.from(classLoader) ?: return null
            val basePackClass = nativeUi.packageClass.from(classLoader) ?: return null

            return runCatching {
                // 1) Package 子类：覆写 createSettingProvider() 返回模块条目。
                val packClass = (packProxyClass ?: basePackClass.proxy(nativeUi.createSettingProviderMethod)
                    .also { packProxyClass = it })
                // 2) Fragment 子类：覆写 title()/settingPackage()。
                val fragClass = baseFragmentClass.proxy(nativeUi.titleMethod, nativeUi.settingPackageMethod)

                // Fragment 实例的行为：title 返回固定文案；settingPackage 返回 Pack 实例。
                // 这里不装 invocationHandler —— 实例创建时装（见 newFragmentInstance 内的 hookBefore 构造器拦截，
                // 与老 QQMusicPlus 的 "constructor + setInvocationHandler" 手法一致）。
                packClass.invocationHandler { pack, method, mArgs ->
                    when (method.name) {
                        nativeUi.createSettingProviderMethod ->
                            buildProviders(context, pack)
                        else -> null
                    }
                }
                fragClass.invocationHandler { proxy, method, args ->
                    when (method.name) {
                        nativeUi.titleMethod -> PAGE_TITLE
                        nativeUi.settingPackageMethod -> {
                            // Pack 实例创建后要立刻把 handler 挂回去 —— ProxyBuilder.setInvocationHandler
                            // 是按实例设置的，类级 handler 只对"下一个实例"生效。
                            val pack = newPackInstance(packClass, context)
                            pack?.invocationHandler { p, m, _ ->
                                when (m.name) {
                                    nativeUi.createSettingProviderMethod -> buildProviders(context, p)
                                    else -> null
                                }
                            }
                            pack
                        }
                        else -> null
                    }
                }
                // 给 Pack 子类也预置 handler 工厂（实例创建时再挂真正的 handler）。
                fragClass.also { fragmentClass = it }
            }.onFailure {
                Log.e("原生设置页：动态子类生成失败")
                Log.e(it)
            }.getOrNull()
        }
    }

    /** 造一个 Pack 实例：构造器要 (Context, SettingEvent, Bundle)，全部可从当前环境拼出来。 */
    private fun newPackInstance(packClass: Class<*>, context: Context): Any? {
        // SettingEvent 也是接口 —— Proxy 一个空实现即可（宿主用它回传列表刷新事件，
        // 我们不需要监听，notifyEvent 走宿主自己的通道）。
        val eventClass = "com.tencent.qqmusic.fragment.morefeatures.settings.base.SettingEvent"
            .from(classLoader) ?: return null
        val event = Proxy.newProxyInstance(
            classLoader, arrayOf(eventClass)
        ) { _, _, _ -> null }

        return runCatchingOrNull {
            packClass.new(context, event, null)
        } ?: runCatchingOrNull {
            // 某些版本构造器签名是 (Context, SettingEvent, Bundle) 且 Bundle 必传非 null
            packClass.new(context, event, android.os.Bundle())
        }
    }

    // ------------------------------------------------------------------ 条目

    /**
     * 构造本页的设置项列表（`createSettingProvider()` 的返回值）。
     *
     * 每条规格包装成一个 `SettingProvider` 动态代理；宿主的 RecyclerView
     * 通过 `provider.getSetting()` 拿到原生 `Setting` 对象去渲染。
     */
    fun buildProviders(context: Context, fragment: Any): CopyOnWriteArrayList<Any?> {
        val providerInterface =
            "com.tencent.qqmusic.fragment.morefeatures.settings.base.SettingProvider".from(classLoader)
        val list = CopyOnWriteArrayList<Any?>()
        if (providerInterface == null) return list

        val specs = runCatching { SettingPack().apply { activity = fragment as? android.app.Activity }.getSettings() }
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
        return list
    }

    /** 开关回调代理（SettingView$d）：读/写 sPrefs。 */
    fun switchListener(isSwitchOn: () -> Boolean, onChange: (Boolean) -> Unit): Any? {
        val viewD = qmPackage.switchListenerClass ?: return null
        return Proxy.newProxyInstance(classLoader, arrayOf(viewD)) { _, method, args ->
            when (method.name) {
                hookInfo.setting.switchListener.isSwitchOn.name -> isSwitchOn()
                hookInfo.setting.switchListener.onSwitchStatusChange.name -> onChange(args?.getOrNull(0) as? Boolean ?: false)
            }
            null
        }
    }
}