package me.kofua.qmhelper.utils

import android.media.MediaScannerConnection
import android.os.Environment
import me.kofua.qmhelper.classLoader
import me.kofua.qmhelper.data.StorageVolume.Companion.toMockVolume
import me.kofua.qmhelper.hookInfo
import me.kofua.qmhelper.qmPackage
import java.io.File
import java.io.RandomAccessFile
import java.lang.reflect.Modifier

typealias SingleDecryptListener = (
    srcFile: File, current: Int, total: Int, success: Boolean
) -> Unit

/**
 * 已下载音乐的批量解密。
 *
 * 流程：找加密文件 → 取该文件的 eKey → 用宿主的解密器写出明文 → **校验明文真的是音频**
 * → 按真实容器改扩展名 → 丢给媒体扫描。任何一步不过就判失败，绝不留下半成品。
 */
object Decryptor {

    /** 加密扩展名 → 明文扩展名（只用于命名参考，最终以产物真实容器为准）。 */
    private val ENC_TO_PLAIN = mapOf(
        "qmcflac" to "flac",
        "qmcogg" to "ogg",
        "qmc0" to "mp3",
        "qmc3" to "mp3",
        "qmc2" to "m4a",
        "qmc4" to "m4a",
        "qmc6" to "m4a",
        "qmc8" to "m4a",
        "qmcra" to "m4a",
        "qmcvinyl" to "m4a",
        "qmcwy" to "m4a",
        "mgg" to "ogg",
        "mgg0" to "ogg",
        "mgg1" to "ogg",
        "mgg2" to "ogg",
        "mgg6" to "ogg",
        "mgg7" to "ogg",
        "mggc0" to "ogg",
        "mggc1" to "ogg",
        "mggc2" to "ogg",
        "mggc3" to "ogg",
        "mggc4" to "ogg",
        "mggc5" to "ogg",
        "mflac" to "flac",
        "mflac0" to "flac",
        "mflac1" to "flac",
        "mflac2" to "flac",
        "mflac3" to "flac",
        "mflac4" to "flac",
        "mflac5" to "flac",
        "mflacc0" to "flac",
        "mflacc1" to "flac",
        "mflacc2" to "flac",
        "mflacc3" to "flac",
        "mflacc4" to "flac",
        "mflacc5" to "flac",
        "mdolby" to "flac",
        "mmp4" to "mp4",
    )

    /** 明文扩展名集合，用于识别「名字里既有加密又有明文扩展名」的情况。 */
    private val PLAIN_EXTS = setOf("flac", "ogg", "mp3", "m4a", "mp4", "wav", "ape")

    /** 文件名里的下载标记，如 `xxx [mqms2]`。 */
    private val MQMS_RE = Regex("""\s*\[mqms\d*]""")

    /** 解密块大小，与宿主一致。 */
    private const val CHUNK = 0x2000

    /** 容器魔数探测范围。 */
    private const val HEAD_SCAN = 2048

    private const val CLS_DECRYPTOR =
        "com.tencent.qqmusicplayerprocess.audio.playermanager.EKeyDecryptor"
    private const val CLS_COMPANION =
        "com.tencent.qqmusicplayerprocess.audio.playermanager.EKeyDecryptor\$a"

    // ------------------------------------------------------------------ 对外

    fun batchDecrypt(
        saveDir: File,
        listener: SingleDecryptListener? = null
    ): Triple<Int, Int, List<File>> {
        val encSongs = getEncSongs()
        if (encSongs.isEmpty()) return Triple(0, 0, listOf())
        val total = encSongs.size
        var current = 0
        val successOrigSongs = mutableListOf<File>()
        var success = 0
        for (src in encSongs) {
            current++
            val ok = decrypt(src, saveDir)
            listener?.invoke(src, current, total, ok)
            if (ok) {
                success++
                successOrigSongs.add(src)
            }
        }
        return Triple(total, success, successOrigSongs)
    }

    fun deleteOrigSongs(songs: List<File>) = songs.forEach { it.delete() }

    /** 该文件名是否为 QQ 音乐的加密音乐。 */
    fun isEncrypted(file: File): Boolean = parseName(file) != null

    // ------------------------------------------------------------------ 单文件

    private fun decrypt(src: File, saveDir: File?): Boolean {
        if (!src.isFile) return false
        val parsed = parseName(src) ?: return false
        val destDir = saveDir ?: src.parentFile ?: return false
        destDir.mkdirs()

        val eKey = resolveEKey(src.absolutePath)
        if (eKey.isEmpty()) {
            Log.w("解密跳过：${src.name} 没有 eKey（先在 Q音 里播放一次这首歌）")
            return false
        }

        val temp = File(destDir, ".${parsed.base}.decrypting")
        temp.delete()

        val produced = decryptToTemp(src, temp, eKey)
        if (!produced) {
            temp.delete()
            Log.w("解密失败：${src.name}（宿主解密器没有产出内容）")
            return false
        }

        val container = inspectAndTrim(temp)
        if (container == null) {
            temp.delete()
            Log.w("解密失败：${src.name}（产物不是可识别的音频容器）")
            return false
        }

        val dest = uniqueFile(destDir, "${parsed.base}.$container")
        if (!moveTo(temp, dest)) {
            temp.delete()
            Log.w("解密失败：${src.name}（写入目标文件失败）")
            return false
        }
        if (CoverArt.attach(dest, src.absolutePath, container)) {
            Log.done("封面已写入：${dest.name}")
        }
        scanFile(dest)
        Log.done("解密完成：${src.name} -> ${dest.absolutePath}（${dest.length()} 字节）")
        return true
    }

