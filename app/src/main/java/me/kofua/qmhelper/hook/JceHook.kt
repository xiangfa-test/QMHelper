package me.kofua.qmhelper.hook

import me.kofua.qmhelper.BuildConfig
import me.kofua.qmhelper.hookInfo
import me.kofua.qmhelper.utils.*

object JceHook : BaseHook {
    override fun hook() {
        val hidden = sPrefs.getBoolean("hidden", false)
        val unlockTheme = sPrefs.getBoolean("unlock_theme", false)
        val unlockFont = sPrefs.getBoolean("unlock_font", false)

        hookInfo.jceRespConverter.hookAfterMethod({ parse }) { param ->
            // 21.x 里同一个锚点字符串还会命中别的类，参数不一定是 Class —— 类型对不上就直接放行。
            val type = (param.args.getOrNull(1) as? Class<*>)?.name ?: return@hookAfterMethod
            if (BuildConfig.DEBUG)
                runCatching { Log.d("net.jce, type: $type, json: ${param.result?.toJson()}") }
            if (type == "com.tencent.jce.playerStyle.PlayerStyleRsp" && hidden && unlockTheme) {
                // 21.x 仍在：播放页主题样式解锁。
                param.result?.run {
                    getObjectField("alert")?.setIntField("revertTheme", 0)
                    getObjectField("styleConf")?.setIntField("status", 0)
                }
            } else if (
            // 21.x：com.tencent.jce.personal.{ApplyIconRsp,GetIconRsp} 已不存在
            //（自定义头像挂件入口下线），这两个分支不会命中，保留作旧版本兼容。
                (type == "com.tencent.jce.personal.ApplyIconRsp"
                        || type == "com.tencent.jce.personal.GetIconRsp") && hidden && unlockTheme
            ) {
                param.result?.getObjectField("auth")?.run {
                    setIntField("enable", 1)
                    setIntField("authType", 0)
                }
            } else if (type == "com.tencent.qqmusic.business.playernew.view.playerlyric.model.QueryLyricFontRsp" && hidden && unlockFont) {
                // 21.x 仍在：歌词字体解锁。
                param.result?.getObjectFieldAs<Array<*>?>("fontList")?.forEach {
                    it?.setIntField("auth", 0)
                }
            }
        }
    }
}