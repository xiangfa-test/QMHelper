@file:Suppress("unused")

package me.kofua.qmhelper.utils

import android.util.Log as ALog

/**
 * 统一日志出口。
 *
 * ## 级别（设置 → 日志级别）
 *
 * | 级别 | 输出什么 | 用哪个方法 |
 * | --- | --- | --- |
 * | 警告 | 只输出警告和错误 | [w] / [e] |
 * | 正常（默认） | 加上「某个操作完成了」 | [done] / [i] |
 * | 完整 | 每一步都输出 | [step] / [d] / [v] |
 *
 * ## 去处
 *
 * 每条日志都会写 logcat；设置里「写入 LSPosed 日志」打开时（默认开）同时写进
 * 框架日志（模块页 → 日志，或 `/data/adb/lspd/log/modules_*.log`）。
 */
object Log {

    const val LEVEL_WARN = 0
    const val LEVEL_NORMAL = 1
    const val LEVEL_FULL = 2

    private const val keyLevel = "log_level"
    private const val keyToXposed = "log_to_xposed"
    private const val maxLength = 3000
    private const val TAG = "QMHelper"

    /** 当前级别，默认「正常」。设置改动后调用 [refresh]。 */
    @Volatile
    var level: Int = LEVEL_NORMAL
        private set

    /** 是否同时写进 LSPosed 框架日志。 */
    @Volatile
    var toXposed: Boolean = true
        private set

    fun levelName(value: Int = level): String = when (value) {
        LEVEL_WARN -> "警告"
        LEVEL_FULL -> "完整"
        else -> "正常"
    }

    /** 从偏好里重新读一遍级别和去向；偏好还没就绪时保持默认。 */
    fun refresh() {
        if (!tryRefresh()) return
        flushPending()
    }

    /**
     * 偏好是否已就绪。宿主 Context 要到 `callApplicationOnCreate` 才拿得到，
     * 而 `onModuleLoaded` 就已经有日志要打 —— 那之前的日志先攒着，等读了级别再按级别过滤输出。
     */
    @Volatile
    private var ready: Boolean = false

    private val pending = ArrayDeque<Pair<Int, String>>()

    private fun tryRefresh(): Boolean {
        val ok = runCatching {
            val prefs = sPrefs
            level = prefs.getInt(keyLevel, LEVEL_NORMAL).coerceIn(LEVEL_WARN, LEVEL_FULL)
            toXposed = prefs.getBoolean(keyToXposed, true)
        }.isSuccess
        if (ok) ready = true
        return ok
    }

    private fun flushPending() {
        while (pending.isNotEmpty()) {
            val (priority, text) = pending.removeFirst()
            if (allowed(priority)) emitChunks(priority, text)
        }
    }

    private fun allowed(priority: Int): Boolean = when (priority) {
        ALog.ERROR, ALog.WARN -> true
        ALog.INFO -> level >= LEVEL_NORMAL
        else -> level >= LEVEL_FULL
    }

    @JvmStatic
    private fun emit(priority: Int, text: String) {
        ALog.println(priority, TAG, text)
        // 框架还没挂上时跳过，避免同一行在 logcat 里出现两次
        if (toXposed && HookEnv.module != null) HookEnv.log(priority, TAG, text)
    }

    private fun emitChunks(priority: Int, str: String) {
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
    private fun doLog(priority: Int, obj: Any?) {
        val str = if (obj is Throwable) ALog.getStackTraceString(obj) else obj.toString()
        if (!ready && !tryRefresh()) {
            // 级别还不知道，先攒着（最多 200 条，避免启动期把内存吃掉）
            if (pending.size < 200) pending.addLast(priority to str)
            return
        }
        if (!allowed(priority)) return
        emitChunks(priority, str)
    }

    /** 操作完成（正常级别可见）。 */
    @JvmStatic
    fun done(obj: Any?) = doLog(ALog.INFO, obj)

    /** 每一步细节（完整级别才可见）。 */
    @JvmStatic
    fun step(obj: Any?) = doLog(ALog.DEBUG, obj)

    /** 警告（任何级别都可见）。 */
    @JvmStatic
    fun w(obj: Any?) = doLog(ALog.WARN, obj)

    /** 错误（任何级别都可见）。 */
    @JvmStatic
    fun e(obj: Any?) = doLog(ALog.ERROR, obj)

    @JvmStatic
    fun i(obj: Any?) = doLog(ALog.INFO, obj)

    @JvmStatic
    fun d(obj: Any?) = doLog(ALog.DEBUG, obj)

    @JvmStatic
    fun v(obj: Any?) = doLog(ALog.VERBOSE, obj)
}