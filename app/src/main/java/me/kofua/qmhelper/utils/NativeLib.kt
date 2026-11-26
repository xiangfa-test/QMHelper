package me.kofua.qmhelper.utils

import android.os.Build
import me.kofua.qmhelper.XposedInit
import java.io.File
import java.util.zip.ZipFile

/**
 * 加载 DexKit 的 native 库 `libdexkit.so`。
 *
 * DexKit 官方示例是 `System.loadLibrary("dexkit")`，在**传统 Xposed** 下能用；
 * 但本模块是现代 API（libxposed）注入到 Q音 进程的，`System.loadLibrary` 只会搜
 * **宿主自己的** nativeLibraryDir 和系统路径，看不到我们模块 APK 里的 so —— 实测报
 * `UnsatisfiedLinkError: nativeInitDexKitByClassLoader`。
 *
 * 所以这里按绝对路径加载，四级兜底，每一级都写日志，出问题时能直接看出走的哪条路。
 */
object NativeLib {

    private const val LIB_NAME = "dexkit"

    @Volatile
    private var loaded: Boolean? = null

    @Volatile
    private var loadedFrom: String? = null

    fun ensureLoaded(): Boolean {
        loaded?.let { return it }
        synchronized(this) {
            loaded?.let { return it }
            val path = tryLoad()
            loaded = path != null
            loadedFrom = path
            if (path != null) Log.i("DexKit native 库已加载：$path")
            else Log.e("DexKit native 库加载失败：四条路径全试过了")
            return path != null
        }
    }

    fun describe(): String = loadedFrom ?: "未加载"

    private fun libFileName() = "lib$LIB_NAME.so"

    private fun loadFile(file: File): String? {
        if (!file.isFile) return null
        return runCatching {
            System.load(file.absolutePath)
            file.absolutePath
        }.getOrNull()
    }

    private fun tryLoad(): String? {
        // 1) 交给系统搜索（极少数环境下宿主搜索路径里真有它）
        runCatching { System.loadLibrary(LIB_NAME) }.onSuccess { return "System.loadLibrary(\"$LIB_NAME\")" }

        // 2) 模块自己的 native 库目录（extractNativeLibs=true 时在这）
        HookEnv.moduleApplicationInfo?.nativeLibraryDir?.let { dir ->
            loadFile(File(dir, libFileName()))?.let { return it }
        }

        // 3) APK 同级的 lib/<abi>/（某些安装器会把 so 解到 base.apk 旁边）
        HookEnv.moduleApkPath.takeIf { it.isNotEmpty() }?.let { apk ->
            File(apk).parentFile?.let { base ->
                File(base, "lib").listFiles()?.forEach { abiDir ->
                    loadFile(File(abiDir, libFileName()))?.let { return it }
                }
            }
        }

        // 4) 从模块 APK 里解到 code_cache 再 load（extractNativeLibs=false 的情况）
        return extractFromApkAndLoad()
    }

    private fun extractFromApkAndLoad(): String? {
        val apkPath = HookEnv.moduleApkPath.takeIf { it.isNotEmpty() } ?: run {
            Log.d("DexKit 库：拿不到模块 APK 路径")
            return null
        }
        val cacheDir = HookEnv.appContext?.codeCacheDir ?: run {
            Log.d("DexKit 库：拿不到 codeCacheDir")
            return null
        }

        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: return null
        val entryName = "lib/$abi/${libFileName()}"

        return runCatching {
            val target = File(cacheDir, libFileName())
            if (!target.isFile || target.length() == 0L) {
                ZipFile(apkPath).use { zip ->
                    val entry = zip.getEntry(entryName) ?: return@use
                    zip.getInputStream(entry).use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
            if (!target.isFile) return null
            target.setExecutable(true, false)
            System.load(target.absolutePath)
            "${target.absolutePath}（从 APK 的 $entryName 解出）"
        }.onFailure { Log.d("DexKit 库：从 APK 解压加载失败 ${it.message}") }.getOrNull()
    }
}