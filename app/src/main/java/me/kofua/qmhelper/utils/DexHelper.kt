package me.kofua.qmhelper.utils

import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.query.matchers.MethodsMatcher
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Member
import java.lang.reflect.Method

/**
 * Q音 内部被混淆的类/方法/字段的**运行时索引**。
 *
 * 上游 1.1.4 的这层是**自研 JNI**（`app/src/main/jni/qmhelper.cc` + DexBuilder，按 dex
 * cookie 直接读内存里的 dex，再用一堆 `long` 索引换来换去）。那套东西：
 *
 *  - 需要 NDK 25 + CMake + 一个 DexBuilder 的 submodule 才能编译（发布源码时很劝退）；
 *  - 索引语义是"返回 long，再 decode 成 Member"，调用点写起来很绕；
 *  - 一旦 Q音 把题面（字符串/短签名）改掉，调试基本只能靠猜。
 *
 * 现在换成 **DexKit 2.3.0**：同样是 C++ 索引（`libdexkit.so`），但：
 *
 *  - 查询写成声明式 matcher（`usingStrings` / `protoShorty` / `invokeMethods` …），
 *    和"这个类里出现过哪些字符串"这种定位思路天然对齐；
 *  - 直接返回可解析成 `Class`/`Method` 的结果对象，不再需要 long ↔ Member 转换；
 *  - 索引按 classloader 缓存，Q音 那种 176 万方法的大包预热一次之后每次查询都是毫秒级。
 *
 * ## 与旧接口的对应关系
 *
 * 为了不动 `QMPackage.kt` 里 60 多处定位代码，这里保留了旧方法名与参数名，
 * 但**参数/返回值从 `long` 索引换成了真正的 `Class` / `Member`**：
 *
 * | 旧 | 新 |
 * | --- | --- |
 * | `findMethodUsingString(str).firstOrNull()` → Long | → [Method] |
 * | `decodeMethodIndex(index)` | 恒等（已经是 Member） |
 * | `encodeClassIndex(clazz)` / `encodeMethodIndex(m)` | 恒等（已经是 Class/Member） |
 *
 * 也就是说 `decode*`/`encode*` 现在只是"语义占位"，调用点可以原样保留。
 */
class DexHelper(private val classLoader: ClassLoader) : AutoCloseable {

    private val bridge: DexKitBridge? = run {
        if (!NativeLib.ensureLoaded()) return@run null
        try {
            DexKitBridge.create(classLoader, true).also {
                Log.d("DexKit 索引就绪（classloader=${classLoader.javaClass.name}）")
            }
        } catch (t: Throwable) {
            Log.e("DexKit 初始化失败")
            Log.e(t)
            null
        }
    }

    val isReady: Boolean get() = bridge?.isValid == true

    // ------------------------------------------------------------------ 查找

    /**
     * 按"方法体内引用过某个字符串"定位方法。
     *
     * 参数全部是可选约束（AND 关系）：
     *  - [matchPrefix] true 用 StartsWith 匹配，false 用 Contains —— 上游传 false 时是
     *    精确等值；这里放成 Contains 是因为 Q音 新版把很多日志文案追加了后缀，
     *    只认等值会大面积失配（21.0.5.3 实测）。
     */
    fun findMethodUsingString(
        str: String,
        matchPrefix: Boolean = false,
        returnType: Class<*>? = null,
        parameterCount: Int = -1,
        parameterShorty: String? = null,
        declaringClass: Class<*>? = null,
        parameterTypes: Array<Class<*>>? = null,
        findFirst: Boolean = true,
    ): List<Method> = query(findFirst) { matcher ->
        matcher.addUsingString(str, if (matchPrefix) StringMatchType.StartsWith else StringMatchType.Contains)
        if (returnType != null) matcher.returnType(returnType)
        if (parameterCount >= 0) matcher.paramCount(parameterCount)
        if (parameterShorty != null) matcher.protoShorty(parameterShorty)
        if (declaringClass != null) matcher.declaredClass(declaringClass)
        if (parameterTypes != null && parameterTypes.isNotEmpty()) matcher.paramTypes(*parameterTypes)
    }

    /** 按"方法调用了 [target]"定位调用方（等价于旧版的 findMethodInvoking）。 */
    fun findMethodInvoking(
        target: Member,
        returnType: Class<*>? = null,
        parameterCount: Int = -1,
        parameterShorty: String? = null,
        declaringClass: Class<*>? = null,
        parameterTypes: Array<Class<*>>? = null,
        findFirst: Boolean = true,
    ): List<Method> = query(findFirst) { matcher ->
        matcher.invokeMethods(MethodsMatcher().add(matcherOf(target)))
        if (returnType != null) matcher.returnType(returnType)
        if (parameterCount >= 0) matcher.paramCount(parameterCount)
        if (parameterShorty != null) matcher.protoShorty(parameterShorty)
        if (declaringClass != null) matcher.declaredClass(declaringClass)
        if (parameterTypes != null && parameterTypes.isNotEmpty()) matcher.paramTypes(*parameterTypes)
    }

