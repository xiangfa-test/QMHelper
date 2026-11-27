package me.kofua.qmhelper.utils

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * 给解密后的文件补封面图。
 *
 * QQ 音乐下载的 QMC 文件本身不带封面，解密出来就是「裸音频」，所以这里自己去宿主库里
 * 找这首歌的专辑图 mid，拉一张封面塞进容器：
 *  - FLAC：写一个 PICTURE 元数据块（type=3 前封面）
 *  - MP3 ：写 ID3v2 APIC 帧
 * 其它容器（ogg/m4a…）暂不支持，只记日志。
 */
object CoverArt {

    /** 专辑图 mid 里的分辨率后缀，如 `_3`。 */
    private val MID_SUFFIX = Regex("_\\d+$")

    /** 依次尝试的封面尺寸。 */
    private val COVER_URLS = listOf(
        "https://y.gtimg.cn/music/photo_new/T002R500x500M000%s.jpg",
        "https://y.gtimg.cn/music/photo_new/T002R300x300M000%s.jpg",
        "https://y.gtimg.cn/music/photo_new/T002R150x150M000%s.jpg",
    )

    private data class Tags(val title: String, val artist: String, val album: String)

    /**
     * 尝试给 [target] 写封面。
     *
     * @param sourcePath 加密文件路径，用来在宿主库里反查歌曲
     * @param ext        目标文件真实容器（flac/mp3/…）
     */
    /** 文件里是否已经有封面（FLAC 的 PICTURE 块 / MP3 的 APIC 帧）。 */
    fun hasCover(target: File, ext: String): Boolean = runCatching {
        when (ext) {
            "flac" -> fileHasFlacPicture(target)
            "mp3" -> fileHasMp3Apic(target)
            else -> false
        }
    }.getOrDefault(false)

