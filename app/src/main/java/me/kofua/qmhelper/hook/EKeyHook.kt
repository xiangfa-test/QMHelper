package me.kofua.qmhelper.hook

import me.kofua.qmhelper.utils.EKeyStore
import me.kofua.qmhelper.utils.Log
import me.kofua.qmhelper.utils.from
import me.kofua.qmhelper.utils.hookAfterMethod
import me.kofua.qmhelper.utils.hookBeforeAllConstructors
import me.kofua.qmhelper.utils.hookBeforeMethod

/**
 * 采集宿主的 eKey，交给 [EKeyStore] 保管。
 *
 * 三个采集点：
 *  - `EKeyDecryptor.<init>(String eKey)` —— 当前曲目用到的 key；
 *  - `EKeyDecryptor$a#a(src, dest, eKey)` —— 文件路径与 key 的对应关系；
 *  - `AudioStreamFileEKeyTable#query/insertOrUpdate` —— 宿主自己的锁定表。
 *
 * 只挂方法/构造器，**不读宿主的静态字段**：一读就会触发 `EKeyDecryptor.<clinit>`，
 * 而那个初始化依赖 native 环境，提前触发会直接抛异常。
 */
object EKeyHook : BaseHook {

    private const val CLS_DECRYPTOR =
        "com.tencent.qqmusicplayerprocess.audio.playermanager.EKeyDecryptor"
    private const val CLS_COMPANION =
        "com.tencent.qqmusicplayerprocess.audio.playermanager.EKeyDecryptor\$a"
    private const val CLS_EKEY_TABLE =
        "com.tencent.qqmusicplayerprocess.audio.playermanager.AudioStreamFileEKeyTable"

    override fun hook() {
        hookDecryptorCtor()
        hookDecryptFile()
        hookEKeyTable()
    }

    private fun hookDecryptorCtor() {
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
    }

    private fun hookDecryptFile() {
        val companion = CLS_COMPANION.from(classLoader)
        if (companion == null) {
            Log.w("eKey 采集：找不到 EKeyDecryptor\$a")
            return
        }
        try {
            companion.hookBeforeMethod(
                "a",
                String::class.java,
                String::class.java,
                String::class.java,
            ) { param ->
                EKeyStore.record(param.args[0] as? String, param.args[2] as? String)
            }
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
            Log.done("eKey 采集：已接管锁定表读写点")
        } catch (t: Throwable) {
            Log.w("eKey 采集：锁定表 hook 失败 ${t.message}")
        }
    }
}