    /** 用宿主的解密器把 src 解到 temp；两种驱动方式都试一遍。 */
    private fun decryptToTemp(src: File, temp: File, eKey: String): Boolean {
        if (decryptViaCompanion(src, temp, eKey) && temp.length() > 0) return true
        temp.delete()
        if (decryptChunked(src, temp, eKey) && temp.length() > 0) return true
        return false
    }

    /** 宿主的整文件入口：`EKeyDecryptor$a#a(src, dest, eKey)`。 */
    private fun decryptViaCompanion(src: File, dest: File, eKey: String): Boolean = try {
        val companion = decryptorCompanion() ?: return false
        companion.callMethod("a", src.absolutePath, dest.absolutePath, eKey)
        dest.isFile && dest.length() > 0
    } catch (t: Throwable) {
        Log.w("整文件解密失败：${t.javaClass.simpleName} ${t.message}")
        false
    }

    /** 手动分块：`EKeyDecryptor(eKey)` + `d(offset, buffer, length)` + `c()`。 */
    private fun decryptChunked(src: File, dest: File, eKey: String): Boolean = try {
        val clazz = decryptorClass() ?: return false
        val ctor = clazz.getDeclaredConstructor(String::class.java).apply { isAccessible = true }
        val decrypt = clazz.getDeclaredMethod(
            "d",
            java.lang.Long.TYPE,
            ByteArray::class.java,
            Integer.TYPE,
        ).apply { isAccessible = true }
        val release = runCatching {
            clazz.getDeclaredMethod("c").apply { isAccessible = true }
        }.getOrNull()

        var ok = true
        val unit = ctor.newInstance(eKey)
        try {
            src.inputStream().use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(CHUNK)
                    var offset = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        val chunkOk = decrypt.invoke(unit, offset, buffer, read) as? Boolean ?: false
                        if (!chunkOk) {
                            ok = false
                            break
                        }
                        output.write(buffer, 0, read)
                        offset += read
                    }
                }
            }
        } finally {
            runCatching { release?.invoke(unit) }
        }
        ok && dest.isFile && dest.length() > 0
    } catch (t: Throwable) {
        Log.w("分块解密失败：${t.javaClass.simpleName} ${t.message}")
        false
    }

    // ------------------------------------------------------------------ eKey

    private fun resolveEKey(path: String): String {
        EKeyStore.find(path)?.let { return it }

        val fromHost = runCatching {
            qmPackage.eKeyManagerClass
                ?.getStaticObjectFieldOrNull(hookInfo.eKeyManager.instance)
                ?.callMethodAs<String?>(hookInfo.eKeyManager.getFileEKey, path, null)
        }.getOrNull()
        if (!fromHost.isNullOrEmpty()) {
            EKeyStore.record(path, fromHost)
            return fromHost
        }

        val fromTable = runCatching {
            val table = CLS_TABLE.from(classLoader) ?: return@runCatching null
            val instance = table.getStaticObjectFieldOrNull("INSTANCE") ?: table.newInstance()
            instance.callMethodAs<String?>("query", path)
        }.getOrNull()
        if (!fromTable.isNullOrEmpty()) {
            EKeyStore.record(path, fromTable)
            return fromTable
        }

        // 单曲场景：宿主刚播过的那首，key 还热着
        return EKeyStore.last()
    }

    private const val CLS_TABLE =
        "com.tencent.qqmusicplayerprocess.audio.playermanager.AudioStreamFileEKeyTable"

    private val decryptorClassCache by lazy {
        CLS_DECRYPTOR.from(classLoader) ?: qmPackage.eKeyDecryptorClass
    }

    private fun decryptorClass(): Class<*>? = decryptorClassCache

    private fun decryptorCompanion(): Any? = runCatching {
        val clazz = decryptorClass() ?: return@runCatching null
        val companionClass = CLS_COMPANION.from(classLoader)
        clazz.declaredFields
            .firstOrNull { Modifier.isStatic(it.modifiers) && it.type == companionClass }
            ?.apply { isAccessible = true }
            ?.get(null)
    }.getOrNull()

    // ------------------------------------------------------------------ 校验

    /**
     * 确认产物是音频：按魔数认出容器类型；开头若有杂质就裁掉。
     *
     * @return 容器对应的扩展名；认不出来返回 null
     */
    private fun inspectAndTrim(file: File): String? {
        if (!file.isFile || file.length() < 1024) return null
        val head = ByteArray(minOf(HEAD_SCAN.toLong(), file.length()).toInt())
        RandomAccessFile(file, "r").use { it.readFully(head) }
        val hit = detectContainer(head) ?: return null
        if (hit.second > 0) {
            Log.step("解密产物开头有 ${hit.second} 字节杂质，已裁掉")
            trimHead(file, hit.second)
        }
        return hit.first
    }

    /** 返回 (扩展名, 魔数偏移)。 */
    private fun detectContainer(head: ByteArray): Pair<String, Int>? {
        val start = 0
        for (offset in start..(head.size - 12).coerceAtLeast(0)) {
            if (offset + 12 > head.size) break
            val b0 = head[offset].toInt() and 0xFF
            val b1 = head[offset + 1].toInt() and 0xFF
            val b2 = head[offset + 2].toInt() and 0xFF
            val b3 = head[offset + 3].toInt() and 0xFF
            when {
                b0 == 0x66 && b1 == 0x4C && b2 == 0x61 && b3 == 0x43 -> "flac" to offset // fLaC
                b0 == 0x4F && b1 == 0x67 && b2 == 0x67 && b3 == 0x53 -> "ogg" to offset // OggS
                b0 == 0x49 && b1 == 0x44 && b2 == 0x33 -> "mp3" to offset // ID3
                b0 == 0xFF && (b1 and 0xE0) == 0xE0 -> "mp3" to offset // MPEG 帧同步
                b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46 -> "wav" to offset // RIFF
                offset >= 4 && b0 == 0x66 && b1 == 0x74 && b2 == 0x79 && b3 == 0x70 ->
                    "m4a" to offset - 4 // ....ftyp
                else -> null
            }?.let { return it }
        }
        return null
    }

    private fun trimHead(file: File, offset: Int) {
        val temp = File(file.parentFile, "${file.name}.trim")
        runCatching {
            temp.delete()
            file.inputStream().use { input ->
                input.skip(offset.toLong())
                temp.outputStream().use { output -> input.copyTo(output, 1 shl 16) }
            }
            if (temp.length() <= 0) return@runCatching
            file.delete()
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
        }.onFailure { Log.w("裁剪产物头部失败：${it.message}") }
        temp.delete()
    }

    // ------------------------------------------------------------------ 文件

    private fun parseName(file: File): ParsedName? {
        val parts = file.name.split('.')
        if (parts.size < 2) return null
        for (i in parts.indices.reversed()) {
            val part = parts[i].lowercase()
            if (part in PLAIN_EXTS) continue // 双扩展名：先跳过明文扩展名继续往前找
            if (ENC_TO_PLAIN.containsKey(part)) {
                val base = parts.subList(0, i).joinToString(".")
                if (base.isBlank()) return null
                return ParsedName(base.replace(MQMS_RE, "").trim(), ENC_TO_PLAIN.getValue(part))
            }
            return null
        }
        return null
    }

    /** 所有可能存放下载音乐的目录。 */
    fun songDirs(): List<File> {
        val dirs = linkedSetOf<File>()
        runCatching {
            qmPackage.storageUtilsClass
                ?.callStaticMethodAs<Set<*>>(hookInfo.storageUtils.getVolumes, currentContext)
                ?.mapNotNull { it?.toMockVolume()?.path }
                ?.forEach { path ->
                    dirs.add(File(path, "qqmusic/song"))
                    dirs.add(File(path, "Music/qqmusic/song"))
                    dirs.add(File(path, "Android/data/${hostPackageName}/files/qqmusic/song"))
                }
        }.onFailure { Log.w("读取存储卷失败：${it.message}") }
        val external = Environment.getExternalStorageDirectory()
        dirs.add(File(external, "qqmusic/song"))
        dirs.add(File(external, "Music/qqmusic/song"))
        dirs.add(File(external, "Android/data/${hostPackageName}/files/qqmusic/song"))
        return dirs.filter { it.isDirectory }
    }

    /** 扫描所有加密音乐。 */
    private fun getEncSongs(): List<File> = songDirs()
        .flatMap { dir -> dir.listFiles()?.toList() ?: listOf() }
        .filter { it.isFile && parseName(it) != null }
        .sortedBy { it.name.lowercase() }

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var index = 1
        while (candidate.exists()) {
            candidate = File(dir, if (ext.isEmpty()) "$base($index)" else "$base($index).$ext")
            index++
        }
        return candidate
    }

    private fun moveTo(from: File, to: File): Boolean {
        if (from.renameTo(to)) return true
        return runCatching {
            from.copyTo(to, overwrite = true)
            from.delete()
            true
        }.getOrDefault(false)
    }

    private fun scanFile(file: File) {
        runCatching {
            MediaScannerConnection.scanFile(currentContext, arrayOf(file.absolutePath), null, null)
        }.onFailure { Log.w("媒体扫描失败：${it.message}") }
    }

    private data class ParsedName(val base: String, val plainExt: String)
}
