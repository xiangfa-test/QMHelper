package me.kofua.qmhelper.hook

import me.kofua.qmhelper.R
import me.kofua.qmhelper.classLoader
import me.kofua.qmhelper.from
import me.kofua.qmhelper.hookInfo
import me.kofua.qmhelper.utils.*

/**
 * 首页顶部 tab 管理（21.x 版）。
 *
 * 21.0.5.3 的 tab 装配链（反编译 `business/timeline/ui/HomePageFragment` 核对）：
 *
 * ```
 * 服务器配置 → L5(uimode) → List<x0>
 *           → Z6(list) → j5(list) → h5(x0):Boolean → this.y（tab 列表）
 * ```
 *
 * `h5` 是每个 tab 进列表的**必经闸门**，参数 `x0`：
 *  - `h():String` = tabId（配置里的字符串 id：乐馆="6"、直播="10"、有声="5"、会员="20"…）
 *  - `f():Int` = nativeLoadId（即老版本 addTabById 用的那个数字 id）
 *
 * 老 UI 层（`MainDeskTitleTabViewV21.W2`）在 21.x 是死代码（只有旧版头 `r2.f0` 调它，
 * 当前首页根本不走），所以 1.1.4 那套 UI 过滤换成了挂 `h5`。
 *
 * 匹配规则与老版本兼容：`purify_home_top_tab` 里存的数字 id 优先和 `f()` 比，
 * 同时也和 `h()`（tabId 字符串）比 —— 配置端两种存法都能命中。
 */
object HomeTopTabHook : BaseHook {

    private val purifyHomeTopTabIds by lazy {
        sPrefs.getStringSet("purify_home_top_tab", null)
            ?.sorted()?.map { it.toInt() } ?: listOf()
    }
    private val purifyHomeTobTabNames by lazy {
        stringArray(R.array.home_top_tab_values).map { it.toInt() }
            .zip(stringArray(R.array.home_top_tab_entries))
            .filter { purifyHomeTopTabIds.contains(it.first) }
            .map { it.second }
    }

    override fun hook() {
        if (purifyHomeTopTabIds.isEmpty()) return

        val topTab = hookInfo.homeTopTab
        if (topTab.addTab.name.isEmpty() || topTab.tabId.name.isEmpty()) {
            Log.w("首页顶部tab：21.x 闸门没解析到（h5/tabId），功能不生效")
            return
        }
        val homeFragmentClass = topTab.clazz.from(classLoader) ?: run {
            Log.w("首页顶部tab：HomePageFragment 加载失败")
            return
        }
        val tabId = topTab.tabId.name
        val nativeId = topTab.nativeId.name
        homeFragmentClass.hookBeforeMethod(topTab.addTab.name, *topTab.addTab.paramTypes) { param ->
            val x0 = param.args.getOrNull(0) ?: return@hookBeforeMethod
            val idStr = runCatchingOrNull { x0.callMethodAs<String>(tabId) }
            val native = nativeId.ifNotEmpty { runCatchingOrNull { x0.callMethodAs<Int>(nativeId) } }
            val hit = (idStr != null && purifyHomeTopTabIds.contains(idStr.toIntOrNull() ?: -1)) ||
                    (native != null && purifyHomeTopTabIds.contains(native))
            if (hit) {
                Log.d("首页顶部tab：拦截 tabId=$idStr nativeId=$native（${purifyHomeTobTabNames.joinToString()}）")
                param.result = false
            }
        }
        Log.d("首页顶部tab：已挂 h5 闸门（${topTab.addTab.name}），过滤 ${purifyHomeTobTabNames.joinToString()}")
    }
}