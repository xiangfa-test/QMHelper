package me.kofua.qmhelper.hook

import me.kofua.qmhelper.utils.EKeyStore
import me.kofua.qmhelper.utils.Log
import me.kofua.qmhelper.utils.from
import me.kofua.qmhelper.utils.hookAfterMethod
import me.kofua.qmhelper.utils.hookBeforeAllConstructors
import me.kofua.qmhelper.utils.hookBeforeMethod
import java.lang.reflect.Modifier

/**
 * 采集宿主的 eKey，交给 [EKeyStore] 保管。
 *
 * 三个采集点：
 *  - `EKeyDecryptor.<init>(String eKey)` —— 当前曲目用到的 key；
 *  - `EKeyDecryptor$a#a(src, dest, eKey)` —— 文件路径与 key 的对应关系；
 *  - `AudioStreamFileEKeyTable#query/insertOrUpdate` —— 宿主自己的锁定表。
 */
object EKeyHook : BaseHook {

    private const val CLS_DECRYPTOR =
        "com.tencent.qqmusicplayerprocess.audio.playermanager.EKeyDecryptor"
    private const val CLS_COMPANION =
        "com.tencent.qqmusicplayerprocess.audio.playermanager.EKeyDecryptor\$a"
    private const val CLS_EKEY_TABLE =
        "com.tencent.qqmusicplayerprocess.audio.playermanager.AudioStreamFileEKeyTable"

    override fun hook() {
        hookDecryptor()
        hookEKeyTable()
    }

    private fun hookDecryptor() {
        val decryptor = CLS_DECRYPTOR.from(classLoader)
        if (decryptor == null) {
            Log.w("eKey 采集：找不到 EKeyDecryptor")
            return
        }
        try {
            decryptor.hookBeforeAllConstructors { param ->
                EKeyStore.record(null, param.args.getOrNull(0) as? String)
            }
        } catch (t: Throwable) {
            Log.w("eKey 采集：构造器 hook 失败 ${t.message}")
        }

        try {
            val companionClass = CLS_COMPANION.from(classLoader)
            val companion = decryptor.declaredFields
                .firstOrNull { Modifier.isStatic(it.modifiers) && it.type == companionClass }
                ?.apply { isAccessible = true }
                ?.get(null)
            companion?.javaClass?.hookBeforeMethod(
                "a",
                String::class.java,
                String::class.java,
                String::class.java,
            ) { param ->
                EKeyStore.record(param.args[0] as? String, param.args[2] as? String)
            }
            if (companion == null) Log.w("eKey 采集：拿不到 EKeyDecryptor 伴生对象")
        } catch (t: Throwable) {
            Log.w("eKey 采集：整文件解密入口 hook 失败 ${t.message}")
        }
    }

    private fun hookEKeyTable() {
        val table = CLS_EKEY_TABLE.from(classLoader)
        if (table == null) {
            Log.w("eKey 采集：找不到 AudioStreamFileEKeyTable")
            return
        }
        try {
            table.hookAfterMethod("query", String::class.java) { param ->
                EKeyStore.record(param.args[0] as? String, param.result as? String)
            }
            table.hookBeforeMethod("insertOrUpdate", String::class.java, String::class.java) { param ->
                EKeyStore.record(param.args[0] as? String, param.args[1] as? String)
            }
            Log.d("eKey 采集：已接管锁定表读写点")
        } catch (t: Throwable) {
            Log.w("eKey 采集：锁定表 hook 失败 ${t.message}")
        }
    }
}
