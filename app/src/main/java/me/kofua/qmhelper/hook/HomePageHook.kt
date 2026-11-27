package me.kofua.qmhelper.hook

import android.view.View
import me.kofua.qmhelper.from
import me.kofua.qmhelper.hookInfo
import me.kofua.qmhelper.utils.*

object HomePageHook : BaseHook {

    // These obfuscated methods were verified against QQ Music 21.0.5.5.
    val supportsMinePullRemoval by lazy { getVersionCode(hostPackageName) == 7755 }

    override fun hook() {
        if (supportsMinePullRemoval && sPrefs.getBoolean("hide_mine_pull_entrance", false)) {
            val controller = "com.tencent.qqmusic.fragment.mymusic.my.mytabpull.l".from(classLoader)
            val canPull = controller?.declaredMethods?.firstOrNull {
                it.name == "i" && it.parameterCount == 0 && it.returnType == Boolean::class.javaPrimitiveType
            }
            if (canPull?.replace { false } != null) {
                Log.d("我的页：已关闭顶部下拉活动入口")
            } else {
                Log.w("我的页：未定位到顶部下拉活动入口")
            }
        }
        if (sPrefs.getBoolean("hide_music_world", false)) {
            // 21.x：音乐世界 = cocos 容器，唯一入口是 MusicWorldActivity.Companion.startGame(...)。
            // 直接拦 startGame（含 $default 桥接），点了也进不去；老的 pullEntrance / mainDesktopHeader
            // 在 21.x 都已不存在。
            "com.tencent.qqmusic.cocos.lib.nolib.MusicWorldActivity\$Companion".from(classLoader)
                ?.let { companion ->
                    companion.hookBeforeMethod("startGame") { param -> param.result = null }
                    companion.hookBeforeMethod("startGame\$default") { param -> param.result = null }
                    Log.d("音乐世界：已拦 startGame 入口")
                } ?: Log.w("音乐世界：MusicWorldActivity\$Companion 没找到（cocos 包被裁剪？）")
        }
        if (sPrefs.getBoolean("hide_vip_bubble", false)) {
            hookInfo.userInfoHolder.replaceMethod({ showBubble }) { null }
            hookInfo.vipAdBarData.from(classLoader)?.declaredConstructors
                ?.find { m -> m.parameterTypes.let { it.size == 10 && it[8] == Boolean::class.javaPrimitiveType } }
                ?.hookBefore { it.args[8] = true }
        }
        if (sPrefs.getBoolean("purify_live_guide", false)) {
            hookInfo.topAreaDelegate.replaceMethod({ initLiveGuide }) { null }
            hookInfo.topAreaDelegate.showCurListen.name.ifNotEmpty {
                hookInfo.topAreaDelegate.replaceMethod({ showCurListen }) { null }
            }
            if (supportsMinePullRemoval) {
                val manager = "com.tencent.qqmusic.business.currentlisten.c".from(classLoader)
                val canShowLabel = manager?.declaredMethods?.firstOrNull {
                    it.name == "m" && it.returnType == Boolean::class.javaPrimitiveType &&
                        it.parameterTypes.singleOrNull()?.name == "com.tencent.qqmusicplayerprocess.songinfo.SongInfo"
                }
                if (canShowLabel?.replace { false } != null) {
                    Log.d("当前收听：已关闭歌曲页和歌词页的在听标签")
                } else {
                    Log.w("当前收听：未定位到在听标签显示条件")
                }
            }
        }
        if (sPrefs.getBoolean("purify_share_guide", false)) {
            hookInfo.topAreaDelegate.replaceMethod({ showShareGuide }) { null }
        }
        if (sPrefs.getBoolean("forbid_slide", false)) {
            hookInfo.playViewModel.hookBeforeMethod({ setCanSlide }) {
                it.args[0].callMethod(hookInfo.playViewModel.postCanSlide, false)
                it.result = null
            }
        }
        if (sPrefs.getBoolean("hide_ad_bar", false)) {
            hookInfo.adBar.clazz.from(classLoader)?.run {
                hookAfterAllConstructors { param ->
                    val view = param.thisObject as View
                    view.visibility = View.GONE
                }
                val methods = hookInfo.adBar
                    .methods.takeIf { it.size == 2 } ?: return@run
                val methodA = methods[0].name
                val methodB = methods[1].name
                hookAfterMethod(methodA) { param ->
                    val view = param.thisObject as View
                    if (view.visibility == View.VISIBLE)
                        view.callMethod(methodB)
                }
                hookAfterMethod(methodB) { param ->
                    val view = param.thisObject as View
                    if (view.visibility == View.VISIBLE)
                        view.callMethod(methodA)
                }
            }
        }
        if (sPrefs.getBoolean("block_bottom_tips", false)) {
            hookInfo.bottomTipController.replaceMethod({ updateBottomTips }) { null }
        }
        val blockCoverAds = sPrefs.getStringSet("block_cover_ads", null) ?: setOf()
        if (blockCoverAds.contains("video")) {
            hookInfo.videoViewDelegate.replaceMethod({ onResult }) { null }
        }
        if (blockCoverAds.contains("genre")) {
            hookInfo.genreViewDelegate.replaceMethod({ onBind }) { null }
            hookInfo.topSongViewDelegate.replaceMethod({ onBind }) { null }
        }
        if (sPrefs.getBoolean("block_user_guide", false)) {
            hookInfo.userGuideViewDelegate.replaceMethod({ showUserGuide }) { null }
        }
    }
}
