package me.kofua.qmhelper.hook

import android.app.Activity
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.kofua.qmhelper.R
import me.kofua.qmhelper.from
import me.kofua.qmhelper.hookInfo
import me.kofua.qmhelper.qmPackage
import me.kofua.qmhelper.setting.Setting
import me.kofua.qmhelper.setting.SettingPack
import me.kofua.qmhelper.setting.NativeSettingsPage
import me.kofua.qmhelper.ui.ModuleSettingsDialog
import me.kofua.qmhelper.utils.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Calendar
import java.util.TimeZone

object SettingsHook : BaseHook {
    private val purifyRedDots by lazy { sPrefs.getBoolean("purify_red_dots", false) }
    private val purifyMoreItems by lazy {
        sPrefs.getStringSet("purify_more_items", null) ?: setOf()
    }
    private val settingPack = SettingPack()

    /** 面板里要用的开关，缓存一下避免每次渲染都读盘。 */
    private val settingViewTextFields by lazy {
        hookInfo.settingView.clazz.from(classLoader)
            ?.declaredFields?.filter { it.type == TextView::class.java }
            ?.map { it.name } ?: listOf()
    }
    private val todayFormat: String
        get() = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
            .format(Date())

    override fun hook() {
        if (sPrefs.getBoolean("vip_station_sign_in", false)) {
            handler.post(8000L) {
                mainScope.launch(Dispatchers.IO) { vipStationSignIn() }
            }
        }
        hookInfo.appStarterActivity.hookAfterMethod({ doOnCreate }) { param ->
            val activity = param.thisObject as Activity
            activity.addModuleAssets()
            settingPack.activity = activity
            settingPack.checkUpdate(dialog = false)
            if (sPrefs.getBoolean("daily_sign_in", false))
                handler.post(8000L) {
                    mainScope.launch(Dispatchers.IO) { dailySignIn() }
                }
            if (uiMode == UiMode.NORMAL && !sPrefs.getBoolean("ui_mode_hint", false)) {
                handler.post(2000L) {
                    activity.showMessageDialog(
                        string(R.string.tips_title),
                        string(R.string.tips_open_clean_mode),
                        string(R.string.go_to_mode_setting),
                        string(R.string.i_know),
                        { sPrefs.edit { putBoolean("ui_mode_hint", true) } },
                    ) {
                        sPrefs.edit { putBoolean("ui_mode_hint", true) }
                        val modeFragmentClass = hookInfo.modeFragment.from(classLoader)
                        if (modeFragmentClass == null) {
                            BannerTips.error(R.string.jump_failed)
                            return@showMessageDialog
                        }
                        activity.callMethod(
                            hookInfo.appStarterActivity.addSecondFragment,
                            modeFragmentClass, null
                        )
                    }
                }
            }
        }
        qmPackage.appStarterActivityClass?.hookAfterMethod(
            "onActivityResult",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Intent::class.java
        ) { param ->
            val requestCode = param.args[0] as Int
            val resultCode = param.args[1] as Int
            val data = param.args[2] as? Intent
            settingPack.onActivityResult(requestCode, resultCode, data)
        }
        @Suppress("UNCHECKED_CAST")
        qmPackage.appStarterActivityClass?.hookAfterMethod(
            "onRequestPermissionsResult",
            Int::class.javaPrimitiveType,
            Array<String>::class.java,
            IntArray::class.java,
        ) { param ->
            val requestCode = param.args[0] as Int
            val permissions = param.args[1] as Array<String>
            val grantResults = param.args[2] as IntArray
            settingPack.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
        hookInfo.uiModeManager.replaceMethod({ isThemeForbid }) { false }
        hookInfo.settingView.hookAfterMethod({ setSetting }) { param ->
            val viewGroup = param.thisObject as? ViewGroup ?: return@hookAfterMethod
            settingViewTextFields.forEach {
                viewGroup.getObjectFieldOrNullAs<TextView>(it)?.isSingleLine = false
            }
            val typeField = hookInfo.setting.type
            if (typeField.name.isEmpty()) return@hookAfterMethod
            runCatchingOrNull {
                if (viewGroup.getIntField(typeField) == Setting.Type.DIVIDER.key) return@hookAfterMethod
                (viewGroup.layoutParams as? RelativeLayout.LayoutParams)?.apply {
                    height = RelativeLayout.LayoutParams.WRAP_CONTENT
                }?.let { viewGroup.layoutParams = it }
                if (viewGroup.minimumHeight == 50.dp)
                    viewGroup.setPadding(0, 6.dp, 0, 6.dp)
            }
        }
        if (hookInfo.settingView.setLastClickTime.name.isNotEmpty())
            hookInfo.settingView.hookBeforeMethod({ setLastClickTime }) { it.args[1] = 0L }

        installSettingsEntry()
        hideDrawerShortcuts()

        if (purifyMoreItems.contains("创作者中心") && hookInfo.setting.drawerSettingPack.initKolEnter.name.isNotEmpty())
            hookInfo.setting.drawerSettingPack.replaceMethod({ initKolEnter }) { null }

        if (!purifyRedDots) return
        if (hookInfo.personalEntryView.update.name.isNotEmpty()) {
            hookInfo.personalEntryView.hookAfterMethod({ update }) { param ->
                runCatchingOrNull {
                    param.thisObject.getObjectFieldAs<TextView?>(hookInfo.personalEntryView.rightDescView)
                        ?.run { text = "" }
                    param.thisObject.getObjectFieldAs<View?>(hookInfo.personalEntryView.redDotView)
                        ?.run { visibility = View.GONE }
                }
            }
        }
        if (hookInfo.settingFragment.settingList.name.isNotEmpty()) {
            hookInfo.settingFragment.hookBeforeMethod({ resume }) { param ->
                runCatchingOrNull {
                    param.thisObject.getObjectFieldAs<List<*>>(hookInfo.settingFragment.settingList)
                        .forEach {
                            it?.setObjectField(hookInfo.setting.redDotListener, null)
                        }
                }
            }
        }
    }

    /** 只向「我的 → 更多」的宿主设置包添加入口。 */
    private fun installSettingsEntry() {
        val drawerPack = hookInfo.setting.drawerSettingPack
        val drawerClass = drawerPack.clazz.name.from(classLoader) ?: run {
            Log.w("设置入口：未定位到「更多」设置包")
            return
        }

        fun injectDrawerEntry(host: Any?) {
            if (host == null || host.javaClass != drawerClass) return
            runCatching { injectSettingsEntry(host) }
                .onFailure { Log.e("设置入口：注入失败 ${it.message}"); Log.e(it) }
        }

        if (drawerPack.refresh.name.isNotEmpty()) {
            drawerPack.hookAfterMethod({ refresh }) { param -> injectDrawerEntry(param.thisObject) }
        }

        // 首次读取有时早于刷新。共用方法的兜底也只处理「更多」包。
        val basePackClass = hookInfo.setting.baseSettingPack.clazz.name.ifEmpty {
            "com.tencent.qqmusic.fragment.morefeatures.settings.base.BaseSettingPackage"
        }
        basePackClass.from(classLoader)?.declaredMethods?.firstOrNull {
            it.parameterTypes.isEmpty() && List::class.java.isAssignableFrom(it.returnType)
        }?.let { getSettings ->
            getSettings.declaringClass.hookAfterMethod(getSettings.name) { param ->
                injectDrawerEntry(param.thisObject)
            }
        }
    }

    private fun hideDrawerShortcuts() {
        val hiddenTitles = purifyMoreItems.filter { it.startsWith("top:") }
            .map { it.removePrefix("top:") }.toSet()
        if (hiddenTitles.isEmpty()) return

        val hiddenViews = java.util.WeakHashMap<View, Int>()
        "com.tencent.qqmusic.fragment.morefeatures.ui.TopEntranceView".from(classLoader)
            ?.hookBeforeMethod("setData", List::class.java) { param ->
                val view = param.thisObject as? View ?: return@hookBeforeMethod
                val entries = param.args[0] as? List<*> ?: return@hookBeforeMethod
                val visible = entries.filterNot { entry ->
                    entry?.getObjectFieldOrNullAs<String>("title") in hiddenTitles
                }
                if (visible.size != entries.size) param.args[0] = visible
                if (entries.isNotEmpty() && visible.isEmpty()) {
                    hiddenViews.putIfAbsent(view, view.visibility)
                    view.visibility = View.GONE
                } else if (visible.isNotEmpty()) {
                    hiddenViews.remove(view)?.let { view.visibility = it }
                }
            }
    }

    private fun injectSettingsEntry(host: Any) {
        val settingsField = host.javaClass.findFieldOrNull("settings") ?: run {
            Log.d("设置入口：宿主包里没有 settings 字段")
            return
        }
        @Suppress("UNCHECKED_CAST")
        val settings = settingsField.get(host) as? MutableList<Any?> ?: return

        val entryTitle = string(R.string.app_name)
        val changed = settings.removeAll { entry ->
            val title = entry?.getObjectFieldOrNullAs<String>("titleText")
            title != entryTitle && when (title) {
                "未成年模式" -> "青少年模式" in purifyMoreItems
                "铃声专区" -> "百变彩铃" in purifyMoreItems
                else -> title in purifyMoreItems
            }
        }
        val exists = settings.any { entry ->
            entry?.getObjectFieldOrNullAs<String>("titleText") == entryTitle
        }
        if (exists) {
            if (changed) notifyDrawerChanged(host)
            return
        }

        val entry = Setting.button(
            title = entryTitle,
            summary = string(R.string.module_entry_summary),
            rightDesc = null,
        ) { openModuleSettings() }.toHostSetting() ?: run {
            Log.w("设置入口：构造宿主 Setting 失败（builder 方法不可用）")
            return
        }

        // 插在第一条之后：宿主列表第一项通常是「音乐访问权限」之类的头部条目。
        val index = if (settings.isEmpty()) 0 else 1.coerceAtMost(settings.size)
        settings.add(index, entry)
        Log.i("设置入口：已插入「$entryTitle」（当前 ${settings.size} 条）")

        notifyDrawerChanged(host)
    }

    private fun notifyDrawerChanged(host: Any) {
        handler.post {
            runCatching { host.callMethod("notifyEvent", 0) }
                .onFailure { Log.d("设置入口：触发刷新失败 ${it.message}") }
        }
    }

    private fun openModuleSettings() {
        val activity = HookEnv.currentActivity
            ?: (currentApplication() as? Activity)
        if (activity == null) {
            BannerTips.error(R.string.jump_failed)
            return
        }
        // 首选：Q音 原生设置页（BaseRecyclerSettingFragment 动态子类）。
        if (NativeSettingsPage.openInHost(
                activity, hookInfo.appStarterActivity.addSecondFragment
            )
        ) return
        // 兜底：自绘 Dialog（宿主框架类缺失时用）。
        if (!ModuleSettingsDialog.show(activity)) {
            BannerTips.error(R.string.jump_failed)
        }
    }

    @Synchronized
    private fun vipStationSignIn() {
        if (!isLogin()) {
            Log.i("会员星动福利站：宿主登录状态尚未就绪")
            return
        }
        val account = uin()
        if (account.isBlank() || account == "0") {
            Log.i("会员星动福利站：未取得登录账号")
            return
        }
        val date = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"))
        val month = date.get(Calendar.YEAR) * 100 + date.get(Calendar.MONTH) + 1
        val day = date.get(Calendar.DAY_OF_MONTH)
        val dateKey = "$month-$day"
        val recordKey = "${account}_vip_station_sign_in_record"
        if (sCaches.getString(recordKey, null) == dateKey) return

        fun request(method: String, params: Map<String, Any> = emptyMap()): org.json.JSONObject? {
            val response = webJsonPost(
                "https://u6.y.qq.com/cgi-bin/musics.fcg",
                "music.vipcgi.RenewalPlaySignInCgi", method, params
            )?.toJSONObject()?.optJSONObject("req_0") ?: return null
            val code = response.optInt("code", -1)
            if (code != 0) {
                Log.w("会员星动福利站：$method 返回 $code")
                return null
            }
            return response.optJSONObject("data")
        }

        fun todayStatus(): Int? {
            val days = request("GetMonthSignInData", mapOf("month" to month))
                ?.optJSONArray("days") ?: return null
            return (0 until days.length()).asSequence()
                .mapNotNull { days.optJSONObject(it) }
                .firstOrNull { it.optInt("day") == day }
                ?.optInt("signInStatus", -1)
        }

        runCatching {
            when (todayStatus()) {
                2 -> Log.i("会员星动福利站：今日已签到")
                1 -> {
                    if (!isLogin() || uin() != account) return
                    request("RenewalPlaySignIn") ?: return
                    if (todayStatus() != 2) {
                        Log.w("会员星动福利站：签到后未确认今日状态")
                        return
                    }
                    handler.post { BannerTips.success(R.string.vip_station_sign_in_success) }
                    Log.i("会员星动福利站：今日签到成功")
                }
                else -> {
                    Log.w("会员星动福利站：未能确认今日签到状态")
                    return
                }
            }
            sCaches.edit { putString(recordKey, dateKey) }
        }.onFailure { Log.w("会员星动福利站：签到检查失败（${it.javaClass.simpleName}）") }
    }

    private fun dailySignIn(check: Boolean = true) {
        val recordKey = "${uin()}_daily_sign_in_record"
        if (!isLogin() || sCaches.getString(recordKey, null) == todayFormat)
            return
        runCatching {
            webJsonPost(
                "https://u.y.qq.com/cgi-bin/musics.fcg?_webcgikey=doSignIn",
                "music.actCenter.DaysignactSvr",
                "doSignIn",
                mapOf("date" to "")
            )
        }.onFailure {
            Log.e(it)
        }.onSuccess { json ->
            val response = json?.runCatchingOrNull { toJSONObject() } ?: return
            val code = response.optJSONObject("req_0")
                ?.optJSONObject("data")?.optInt("code") ?: return
            if (code == 0) {
                if (check) {
                    runCatching {
                        webHtmlGet("https://i.y.qq.com/n2/m/client/day_sign/index.html")
                    }.onFailure {
                        Log.e(it)
                    }.onSuccess {
                        dailySignIn(false)
                    }
                } else {
                    sCaches.edit { putString(recordKey, todayFormat) }
                    BannerTips.success(R.string.daily_sign_in_success)
                }
            } else if (code == 2) {
                sCaches.edit { putString(recordKey, todayFormat) }
            }
        }
    }
}