    /** 按"方法被 [target] 调用"定位被调方（等价于旧版的 findMethodInvoked）。 */
    fun findMethodInvoked(
        target: Member,
        returnType: Class<*>? = null,
        parameterCount: Int = -1,
        parameterShorty: String? = null,
        declaringClass: Class<*>? = null,
        parameterTypes: Array<Class<*>>? = null,
        findFirst: Boolean = true,
    ): List<Method> = query(findFirst) { matcher ->
        matcher.callerMethods(MethodsMatcher().add(matcherOf(target)))
        if (returnType != null) matcher.returnType(returnType)
        if (parameterCount >= 0) matcher.paramCount(parameterCount)
        if (parameterShorty != null) matcher.protoShorty(parameterShorty)
        if (declaringClass != null) matcher.declaredClass(declaringClass)
        if (parameterTypes != null && parameterTypes.isNotEmpty()) matcher.paramTypes(*parameterTypes)
    }

    // ------------------------------------------------------------------ 兼容层

    /** 旧接口：把 long 索引解成 Member。现在结果本身就是 Member，恒等返回。 */
    fun decodeMethodIndex(member: Member): Member? = member

    /** 旧接口：把 Member 编成 long 索引。现在恒等返回 Member。 */
    fun encodeMethodIndex(member: Member): Member = member

    /** 旧接口：类索引 ↔ Class。现在恒等。 */
    fun encodeClassIndex(clazz: Class<*>): Class<*> = clazz

    fun decodeClassIndex(clazz: Class<*>): Class<*>? = clazz

    fun decodeFieldIndex(field: Field): Field? = field

    fun encodeFieldIndex(field: Field): Field = field

    /** 预热全部索引（Q音 dex 多，先用一次比在 UI 线程上抖强）。 */
    fun createFullCache() = runCatching { bridge?.initFullCache() }.onFailure {
        Log.w("initFullCache 失败（不影响使用）: ${it.message}")
    }

    override fun close() {
        runCatching { bridge?.close() }
    }

    // ------------------------------------------------------------------ 内部

    /** 把 Member 包装成 DexKit 的 matcher（重载里 Method/Constructor/String 会互相歧义，所以显式分派）。 */
    private fun matcherOf(target: Member): MethodMatcher = when (target) {
        is Method -> MethodMatcher(target)
        is Constructor<*> -> MethodMatcher(target)
        else -> throw IllegalArgumentException("不支持的成员: $target")
    }

    private inline fun query(findFirst: Boolean, matcher: (MethodMatcher) -> Unit): List<Method> {
        val dexKit = bridge ?: return emptyList()
        return try {
            val query = FindMethod().apply {
                this.findFirst = findFirst
                this.matcher(MethodMatcher().apply(matcher))
            }
            dexKit.findMethod(query)
                .mapNotNull { it.toMethodOrNull() }
        } catch (t: Throwable) {
            Log.e("DexKit 查询失败")
            Log.e(t)
            emptyList()
        }
    }

    /**
     * [MethodData] → 真实 [Method]。
     *
     * 先按 `getMethodInstance`（DexKit 自己按 descriptor + classloader 解析，最准），
     * 失败再退回"类 + 名字 + 参数名"手工匹配 —— Q音 里有些方法挂在父类上，
     * descriptor 里的 declaringClass 是子类，DexKit 那条路会抛 NoSuchMethodException。
     */
    private fun MethodData.toMethodOrNull(): Method? {
        runCatching { getMethodInstance(classLoader) }.getOrNull()?.let { return it.also { m -> m.isAccessible = true } }
        val declared = runCatching { declaredClass?.getInstance(classLoader) }.getOrNull() ?: return null
        val wanted = paramTypeNames
        var current: Class<*>? = declared
        while (current != null) {
            current.declaredMethods.firstOrNull { m ->
                m.name == methodName && m.parameterTypes.map { it.simpleName } == wanted.map { it.substringAfterLast('.') }
            }?.let { return it.also { m -> m.isAccessible = true } }
            current = current.superclass
        }
        return current?.let { null }
    }

    @Suppress("unused")
    private fun unused(ctor: Constructor<*>, t: InvocationTargetException) = Unit
}