    private fun fileHasFlacPicture(file: File): Boolean = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(4)
            while (true) {
                val header = raf.read()
                if (header < 0) return@use false
                val type = header and 0x7F
                val last = header and 0x80 != 0
                val length = (raf.read() shl 16) or (raf.read() shl 8) or raf.read()
                if (type == 6) return@use true
                raf.seek(raf.filePointer + length)
                if (last) return@use false
            }
            @Suppress("UNREACHABLE_CODE")
            false
        }
    }.getOrDefault(false)

    private fun fileHasMp3Apic(file: File): Boolean = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(10)
            raf.readFully(header)
            if (String(header, 0, 3) != "ID3") return@use false
            val size = ((header[6].toInt() and 0x7F) shl 21) or
                ((header[7].toInt() and 0x7F) shl 14) or
                ((header[8].toInt() and 0x7F) shl 7) or
                (header[9].toInt() and 0x7F)
            val body = ByteArray(size)
            raf.readFully(body)
            containsFrame(body, "APIC")
        }
    }.getOrDefault(false)

    fun attach(target: File, sourcePath: String, ext: String): Boolean {
        if (!target.isFile || target.length() < 1024) return false
        if (ext !in setOf("flac", "mp3")) {
            Log.d("封面：$ext 容器暂不支持，跳过")
            return false
        }
        val tags = readTags(target) ?: tagsFromFileName(target)
        val cover = resolveCover(sourcePath, tags) ?: run {
            Log.w("封面：没有找到「${tags.artist} - ${tags.title}」的专辑图")
            return false
        }
        return try {
            if (ext == "flac") embedFlac(target, cover.first, cover.second)
            else embedMp3(target, cover.first, cover.second, tags)
        } catch (t: Throwable) {
            Log.w("封面：写入失败 ${t.javaClass.simpleName} ${t.message}")
            false
        }
    }

    // ------------------------------------------------------------------ 找图

    /** 返回 (图片字节, mime)。 */
    private fun resolveCover(sourcePath: String, tags: Tags): Pair<ByteArray, String>? {
        val mid = queryAlbumMid(sourcePath, tags) ?: return null
        val key = MID_SUFFIX.replace(mid, "")
        cachedCover(key)?.let { return it }

        for (pattern in COVER_URLS) {
            val bytes = download(pattern.format(key)) ?: continue
            val mime = mimeOf(bytes) ?: continue
            cacheCover(key, bytes)
            return bytes to mime
        }
        return null
    }

    /** 从宿主数据库 `Song_table` 里找专辑图 mid。 */
    private fun queryAlbumMid(sourcePath: String, tags: Tags): String? {
        val dbFile = runCatching { currentContext.getDatabasePath("QQMusic") }.getOrNull() ?: return null
        if (!dbFile.isFile) return null
        val db = runCatching {
            SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
        }.getOrNull() ?: return null
        try {
            val columns = columnNames(db, "Song_table")
            if (columns.isEmpty()) return null
            val midColumn = listOf("album_p_mid", "album_mid", "albummid", "albumMid")
                .firstOrNull { it in columns } ?: return null
            val fileColumn = listOf("file", "file_path", "path").firstOrNull { it in columns }
            val nameColumn = listOf("name", "song_name").firstOrNull { it in columns }
            val singerColumn = listOf("singername", "singer_name").firstOrNull { it in columns }

            fun midOf(sql: String, args: Array<String>): String? = runCatching {
                db.rawQuery(sql, args).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
                }
            }.getOrNull()

            // 1) 按下载路径精确匹配
            if (fileColumn != null) {
                val mid = midOf(
                    "SELECT $midColumn FROM Song_table WHERE $fileColumn = ? LIMIT 1",
                    arrayOf(sourcePath),
                )
                if (mid != null) return mid
            }
            // 2) 按歌名 + 歌手
            if (nameColumn != null && singerColumn != null && tags.title.isNotBlank()) {
                val mid = midOf(
                    "SELECT $midColumn FROM Song_table WHERE $nameColumn = ? AND $singerColumn = ? LIMIT 1",
                    arrayOf(tags.title, tags.artist),
                )
                if (mid != null) return mid
            }
            // 3) 只按歌名
            if (nameColumn != null && tags.title.isNotBlank()) {
                val mid = midOf(
                    "SELECT $midColumn FROM Song_table WHERE $nameColumn = ? AND $midColumn IS NOT NULL LIMIT 1",
                    arrayOf(tags.title),
                )
                if (mid != null) return mid
            }
            return null
        } catch (t: Throwable) {
            Log.w("封面：读宿主数据库失败 ${t.message}")
            return null
        } finally {
            runCatching { db.close() }
        }
    }

    private fun columnNames(db: SQLiteDatabase, table: String): List<String> = runCatching {
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            buildList {
                val nameIndex = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
        }
    }.getOrDefault(emptyList())

    private fun download(url: String): ByteArray? = try {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000
            readTimeout = 8000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }
        connection.inputStream.use { input ->
            val bytes = input.readBytes()
            connection.disconnect()
            if (bytes.size in 1024..(8 shl 20)) bytes else null
        }
    } catch (t: Throwable) {
        Log.w("封面：下载失败 ${t.message}")
        null
    }

    private fun mimeOf(bytes: ByteArray): String? = when {
        bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
        bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "image/png"
        else -> null
    }

    private fun coverDir(): File? {
        return try {
            val base = currentContext.getExternalFilesDir(null) ?: currentContext.filesDir
            File(File(base, "qmhelper"), "covers").apply { mkdirs() }
        } catch (t: Throwable) {
            null
        }
    }

    private fun cachedCover(mid: String): Pair<ByteArray, String>? {
        val dir = coverDir() ?: return null
        val file = File(dir, "$mid.img")
        if (!file.isFile || file.length() < 1024) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        val mime = mimeOf(bytes) ?: return null
        return bytes to mime
    }

    private fun cacheCover(mid: String, bytes: ByteArray) {
        val dir = coverDir() ?: return
        runCatching { File(dir, "$mid.img").writeBytes(bytes) }
    }

    // ------------------------------------------------------------------ 读标签

    private fun readTags(file: File): Tags? = when {
        isFlac(file) -> readFlacTags(file)
        isMp3(file) -> readMp3Tag(file)
        else -> null
    }

    private fun isFlac(file: File): Boolean = head(file, 4)?.let { String(it) == "fLaC" } ?: false

    private fun isMp3(file: File): Boolean = head(file, 3)?.let { String(it) == "ID3" } ?: false

    private fun head(file: File, count: Int): ByteArray? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val buffer = ByteArray(count)
            raf.readFully(buffer)
            buffer
        }
    }.getOrNull()

    private fun readFlacTags(file: File): Tags? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(4)
            while (true) {
                val header = raf.read()
                if (header < 0) return@use null
                val type = header and 0x7F
                val last = header and 0x80 != 0
                val length = (raf.read() shl 16) or (raf.read() shl 8) or raf.read()
                if (type == 4) {
                    val body = ByteArray(length)
                    raf.readFully(body)
                    return@use parseVorbisComment(body)
                }
                raf.seek(raf.filePointer + length)
                if (last) break
            }
            null
        }
    }.getOrNull()

    private fun parseVorbisComment(body: ByteArray): Tags {
        var pos = 0
        val vendorLen = body.u32le(pos); pos += 4
        pos += vendorLen
        val count = body.u32le(pos); pos += 4
        var title = ""
        var artist = ""
        var album = ""
        repeat(count) {
            if (pos + 4 > body.size) return@repeat
            val len = body.u32le(pos); pos += 4
            if (pos + len > body.size) return@repeat
            val text = String(body, pos, len, Charsets.UTF_8)
            pos += len
            val key = text.substringBefore('=').uppercase()
            val value = text.substringAfter('=', "")
            when (key) {
                "TITLE" -> title = value
                "ARTIST" -> artist = value
                "ALBUM" -> album = value
            }
        }
        return Tags(title, artist, album)
    }

    private fun readMp3Tag(file: File): Tags? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(10)
            raf.readFully(header)
            if (String(header, 0, 3) != "ID3") return@use null
            val size = ((header[6].toInt() and 0x7F) shl 21) or
                ((header[7].toInt() and 0x7F) shl 14) or
                ((header[8].toInt() and 0x7F) shl 7) or
                (header[9].toInt() and 0x7F)
            val body = ByteArray(size)
            raf.readFully(body)
            var title = ""
            var artist = ""
            var album = ""
            var pos = 0
            while (pos + 10 <= body.size) {
                val id = String(body, pos, 4, Charsets.ISO_8859_1)
                if (id.isBlank() || id[0] == '\u0000') break
                val frameSize = ((body[pos + 4].toInt() and 0xFF) shl 24) or
                    ((body[pos + 5].toInt() and 0xFF) shl 16) or
                    ((body[pos + 6].toInt() and 0xFF) shl 8) or
                    (body[pos + 7].toInt() and 0xFF)
                if (frameSize <= 0 || pos + 10 + frameSize > body.size) break
                val payload = body.copyOfRange(pos + 10, pos + 10 + frameSize)
                val text = decodeId3Text(payload)
                when (id) {
                    "TIT2" -> title = text
                    "TPE1" -> artist = text
                    "TALB" -> album = text
                }
                pos += 10 + frameSize
            }
            Tags(title, artist, album)
        }
    }.getOrNull()

    private fun decodeId3Text(payload: ByteArray): String {
        if (payload.isEmpty()) return ""
        val encoding = payload[0].toInt()
        val data = payload.copyOfRange(1, payload.size)
        return when (encoding) {
            0 -> String(data, Charsets.ISO_8859_1)
            1 -> String(data, Charsets.UTF_16)
            2 -> String(data, Charsets.UTF_16BE)
            else -> String(data, Charsets.UTF_8)
        }.trim('\u0000', ' ')
    }

    private fun tagsFromFileName(file: File): Tags {
        val base = file.nameWithoutExtension
        val parts = base.split(" - ", limit = 2)
        return if (parts.size == 2) Tags(parts[1].trim(), parts[0].trim(), "")
        else Tags(base.trim(), "", "")
    }

    // ------------------------------------------------------------------ 写 FLAC

    private fun embedFlac(file: File, cover: ByteArray, mime: String): Boolean {
        val blocks = mutableListOf<Pair<Int, ByteArray>>()
        var audioStart = 0L
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(4)
            while (true) {
                val header = raf.read()
                if (header < 0) return false
                val type = header and 0x7F
                val last = header and 0x80 != 0
                val length = (raf.read() shl 16) or (raf.read() shl 8) or raf.read()
                val body = ByteArray(length)
                raf.readFully(body)
                blocks.add(type to body)
                if (last) {
                    audioStart = raf.filePointer
                    break
                }
            }
        }
        if (blocks.any { it.first == 6 }) return false // 已有封面

        val temp = File(file.parentFile, "${file.name}.cover")
        temp.delete()
        FileOutputStream(temp).use { out ->
            out.write("fLaC".toByteArray())
            blocks.forEach { (type, body) ->
                out.write(type and 0x7F)
                out.write((body.size shr 16) and 0xFF)
                out.write((body.size shr 8) and 0xFF)
                out.write(body.size and 0xFF)
                out.write(body)
            }
            val picture = buildFlacPicture(cover, mime)
            out.write(0x80 or 6) // 最后一个块 + PICTURE
            out.write((picture.size shr 16) and 0xFF)
            out.write((picture.size shr 8) and 0xFF)
            out.write(picture.size and 0xFF)
            out.write(picture)
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(audioStart)
                raf.channel.transferTo(audioStart, raf.length() - audioStart, out.channel)
            }
        }
        return replace(file, temp)
    }

    private fun buildFlacPicture(cover: ByteArray, mime: String): ByteArray {
        val mimeBytes = mime.toByteArray(Charsets.US_ASCII)
        val size = 4 + 4 + mimeBytes.size + 4 + 16 + 4 + cover.size
        val out = ByteArray(size)
        var pos = 0
        fun putInt(value: Int) {
            out[pos++] = (value ushr 24).toByte()
            out[pos++] = (value ushr 16).toByte()
            out[pos++] = (value ushr 8).toByte()
            out[pos++] = value.toByte()
        }
        putInt(3) // front cover
        putInt(mimeBytes.size)
        mimeBytes.copyInto(out, pos); pos += mimeBytes.size
        putInt(0) // description
        putInt(0) // width
        putInt(0) // height
        putInt(0) // depth
        putInt(0) // colors
        putInt(cover.size)
        cover.copyInto(out, pos)
        return out
    }

    // ------------------------------------------------------------------ 写 MP3

    private fun embedMp3(
        file: File,
        cover: ByteArray,
        mime: String,
        tags: Tags,
    ): Boolean {
        var audioStart = 0L
        var existingFrames = ByteArray(0)
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(10)
            raf.readFully(header)
            if (String(header, 0, 3) == "ID3") {
                val size = ((header[6].toInt() and 0x7F) shl 21) or
                    ((header[7].toInt() and 0x7F) shl 14) or
                    ((header[8].toInt() and 0x7F) shl 7) or
                    (header[9].toInt() and 0x7F)
                val body = ByteArray(size)
                raf.readFully(body)
                audioStart = 10L + size
                existingFrames = stripApicFrames(body)
            }
        }

        val apic = buildApicFrame(cover, mime)
        val textFrames = buildList {
            if (!containsFrame(existingFrames, "TIT2") && tags.title.isNotBlank())
                add(buildTextFrame("TIT2", tags.title))
            if (!containsFrame(existingFrames, "TPE1") && tags.artist.isNotBlank())
                add(buildTextFrame("TPE1", tags.artist))
            if (!containsFrame(existingFrames, "TALB") && tags.album.isNotBlank())
                add(buildTextFrame("TALB", tags.album))
        }

        val padding = ByteArray(512)
        val tagSize = existingFrames.size + textFrames.sumOf { it.size } + apic.size + padding.size
        val header = id3Header(tagSize)

        val temp = File(file.parentFile, "${file.name}.cover")
        temp.delete()
        FileOutputStream(temp).use { out ->
            out.write(header)
            out.write(existingFrames)
            textFrames.forEach { out.write(it) }
            out.write(apic)
            out.write(padding)
            if (audioStart > 0) {
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(audioStart)
                    raf.channel.transferTo(audioStart, raf.length() - audioStart, out.channel)
                }
            } else {
                RandomAccessFile(file, "r").use { raf ->
                    raf.channel.transferTo(0, raf.length(), out.channel)
                }
            }
        }
        return replace(file, temp)
    }

    private fun id3Header(size: Int): ByteArray = byteArrayOf(
        'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(),
        3, 0, 0,
        ((size shr 21) and 0x7F).toByte(),
        ((size shr 14) and 0x7F).toByte(),
        ((size shr 7) and 0x7F).toByte(),
        (size and 0x7F).toByte(),
    )

    private fun stripApicFrames(body: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(body.size)
        var pos = 0
        while (pos + 10 <= body.size) {
            val id = String(body, pos, 4, Charsets.ISO_8859_1)
            if (id.isBlank() || id[0] == '\u0000') break
            val size = ((body[pos + 4].toInt() and 0xFF) shl 24) or
                ((body[pos + 5].toInt() and 0xFF) shl 16) or
                ((body[pos + 6].toInt() and 0xFF) shl 8) or
                (body[pos + 7].toInt() and 0xFF)
            if (size <= 0 || pos + 10 + size > body.size) break
            if (id != "APIC") out.write(body, pos, 10 + size)
            pos += 10 + size
        }
        return out.toByteArray()
    }

    private fun containsFrame(frames: ByteArray, id: String): Boolean {
        var pos = 0
        while (pos + 10 <= frames.size) {
            val current = String(frames, pos, 4, Charsets.ISO_8859_1)
            if (current.isBlank() || current[0] == '\u0000') break
            val size = ((frames[pos + 4].toInt() and 0xFF) shl 24) or
                ((frames[pos + 5].toInt() and 0xFF) shl 16) or
                ((frames[pos + 6].toInt() and 0xFF) shl 8) or
                (frames[pos + 7].toInt() and 0xFF)
            if (size <= 0 || pos + 10 + size > frames.size) break
            if (current == id) return true
            pos += 10 + size
        }
        return false
    }

    private fun buildTextFrame(id: String, text: String): ByteArray {
        val payload = ByteArray(1 + text.length * 2)
        payload[0] = 1 // UTF-16 with BOM
        val bytes = text.toByteArray(Charsets.UTF_16)
        bytes.copyInto(payload, 1)
        return frame(id, payload)
    }

    private fun buildApicFrame(cover: ByteArray, mime: String): ByteArray {
        val mimeBytes = mime.toByteArray(Charsets.ISO_8859_1)
        val desc = byteArrayOf(0) // 空描述（ISO-8859-1）
        val payload = ByteArray(1 + mimeBytes.size + 1 + desc.size + cover.size)
        var pos = 0
        payload[pos++] = 0 // ISO-8859-1
        mimeBytes.copyInto(payload, pos); pos += mimeBytes.size
        payload[pos++] = 0
        desc.copyInto(payload, pos); pos += desc.size
        payload[pos++] = 3 // front cover
        cover.copyInto(payload, pos)
        return frame("APIC", payload)
    }

    private fun frame(id: String, payload: ByteArray): ByteArray {
        val out = ByteArray(10 + payload.size)
        id.toByteArray(Charsets.ISO_8859_1).copyInto(out, 0)
        val size = payload.size
        out[4] = (size ushr 24).toByte()
        out[5] = (size ushr 16).toByte()
        out[6] = (size ushr 8).toByte()
        out[7] = size.toByte()
        out[8] = 0
        out[9] = 0
        payload.copyInto(out, 10)
        return out
    }

    // ------------------------------------------------------------------ 工具

    private fun ByteArray.u32le(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or
            ((this[offset + 1].toInt() and 0xFF) shl 8) or
            ((this[offset + 2].toInt() and 0xFF) shl 16) or
            ((this[offset + 3].toInt() and 0xFF) shl 24)

    private fun replace(target: File, temp: File): Boolean {
        if (temp.length() <= 0) {
            temp.delete()
            return false
        }
        target.delete()
        if (temp.renameTo(target)) return true
        return runCatching {
            temp.copyTo(target, overwrite = true)
            temp.delete()
            true
        }.getOrDefault(false)
    }
}
