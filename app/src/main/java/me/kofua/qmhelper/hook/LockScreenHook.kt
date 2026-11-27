package me.kofua.qmhelper.hook

import android.content.Context
import android.content.SharedPreferences
import me.kofua.qmhelper.utils.Log
import me.kofua.qmhelper.utils.currentContext
import me.kofua.qmhelper.utils.edit
import me.kofua.qmhelper.utils.from
import me.kofua.qmhelper.utils.hookBeforeMethod
import me.kofua.qmhelper.utils.sPrefs
import java.io.File

/**
 * 强制保持「锁屏图片设置 → 使用QQ音乐锁屏」为关闭。
 *
 * 宿主的开关存在 `qqmusicplayer` 偏好文件的 `enableLockScreen` 键里（默认 true）：
 * 拦掉写入就存不下「开启」，拦掉读取就让开关和锁屏逻辑一律拿到 false。
 */
object LockScreenHook : BaseHook {

    /** 宿主播放器偏好文件名。 */
    private const val PREFS_NAME = "qqmusicplayer"

    /** 「使用QQ音乐锁屏」开关的 key。 */
    private const val KEY_ENABLE = "enableLockScreen"

    /** 模块设置面板里的开关 key。 */
    private const val KEY_MODULE_SWITCH = "disable_lock_screen"

    private var loggedBlockedWrite = false

    override fun hook() = hook(writePrefs = true)

    /** 子进程入口：只装读写闸门，不写 prefs（避免整份文件回写覆盖主进程的改动）。 */
    fun hookInChildProcess() = hook(writePrefs = false)

    private fun hook(writePrefs: Boolean) {
        if (!sPrefs.getBoolean(KEY_MODULE_SWITCH, true)) {
            Log.step("锁屏图片：模块开关已关闭，不干预")
            return
        }
        // 先复位再装钩子，否则下面那条读取闸门会短路掉复位时的取值。
        if (writePrefs) resetStoredSwitch()
        hookWrite()
        hookRead()
        Log.done("锁屏图片：已接管 enableLockScreen")
    }

    /** 把已存的「开启」状态改回关闭。 */
    private fun resetStoredSwitch() {
        try {
            val prefs = hostPrefs()
            if (prefs.getBoolean(KEY_ENABLE, false) || !prefs.contains(KEY_ENABLE)) {
                prefs.edit { putBoolean(KEY_ENABLE, false) }
                Log.done("锁屏图片：enableLockScreen 已复位为 false")
            }
        } catch (t: Throwable) {
            Log.w("锁屏图片：复位开关失败 ${t.message}")
        }
    }

    private fun hostPrefs(): SharedPreferences =
        currentContext.getSharedPreferences(PREFS_NAME, Context.MODE_MULTI_PROCESS)

    /** 写入闸门：把 `putBoolean("enableLockScreen", true)` 就地改成 false。 */
    private fun hookWrite() = try {
        "android.app.SharedPreferencesImpl\$EditorImpl".from(null)
            ?.hookBeforeMethod(
                "putBoolean",
                String::class.java,
                Boolean::class.javaPrimitiveType,
            ) { param ->
                if (param.args[0] != KEY_ENABLE) return@hookBeforeMethod
                if (param.args[1] != true) return@hookBeforeMethod
                if (!isHostPrefs(param.thisObject)) return@hookBeforeMethod
                param.args[1] = false
                if (!loggedBlockedWrite) {
                    loggedBlockedWrite = true
                    Log.step("锁屏图片：拦下一次「开启」写入")
                }
            }
            ?: Log.w("锁屏图片：没找到 SharedPreferencesImpl\$EditorImpl，写入闸门未装")
    } catch (t: Throwable) {
        Log.e("锁屏图片：装写入闸门失败")
        Log.e(t)
    }

    /** 读取闸门：`getBoolean("enableLockScreen", *)` 恒返回 false。 */
    private fun hookRead() = try {
        "android.app.SharedPreferencesImpl".from(null)
            ?.hookBeforeMethod(
                "getBoolean",
                String::class.java,
                Boolean::class.javaPrimitiveType,
            ) { param ->
                if (param.args[0] != KEY_ENABLE) return@hookBeforeMethod
                if (!isHostPrefs(param.thisObject)) return@hookBeforeMethod
                param.result = false
            }
            ?: Log.w("锁屏图片：没找到 SharedPreferencesImpl，读取闸门未装")
    } catch (t: Throwable) {
        Log.e("锁屏图片：装读取闸门失败")
        Log.e(t)
    }

    /**
     * 判断对象是否属于 `qqmusicplayer` 这份偏好。
     *
     * prefs 实例和它的 Editor 内部类身上都带一个 `File` 字段或指向对方的合成字段，
     * 顺着找几层拿到文件名再比，避免写死字段名。
     */
    private fun isHostPrefs(target: Any?): Boolean {
        var current: Any? = target
        repeat(3) {
            val obj = current ?: return false
            val file = try {
                obj.javaClass.declaredFields
                    .firstOrNull { it.type == File::class.java }
                    ?.apply { isAccessible = true }
                    ?.get(obj) as? File
            } catch (_: Throwable) {
                null
            }
            file?.let { return it.name == "$PREFS_NAME.xml" }
            current = try {
                obj.javaClass.declaredFields
                    .firstOrNull { it.type.name == "android.app.SharedPreferencesImpl" }
                    ?.apply { isAccessible = true }
                    ?.get(obj)
            } catch (_: Throwable) {
                null
            }
        }
        return false
    }
}
