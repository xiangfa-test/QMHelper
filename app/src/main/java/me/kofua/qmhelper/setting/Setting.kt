package me.kofua.qmhelper.setting

import android.content.SharedPreferences
import android.view.View
import androidx.annotation.StringRes
import me.kofua.qmhelper.hookInfo
import me.kofua.qmhelper.qmPackage
import me.kofua.qmhelper.utils.*

typealias IsSwitchOn = () -> Boolean
typealias OnSwitchChanged = (enabled: Boolean) -> Unit

/**
 * 一条设置项的**规格**（纯数据），以及"变成宿主 Setting 对象"的转换。
 *
 * ## 为什么要拆成规格 / 宿主对象两半（21.x 适配的改动点）
 *
 * 1.1.4 里 `Setting.switch(...)` 这类工厂**直接返回宿主的 Setting 对象**，模块自己
 * 不保留规格 —— 因为那版把设置页做成了宿主的 Fragment，列表里当然放宿主对象。
 *
 * 21.0.5.3 上宿主那套 Fragment/Provider 框架整个换了（见 [me.kofua.qmhelper.ui.ModuleSettingsDialog]
 * 的说明），模块现在自己画面板，需要的是**规格**：标题、摘要、当前开关值、开关回调。
 * 反过来"往宿主设置列表里插一条入口"仍然需要宿主 `Setting` 对象。
 *
 * 所以这里：
 *  - 工厂方法一律返回规格 [Setting]（不会因为宿主类定位失败而返回 null）；
 *  - 需要宿主对象时显式调 [toHostSetting]（用宿主 `Setting$Builder` 的公开方法构造，
 *    不碰它的私有字段 —— 21.x 的 Builder 字段已经重排过）。
 */
class Setting {
    enum class Type(val key: Int) {
        SWITCH(0), BUTTON_WITHOUT_ARROW(1), BUTTON_WITH_ARROW(2), CATEGORY(3), DIVIDER(6)
    }

    companion object {
        const val TITLE_DIVIDER = "blank space"

        fun get(build: Setting.() -> Unit): Setting = Setting().apply(build)

        fun switch(
            title: String,
            summary: String? = null,
            isSwitchOn: IsSwitchOn? = null,
            onSwitchChanged: OnSwitchChanged? = null
        ) = get {
            this.type = Type.SWITCH
            this.title = title
            this.summary = summary
            this.isSwitchOn = isSwitchOn
            this.onSwitchChanged = onSwitchChanged
        }

        fun switch(
            @StringRes title: Int,
            @StringRes summary: Int? = null,
            isSwitchOn: IsSwitchOn? = null,
            onSwitchChanged: OnSwitchChanged? = null
        ) = switch(string(title), summary?.let { string(it) }, isSwitchOn, onSwitchChanged)

        fun switch(
            key: String,
            title: String,
            summary: String? = null,
            defValue: Boolean = false,
            prefs: SharedPreferences = sPrefs,
        ) = switch(
            title,
            summary,
            { prefs.getBoolean(key, defValue) },
        ) { prefs.edit { putBoolean(key, it) } }

        fun switch(
            key: String,
            @StringRes title: Int,
            @StringRes summary: Int? = null,
            defValue: Boolean = false,
            prefs: SharedPreferences = sPrefs,
        ) = switch(key, string(title), summary?.let { string(it) }, defValue, prefs)

        fun button(
            title: String,
            summary: String? = null,
            rightDesc: String? = null,
            arrow: Boolean = true,
            clickListener: View.OnClickListener? = null
        ) = get {
            this.type = if (arrow) Type.BUTTON_WITH_ARROW else Type.BUTTON_WITHOUT_ARROW
            this.title = title
            this.summary = summary
            this.rightDesc = rightDesc
            this.clickListener = clickListener
        }

        fun button(
            @StringRes title: Int,
            @StringRes summary: Int? = null,
            @StringRes rightDesc: Int? = null,
            arrow: Boolean = true,
            clickListener: View.OnClickListener? = null
        ) = button(
            string(title),
            summary?.let { string(it) },
            rightDesc?.let { string(it) },
            arrow,
            clickListener
        )

        fun category(title: String) = get {
            this.type = Type.CATEGORY
            this.title = title
        }

        fun category(@StringRes title: Int) = category(string(title))

        fun divider() = get {
            this.type = Type.DIVIDER
            this.title = TITLE_DIVIDER
        }
    }

    var type: Type = Type.SWITCH
    var title: String? = null
    var rightDesc: String? = null
    var summary: String? = null
    var isSwitchOn: IsSwitchOn? = null
    var onSwitchChanged: OnSwitchChanged? = null
    var clickListener: View.OnClickListener? = null

    /**
     * 把规格变成宿主的 `Setting` 对象（用于往宿主的设置列表里插条目）。
     *
     * 走 Builder 的**公开方法**：`type(int)` / `title(String)` / `subRightDesc(String)` /
     * `click(OnClickListener)` / `build()`。旧版那套"直接 set builder 私有字段"在 21.x 上
     * 字段已经改名/重排（`subRightDesc`、`switcher` 等），按名字写死会立刻失效。
     *
     * 只做按钮/分类/分割线这几种简单类型；SWITCH 需要宿主 `SettingView$d` 的代理实现，
     * 模块自己的开关已经在面板里渲染，不需要宿主列表里再出现一条开关。
     */
    fun toHostSetting(): Any? = runCatchingOrNull {
        val settingClass = qmPackage.settingClass ?: return null
        val builder = settingClass
            .callStaticMethodOrNull(hookInfo.setting.with, currentContext) ?: return null
        val hostType = hostTypeValue(settingClass, type.key) ?: return null
        builder.callMethod("type", hostType)
        builder.callMethod("title", title ?: "")
        rightDesc?.let { runCatching { builder.callMethod("subRightDesc", it) } }
        clickListener?.let { runCatching { builder.callMethod("click", it) } }
        builder.callMethod("build")
    }

    /**
     * 宿主 `Setting` 上的类型常量（`TYPE_BUTTON_WITH_ARROW` 之类）。
     *
     * 优先按名字取（21.x 里这些常量都在 `Setting` 上，名字未混淆）；
     * 拿不到就退回旧版的数字编号（老版本的编号顺序）。
     */
    private fun hostTypeValue(settingClass: Class<*>, fallback: Int): Int? {
        val names = when (type) {
            Type.SWITCH -> listOf("TYPE_SWITCH")
            Type.BUTTON_WITHOUT_ARROW -> listOf("TYPE_BUTTON_WITHOUT_ARROW")
            Type.BUTTON_WITH_ARROW -> listOf("TYPE_BUTTON_WITH_ARROW")
            Type.CATEGORY -> listOf("TYPE_SECTION", "TYPE_GRUOP_BLANK_HEADER", "TYPE_GROUP_BLANK_HEADER")
            Type.DIVIDER -> listOf("TYPE_BLANK")
        }
        for (name in names) {
            val v = settingClass.getStaticObjectFieldOrNull(name) as? Int
            if (v != null) return v
        }
        return fallback
    }
}