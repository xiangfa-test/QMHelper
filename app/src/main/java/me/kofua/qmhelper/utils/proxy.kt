package me.kofua.qmhelper.utils

import android.content.Context
import com.android.dx.stock.ProxyBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.kofua.qmhelper.classLoader
import me.kofua.qmhelper.hookInfo
import me.kofua.qmhelper.qmPackage
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method

/**
 * dexmaker 动态子类 —— "设置页原生 UI"方案的基础设施。
 *
 * 21.x 的设置框架（`settings.base.*`）还活着：
 *
 * ```
 * BaseRecyclerSettingFragment（抽象类）
 *   ├─ title():String                    抽象 —— 页面标题
 *   └─ settingPackage():BaseSettingPackage  抽象 —— 设置项来源
 * BaseSettingPackage（抽象类，构造 Context/SettingEvent/Bundle）
 *   └─ createSettingProvider():CopyOnWriteArrayList   各包覆写它返回条目列表
 * SettingProvider（接口）  getSetting():Setting / shouldShown() …
 * SettingView$d（接口）   isSwitchOn():Boolean / onSwitchStatusChange(Z)
 * SettingEvent（接口）    包构造器要的"宿主事件回调"
 * ```
 *
 * 生成顺序：Fragment 子类（覆写两个抽象方法）→ Package 子类（覆写 createSettingProvider）
 * → 条目用宿主 `Setting$Builder` 构造（开关用 Proxy(SettingView$d)）。
 * 入口点击时 `addSecondFragment(我们的Fragment子类, null)`，页面就是 Q音 原生设置的样子。
 */
fun Class<*>.proxy(
    vararg onlyMethods: String,
): Class<*> = ProxyBuilder.forClass(this)
    .parentClassLoader(classLoader)
    .dexCache(currentContext.getDir("qmhelper_dx", Context.MODE_PRIVATE))
    .apply {
        if (onlyMethods.isNotEmpty())
            onlyMethods(onlyMethods.map { m ->
                this@proxy.findMethod(deep = true) { it.name == m }
            }.toTypedArray())
    }.buildProxyClass()

fun Any.invocationHandler(handler: InvocationHandler) =
    ProxyBuilder.setInvocationHandler(this, handler)

fun Any.callSuper(m: Method, vararg args: Any?): Any? = ProxyBuilder.callSuper(this, m, *args)

/**
 * 预热：把设置页要用的动态子类提前在后台线程生成（首次生成要写 dex 缓存，慢）。
 *
 * 21.x 的解析结果在 `hookInfo.setting.nativeUi` 里（见 QMPackage#setting 块），
 * 类定位失败时这里什么都不做 —— 入口仍会退回自绘 Dialog（ModuleSettingsDialog）。
 */
fun preloadProxyClasses() = mainScope.launch(Dispatchers.IO) {
    val nativeUi = hookInfo.setting.nativeUi
    val fragmentClass = nativeUi.fragmentClass.from(classLoader) ?: return@launch
    fragmentClass.proxy(nativeUi.settingPackageMethod, nativeUi.titleMethod)
    val packageClass = nativeUi.packageClass.from(classLoader) ?: return@launch
    packageClass.proxy(nativeUi.createSettingProviderMethod)
}