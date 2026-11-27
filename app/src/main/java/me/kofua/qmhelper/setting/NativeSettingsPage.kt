package me.kofua.qmhelper.setting

import android.app.Activity
import android.content.Context
import android.os.Bundle
import me.kofua.qmhelper.classLoader
import me.kofua.qmhelper.from
import me.kofua.qmhelper.hookInfo
import me.kofua.qmhelper.qmPackage
import me.kofua.qmhelper.utils.*
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList

/** 使用宿主的 Fragment、设置包和条目渲染模块设置。 */
object NativeSettingsPage {

    private const val PAGE_TITLE = "Q音助手"
    private const val EVENT_CLASS = "com.tencent.qqmusic.fragment.morefeatures.settings.base.SettingEvent"
    private const val PROVIDER_CLASS = "com.tencent.qqmusic.fragment.morefeatures.settings.base.SettingProvider"

    private data class Proxies(
        val fragmentClass: Class<*>,
        val packConstructor: Constructor<*>,
        val providerConstructor: Constructor<*>,
        val eventClass: Class<*>,
    )

    private var proxies: Proxies? = null
    private val providerHandler = ThreadLocal<InvocationHandler>()
    private val packHandler = ThreadLocal<InvocationHandler>()
    private val fragmentHandler = ThreadLocal<InvocationHandler>()

    fun isAvailable(): Boolean = hookInfo.setting.nativeUi.fragmentClass.isNotEmpty()

    /**
     * 在宿主当前窗口打开模块的原生设置页。
     *
     * @param addSecondFragment 宿主的打开方法（`BaseFragmentActivity#addSecondFragment`）
     * @return true = 已走原生通道；false = 框架不可用（调用方退回 Dialog 兜底）
     */
    fun openInHost(activity: Activity, addSecondFragment: me.kofua.qmhelper.data.Method): Boolean {
        if (!isAvailable()) return false
        return runCatching {
            val (fragClass, packConstructor, providerConstructor, eventClass) =
                ensureProxies() ?: return false
            val nativeUi = hookInfo.setting.nativeUi
            var eventTarget: Any? = null
            val event = Proxy.newProxyInstance(classLoader, arrayOf(eventClass)) { _, method, args ->
                eventTarget?.let { method.invoke(it, *(args ?: emptyArray())) }
            }
            val providers = buildProviders(activity, event, providerConstructor)
            check(providers.isNotEmpty()) { "未能构建任何原生设置项" }

            // 先验证宿主 Pack 构造成功，再导航，失败时调用方仍可打开 Dialog。
            val pack = withHandler(packHandler, InvocationHandler { _, method, _ ->
                when (method.name) {
                    nativeUi.createSettingProviderMethod -> providers
                    else -> null
                }
            }) {
                packConstructor.newInstance(activity, event, Bundle())
            }

            withHandler(fragmentHandler, InvocationHandler { fragment, method, _ ->
                when (method.name) {
                    nativeUi.titleMethod -> PAGE_TITLE
                    nativeUi.settingPackageMethod -> {
                        if (eventClass.isInstance(fragment)) eventTarget = fragment
                        pack
                    }
                    else -> null
                }
            }) {
                activity.callMethod(addSecondFragment.name, fragClass, null)
            }
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
                hookInfo.setting.switchListener.onSwitchStatusChange.name -> {
                    onChange(args?.getOrNull(0) as? Boolean ?: false)
                    null
                }
                else -> null
            }
        }
    }

    // ------------------------------------------------------------------ 内部

    private fun ensureProxies(): Proxies? = synchronized(this) {
        proxies?.let { return@synchronized it }
        val nativeUi = hookInfo.setting.nativeUi
        val baseFrag = nativeUi.fragmentClass.from(classLoader) ?: return@synchronized null
        val basePack = nativeUi.packageClass.from(classLoader) ?: return@synchronized null
        val eventClass = checkNotNull(EVENT_CLASS.from(classLoader))
        val providerClass = checkNotNull(PROVIDER_CLASS.from(classLoader)).proxy("create")
        val packClass = basePack.proxy(nativeUi.createSettingProviderMethod)
        val fragClass = baseFrag.proxy(nativeUi.titleMethod, nativeUi.settingPackageMethod)
        val providerConstructor = providerClass.getDeclaredConstructor(Context::class.java, eventClass)
        val packConstructor = packClass.getDeclaredConstructor(Context::class.java, eventClass, Bundle::class.java)
        val fragConstructor = fragClass.getDeclaredConstructor()
        // Provider 和 Pack 都会在基类构造器中调用覆写方法。
        val providerHook = installHandler(providerConstructor, providerHandler)
        try {
            val packHook = installHandler(packConstructor, packHandler)
            try {
                installHandler(fragConstructor, fragmentHandler)
            } catch (t: Throwable) {
                packHook.unhook()
                throw t
            }
        } catch (t: Throwable) {
            providerHook.unhook()
            throw t
        }
        Proxies(fragClass, packConstructor, providerConstructor, eventClass).also { proxies = it }
    }

    private fun installHandler(constructor: Constructor<*>, slot: ThreadLocal<InvocationHandler>) =
        checkNotNull(installHook(constructor, before = true) { param ->
            slot.get()?.let { param.thisObject.invocationHandler(it) }
        })

    private fun <T> withHandler(
        slot: ThreadLocal<InvocationHandler>,
        handler: InvocationHandler,
        create: () -> T,
    ): T {
        val previous = slot.get()
        slot.set(handler)
        return try {
            create()
        } finally {
            if (previous == null) slot.remove() else slot.set(previous)
        }
    }

    private fun buildProviders(
        activity: Activity,
        event: Any,
        constructor: Constructor<*>,
    ): CopyOnWriteArrayList<Any> {
        val list = CopyOnWriteArrayList<Any>()
        val settingPack = SettingPack().apply { this.activity = activity }
        for (spec in settingPack.getSettings()) {
            val hostSetting = spec.toHostSetting(activity) ?: continue
            list.add(withHandler(providerHandler, InvocationHandler { _, _, _ -> hostSetting }) {
                constructor.newInstance(activity, event)
            })
        }
        Log.d("原生设置页：已构建 ${list.size} 条设置项")
        return list
    }
}