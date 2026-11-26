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
import me.kofua.qmhelper.ui.ModuleSettingsDialog
import me.kofua.qmhelper.utils.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    /**
     * 往宿主「我的 → 更多设置」列表里插一条「Q音助手」入口。
     *
     * ## 为什么挂"抽屉包的刷新方法"
     *
     * 21.0.5.3 的设置页仍然是 Provider 模式：`BaseSettingPackage#settings`（List）是所有
     * 设置页读的同一份列表，各包（`settings.packages.b/c/d`）负责把 provider 的条目汇总进去。
     * 老项目（QQMusicPlus）的做法是挂 `packages.b` 的刷新方法，在它把列表拼好之后补一条 ——
     * 这比"挂 getSettings() 后在宿主遍历时改列表"更安全，也不会在多个页面重复出现。
     *
     * 定位用的字符串 `refreshSettingList: FORCE REFRESH` 在 21.0.5.3 上仍然存在
     * （`settings.packages.b#c()`），随版本变化的风险比类名小。
     *
     * ## 为什么不用宿主的 SettingProvider
     *
     * 宿主 21.x 的 `SettingProvider` 构造签名变成了 `(Context, SettingEvent)`，
     * 而 SettingEvent 只有包自己有；我们没法造。直接把 `Setting` 塞进 `settings` 列表即可，
     * 宿主渲染时只看列表里的 Setting 对象。
     */
    private fun installSettingsEntry() {
        val drawerPack = hookInfo.setting.drawerSettingPack
        var hooked = 0
        if (drawerPack.refresh.name.isNotEmpty()) {
            drawerPack.hookAfterMethod({ refresh }) { param ->
                runCatching { injectSettingsEntry(param.thisObject) }
                    .onFailure { Log.e("设置入口：注入失败 ${it.message}"); Log.e(it) }
            }
            hooked++
            Log.d("设置入口：已挂抽屉刷新注入点（${drawerPack.refresh.name}）")
        }
        // 兜底：直接拦 BaseSettingPackage#getSettings()（所有设置页读列表都走它）。
        // 有些入口路径（比如从搜索/推送深链进来）不经过抽屉包的刷新方法，
        // 只有这条全量兜底能保证入口一定在。
        val basePackClass = hookInfo.setting.baseSettingPack.clazz.name.ifEmpty {
            "com.tencent.qqmusic.fragment.morefeatures.settings.base.BaseSettingPackage"
        }
        basePackClass.from(classLoader)?.let { packClass ->
            val getSettings = packClass.declaredMethods.firstOrNull {
                it.parameterTypes.isEmpty() && List::class.java.isAssignableFrom(it.returnType)
            }
            if (getSettings != null) {
                packClass.hookAfterMethod(getSettings.name) { param ->
                    runCatching { injectSettingsEntry(param.thisObject) }
                        .onFailure { Log.e("设置入口(兜底)：注入失败 ${it.message}"); Log.e(it) }
                }
                hooked++
                Log.d("设置入口：已挂 getSettings 兜底注入点（${packClass.name}#${getSettings.name}）")
            }
        }
        if (hooked == 0) Log.w("设置入口：两个注入点都没挂上，入口不生效")
    }

    private fun injectSettingsEntry(host: Any?) {
        host ?: return
        val settingsField = host.javaClass.findFieldOrNull("settings") ?: run {
            Log.d("设置入口：宿主包里没有 settings 字段")
            return
        }
        @Suppress("UNCHECKED_CAST")
        val settings = settingsField.get(host) as? MutableList<Any?> ?: return

        val entryTitle = string(R.string.app_name)
        val exists = settings.any { entry ->
            entry?.getObjectFieldOrNullAs<String>("titleText") == entryTitle
        }
        if (exists) return

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

        // 列表改完要主动让宿主重建，否则界面还是旧的。
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
        if (me.kofua.qmhelper.setting.NativeSettingsPage.openInHost(
                activity, hookInfo.appStarterActivity.addSecondFragment
            )
        ) return
        // 兜底：自绘 Dialog（宿主框架类缺失时用）。
        if (!ModuleSettingsDialog.show(activity)) {
            BannerTips.error(R.string.jump_failed)
        }
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