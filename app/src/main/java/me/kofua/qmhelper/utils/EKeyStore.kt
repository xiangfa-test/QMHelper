package me.kofua.qmhelper.utils

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 加密文件路径 ↔ eKey 的记录表。
 *
 * eKey 只有在宿主真正播放/解密某首歌时才会出现，所以这里既接住运行时捕获到的 key，
 * 也把它落到磁盘，供之后的批量解密使用（主进程与播放器进程共享同一个外部目录）。
 */
object EKeyStore {

    private const val FILE_NAME = "ekey.tsv"
    private const val MAX_ENTRIES = 2000
    private const val PERSIST_INTERVAL_MS = 1500L

    private val byPath = ConcurrentHashMap<String, String>()
    private var lastEKey = ""
    private var loaded = false
    private var lastPersistAt = 0L

    /** 按文件路径查 eKey；路径不完全一致时退回按文件名匹配。 */
    fun find(path: String): String? {
        ensureLoaded()
        byPath[path]?.takeIf { it.isNotEmpty() }?.let { return it }
        val name = File(path).name
        if (name.isEmpty()) return null
        return byPath.entries
            .firstOrNull { File(it.key).name == name }
            ?.value
            ?.takeIf { it.isNotEmpty() }
    }

    /** 最近一次见到的 eKey（单曲解密时常用来兜底）。 */
    fun last(): String {
        ensureLoaded()
        return lastEKey
    }

    /**
     * 记录一条 eKey。
     *
     * @param path 加密文件路径；只拿到 key 不知道文件时传 null
     */
    fun record(path: String?, eKey: String?) {
        if (eKey.isNullOrEmpty() || eKey.length < 8) return
        ensureLoaded()
        lastEKey = eKey
        if (path.isNullOrEmpty()) {
            persist(force = true)
            return
        }
        if (byPath[path] == eKey) return
        byPath[path] = eKey
        while (byPath.size > MAX_ENTRIES) {
            val oldest = byPath.keys.firstOrNull() ?: break
            byPath.remove(oldest)
        }
        persist(force = false)
    }

    fun clear() {
        byPath.clear()
        lastEKey = ""
        loaded = true
        runCatching { storeFile()?.delete() }
    }

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val file = storeFile() ?: return
        if (!file.isFile) return
        runCatching {
            file.forEachLine { line ->
                if (line.isBlank() || line.startsWith("#")) return@forEachLine
                val parts = line.split('\t')
                if (parts.size >= 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                    byPath[parts[0]] = parts[1]
                    lastEKey = parts[1]
                }
            }
        }.onFailure { Log.w("eKey 清单读取失败：${it.message}") }
    }

    @Synchronized
    private fun persist(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPersistAt < PERSIST_INTERVAL_MS) return
        lastPersistAt = now
        val file = storeFile() ?: return
        runCatching {
            file.parentFile?.mkdirs()
            file.bufferedWriter().use { writer ->
                writer.write("# path\tekey\n")
                byPath.forEach { (path, key) -> writer.write("$path\t$key\n") }
            }
        }.onFailure { Log.w("eKey 清单落盘失败：${it.message}") }
    }

    private fun storeFile(): File? = runCatching {
        val base = currentContext.getExternalFilesDir(null) ?: currentContext.filesDir
        File(File(base, "qmhelper"), FILE_NAME)
    }.getOrNull()
}
