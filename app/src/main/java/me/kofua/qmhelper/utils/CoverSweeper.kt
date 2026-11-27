package me.kofua.qmhelper.utils

import me.kofua.qmhelper.classLoader
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 给**没有加密**的音乐文件补专辑封面。
 *
 * 只处理明文文件（flac / mp3）：QQ 音乐下完一首歌、或者用户自己解出来一个明文文件之后，
 * 顺手把封面写进去。加密文件一律不碰。
 *
 * 触发时机：优先挂在宿主的「下载完成」回调上（`musicdownload.u#onFinish(Object)`、
 * `musicdownload.t#M0(DownloadSongTask)`），事件来了就扫一遍；两个都挂不上时退回
 * 每 15 秒轮询一次。另外启动后 20 秒也会扫一遍，兜住应用关着的时候落下的文件。
 */
object CoverSweeper {

    private const val KEY_SWITCH = "auto_cover_download"

    /** 明文音频扩展名。加密格式（mflac、mgg、qmc 系列）不在这里面，天然被排除。 */
    private val PLAIN_EXTS = setOf("flac", "mp3", "ogg", "m4a", "mp4", "wav")

    /** 只支持写封面的容器。 */
    private val SUPPORTED_EXTS = setOf("flac", "mp3")

    private const val POLL_INTERVAL_MS = 15_000L
    private const val SETTLE_MS = 5_000L
    private const val MAX_DONE = 3000

    private val started = AtomicBoolean(false)
    private val running = AtomicBoolean(false)

    /** 已处理文件的指纹：`路径|大小|修改时间`。 */
    private val done = ConcurrentHashMap.newKeySet<String>()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        loadDone()

        val hooked = installDownloadHooks()
        if (hooked) {
            Log.d("补封面：已挂下载完成回调")
        } else {
            Log.w("补封面：没挂上下载回调，退回每 ${POLL_INTERVAL_MS / 1000}s 轮询")
        }

        Thread({
            // 启动后先扫一遍，再按需轮询
            sleep(20_000)
            sweep("启动扫描")
            if (!hooked) {
                while (!Thread.currentThread().isInterrupted) {
                    sleep(POLL_INTERVAL_MS)
                    sweep("轮询")
                }
            }
        }, "QMHelper-CoverSweeper").apply { isDaemon = true }.start()
    }

    /** 下载完成：等文件落定再扫，避免读到写了一半的东西。 */
    private fun onDownloadFinished() {
        Thread({
            sleep(3_000)
            sweep("下载完成")
        }, "QMHelper-CoverSweeper-trigger").apply { isDaemon = true }.start()
    }

    private fun installDownloadHooks(): Boolean {
        var hooked = false
        runCatching {
            val listener = "com.tencent.qqmusic.business.musicdownload.u".from(classLoader)
            if (listener != null) {
                listener.hookAfterMethod("onFinish", Any::class.java) { onDownloadFinished() }
                hooked = true
            }
        }.onFailure { Log.w("补封面：挂 onFinish 失败 ${it.message}") }
        runCatching {
            val manager = "com.tencent.qqmusic.business.musicdownload.t".from(classLoader)
            val taskClass = "com.tencent.qqmusic.business.musicdownload.DownloadSongTask".from(classLoader)
            if (manager != null && taskClass != null) {
                manager.hookAfterMethod("M0", taskClass) { onDownloadFinished() }
                hooked = true
            }
        }.onFailure { Log.w("补封面：挂 M0 失败 ${it.message}") }
        return hooked
    }

    private fun sweep(reason: String) {
        if (!sPrefs.getBoolean(KEY_SWITCH, false)) return
        if (!running.compareAndSet(false, true)) return
        try {
            var handled = 0
            for (file in plainSongs()) {
                if (attach(file)) handled++
            }
            if (handled > 0) Log.d("补封面（$reason）：处理了 $handled 个文件")
        } catch (t: Throwable) {
            Log.w("补封面（$reason）异常：${t.message}")
        } finally {
            running.set(false)
        }
    }

    /** 扫描目录里的明文音频。 */
    private fun plainSongs(): List<File> = Decryptor.songDirs()
        .flatMap { dir -> dir.listFiles()?.toList() ?: listOf() }
        .filter { it.isFile && extOf(it) != null }
        .sortedBy { it.name.lowercase() }

    private fun attach(file: File): Boolean {
        val ext = extOf(file) ?: return false
        if (ext !in SUPPORTED_EXTS) return false
        val fingerprint = "${file.absolutePath}|${file.length()}|${file.lastModified()}"
        if (fingerprint in done) return false
        // 还在写：最近几秒内被改过就先跳过
        if (System.currentTimeMillis() - file.lastModified() < SETTLE_MS) return false
        if (CoverArt.hasCover(file, ext)) {
            remember(fingerprint)
            return false
        }
        if (!CoverArt.attach(file, file.absolutePath, ext)) {
            remember(fingerprint) // 查不到封面就别每轮重试
            return false
        }
        remember("${file.absolutePath}|${file.length()}|${file.lastModified()}")
        Log.d("补封面：已写入 ${file.name}")
        return true
    }

    private fun extOf(file: File): String? {
        val ext = file.name.substringAfterLast('.', "").lowercase()
        return ext.takeIf { it in PLAIN_EXTS }
    }

    private fun sleep(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
        }
    }

    // ------------------------------------------------------------------ 记录

    private fun remember(fingerprint: String) {
        if (done.size > MAX_DONE) done.clear()
        done.add(fingerprint)
        saveDone()
    }

    private fun storeFile(): File? = runCatching {
        val base = currentContext.getExternalFilesDir(null) ?: currentContext.filesDir
        File(File(base, "qmhelper"), "cover_done.tsv").also { it.parentFile?.mkdirs() }
    }.getOrNull()

    private fun loadDone() {
        val file = storeFile() ?: return
        if (!file.isFile) return
        runCatching {
            file.forEachLine { line ->
                if (line.isNotBlank() && !line.startsWith("#")) done.add(line)
            }
        }.onFailure { Log.w("补封面：读取处理记录失败 ${it.message}") }
    }

    private fun saveDone() {
        val file = storeFile() ?: return
        runCatching {
            file.bufferedWriter().use { writer ->
                writer.write("# path|size|mtime\n")
                done.forEach { writer.write("$it\n") }
            }
        }.onFailure { Log.w("补封面：保存处理记录失败 ${it.message}") }
    }
}
