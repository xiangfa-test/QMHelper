package me.kofua.qmhelper.ui

import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import me.kofua.qmhelper.R
import me.kofua.qmhelper.setting.Setting
import me.kofua.qmhelper.setting.SettingPack
import me.kofua.qmhelper.utils.Log
import me.kofua.qmhelper.utils.dp

/**
 * 模块设置面板（**自带 UI，不再代理宿主的设置 Fragment**）。
 *
 * ## 为什么改成这样
 *
 * 1.1.4 及更早的做法是：`dexmaker` 动态生成宿主 `BaseSettingFragment` / `BaseSettingPack`
 * / `BaseSettingProvider` 的子类，把模块自己的设置项塞进宿主那套框架里。
 * 那套东西在 Q音 21.0.5.3 上**整体不成立了**：
 *
 *  - `fragment.debug.DebugSettingFragment`（原来的定位锚点）、`settings.view.PersonalCenterEntryView`、
 *    `ui.MainDesktopHeader` 这些类在 21.x 里已经不存在；
 *  - 设置框架改成了 `settings.base.{Setting, Setting$Builder, SettingPackage, BaseSettingPackage,
 *    SettingProvider}` + `settings.packages.{a,b,c,d}`，`BaseSettingFragment` 没了；
 *  - 于是 `baseSettingFragment/baseSettingPack/baseSettingProvider` 三件套全部定位失败，
 *    模块自己的设置页打不开（用户只看到"可以在 Q音助手中开启简洁模式"的提示，点了没反应）。
 *
 * 现在改成两层：
 *
 *  - **入口**：往宿主「我的 → 更多设置」列表里插一条 `Setting`（见 [me.kofua.qmhelper.hook.SettingsHook]）。
 *    这条走宿主自己的 `Setting$Builder`，外观和原生条目一致。
 *  - **面板**：点入口后**在宿主窗口里弹一个我们自己的 Dialog**（就是本文件），
 *    内容由 [SettingPack.getSettings] 的规格列表渲染成普通 Android View。
 *
 * 这样完全不依赖宿主的 Fragment/Provider 框架 —— 那是每个版本都会重排的东西 ——
 * 只依赖 `Setting$Builder` 的四个公开方法（`type` / `title` / `subRightDesc` / `click` / `build`），
 * 这些在 21.x 上实测都在。
 */
object ModuleSettingsDialog {

    private val settingPack = SettingPack()

    @Volatile
    private var dialog: AlertDialog? = null

    /**
     * 在宿主当前 Activity 上弹面板。
     *
     * [activity] 用宿主的 Activity（模块 UI 跑在宿主进程里），
     * 这样不用额外的 Activity 声明，也不需要跳出去。
     */
    fun show(activity: android.app.Activity): Boolean {
        return try {
            settingPack.activity = activity
            if (dialog?.isShowing == true) {
                dialog?.dismiss()
                dialog = null
            }
            val context: Context = activity
            val dark = (context.resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val d = AlertDialog.Builder(context)
                .setTitle(R.string.app_name)
                .setView(buildContent(context, dark))
                .setNegativeButton(android.R.string.cancel, null)
                .create()
            d.setOnDismissListener { dialog = null }
            d.show()
            dialog = d
            Log.d("模块设置面板已打开（${if (dark) "dark" else "light"}）")
            true
        } catch (t: Throwable) {
            Log.e("模块设置面板打开失败")
            Log.e(t)
            false
        }
    }

    fun dismiss() {
        runCatching { dialog?.dismiss() }
        dialog = null
    }

    // ------------------------------------------------------------------ 渲染

    private fun buildContent(context: Context, dark: Boolean): View {
        val textColor = if (dark) Color.WHITE else Color.BLACK
        val subColor = if (dark) 0xFFAAAAAA.toInt() else 0xFF666666.toInt()
        val divider = if (dark) 0xFF333333.toInt() else 0xFFE0E0E0.toInt()
        val highlight = if (dark) 0xFF1F1F1F.toInt() else 0xFFFFFFFF.toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 6.dp, 0, 6.dp)
        }

        val specs = runCatching { settingPack.getSettings() }
            .onFailure { Log.e("读取设置规格失败"); Log.e(it) }
            .getOrDefault(emptyList())

        for (spec in specs) {
            when (spec.type) {
                Setting.Type.CATEGORY -> root.addView(
                    TextView(context).apply {
                        text = spec.title
                        setTextColor(subColor)
                        textSize = 13f
                        setPadding(20.dp, 14.dp, 20.dp, 6.dp)
                    }
                )

                Setting.Type.DIVIDER -> root.addView(
                    View(context).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, 1.dp
                        )
                        setBackgroundColor(divider)
                        setPadding(0, 0, 0, 0)
                    }
                )

                Setting.Type.SWITCH -> root.addView(buildSwitchRow(context, spec, textColor, subColor, highlight))

                else -> root.addView(buildButtonRow(context, spec, textColor, subColor, highlight))
            }
        }

        if (specs.isEmpty()) {
            root.addView(
                TextView(context).apply {
                    text = "（没有可显示的设置项）"
                    setTextColor(subColor)
                    setPadding(20.dp, 20.dp, 20.dp, 20.dp)
                }
            )
        }

        return ScrollView(context).apply {
            addView(root)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (context.resources.displayMetrics.heightPixels * 0.7f).toInt()
            )
        }
    }

    private fun buildSwitchRow(
        context: Context,
        spec: Setting,
        textColor: Int,
        subColor: Int,
        highlight: Int,
    ): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20.dp, 10.dp, 20.dp, 10.dp)
            setBackgroundColor(highlight)
        }
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(context).apply {
            text = spec.title
            setTextColor(textColor)
            textSize = 16f
        })
        spec.summary?.takeIf { it.isNotEmpty() }?.let {
            texts.addView(TextView(context).apply {
                text = it
                setTextColor(subColor)
                textSize = 12f
            })
        }
        row.addView(texts)

        val switch = Switch(context).apply {
            isChecked = runCatching { spec.isSwitchOn?.invoke() ?: false }.getOrDefault(false)
        }
        switch.setOnCheckedChangeListener { _, checked ->
            runCatching { spec.onSwitchChanged?.invoke(checked) }
                .onFailure { Log.e("开关 ${spec.title} 写入失败"); Log.e(it) }
        }
        row.addView(switch)
        return row
    }

    private fun buildButtonRow(
        context: Context,
        spec: Setting,
        textColor: Int,
        subColor: Int,
        highlight: Int,
    ): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20.dp, 12.dp, 20.dp, 12.dp)
            setBackgroundColor(highlight)
            isClickable = true
        }
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(context).apply {
            text = spec.title
            setTextColor(textColor)
            textSize = 16f
        })
        spec.summary?.takeIf { it.isNotEmpty() }?.let {
            texts.addView(TextView(context).apply {
                text = it
                setTextColor(subColor)
                textSize = 12f
            })
        }
        row.addView(texts)
        spec.rightDesc?.takeIf { it.isNotEmpty() }?.let {
            row.addView(TextView(context).apply {
                text = it
                setTextColor(subColor)
                textSize = 13f
            })
        }
        row.setOnClickListener { view ->
            runCatching { spec.clickListener?.onClick(view) }
                .onFailure { Log.e("点击 ${spec.title} 失效"); Log.e(it) }
        }
        return row
    }


}