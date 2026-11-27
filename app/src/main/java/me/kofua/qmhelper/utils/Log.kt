@file:Suppress("unused")

package me.kofua.qmhelper.utils

import android.util.Log as ALog

/**
 * 统一日志出口。
 *
 * 每条日志同时写两个地方：
 *  - logcat（`adb logcat -s QMHelper:V` 看）
 *  - LSPosed 框架日志（模块页 → 日志，或 `/data/adb/lspd/log/` 下的文件）
 *
 * 框架日志默认开启，不需要再开任何开关。逐条过长时按 3000 字切块，避免被框架截断。
 */
object Log {
    private const val maxLength = 3000
    private const val TAG = "QMHelper"

    @JvmStatic
    private fun emit(priority: Int, text: String) {
        ALog.println(priority, TAG, text)
        // 框架还没挂上时（onModuleLoaded 之前）跳过，避免把同一行写两次 logcat
        if (HookEnv.module != null) HookEnv.log(priority, TAG, text)
    }

    @JvmStatic
    private fun doLog(priority: Int, obj: Any?) {
        val str = if (obj is Throwable) ALog.getStackTraceString(obj) else obj.toString()
        if (str.length <= maxLength) {
            emit(priority, str)
            return
        }
        var start = 0
        while (start < str.length) {
            val end = minOf(start + maxLength, str.length)
            emit(priority, str.substring(start, end))
            start = end
        }
    }

    @JvmStatic
    fun d(obj: Any?) = doLog(ALog.DEBUG, obj)

    @JvmStatic
    fun i(obj: Any?) = doLog(ALog.INFO, obj)

    @JvmStatic
    fun e(obj: Any?) = doLog(ALog.ERROR, obj)

    @JvmStatic
    fun v(obj: Any?) = doLog(ALog.VERBOSE, obj)

    @JvmStatic
    fun w(obj: Any?) = doLog(ALog.WARN, obj)
}