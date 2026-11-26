@file:Suppress("UNUSED", "UNCHECKED_CAST")

package me.kofua.qmhelper.utils

import android.util.Log as ALog
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Enumeration

/**
 * 现代（libxposed API 102）上的兼容层。
 *
 * 老模块写的是 `de.robv.android.xposed` 那套：`XC_MethodHook` + `XposedHelpers` +
 * `XposedBridge`。API 101/102 把整套换成了"链式拦截"模型：
 *
 * ```kotlin
 * module.hook(method)
 *     .setExceptionMode(PROTECTIVE)
 *     .intercept { chain ->           // 取代 before / after 两个回调
 *         val args = chain.args       // 只读 List
 *         chain.proceed(newArgs)      // 继续（也就是调用原方法）
 *         // 直接 return 一个值 = 跳过原方法
 *     }
 * ```
 *
 * 为了让上游（zjns/QMHelper）那 1 万多行 hook 代码一行不改地继续跑，这个文件把老 DSL
 * 原样重建了一遍，底下全部走新 API：
 *
 *  - [MethodHookParam] —— 老 `XC_MethodHook.MethodHookParam` 的等价物：
 *    `args` 可改、`result` 可读可写、`thisObject` / `method` 都在。
 *  - `hookBefore` / `hookAfter` / `replace` —— 老语义：before 里给 `result` 赋值就跳过原方法；
 *    after 里给 `result` 赋值就替换返回值。
 *  - `hookAllMethods` / `hookAllConstructors` —— 同名方法/构造器全挂。
 *  - 反射工具（`getObjectField` / `callMethod` / `findClass` …）—— 老 `XposedHelpers` 的等价物，
 *    但**全部走标准反射**，不再依赖框架内部实现，换框架也不用改。
 *
 * 关键差异（踩过）：
 *  1. 新 API 的 `chain.args` 是**只读 List**，要改参数得 `proceed(改好的数组)`。
 *     所以 [MethodHookParam.args] 是一份可变拷贝，拦截时把它传回 `proceed`。
 *  2. 新 API 里 hook 抛异常默认会**穿透**到宿主（DEFAULT）。这里统一设成 PROTECTIVE：
 *     模块自己的 bug 只记日志、放行原调用，绝不让 Q音 崩。
 *  3. `XposedInterface.PRIORITY_*` 是 int 常量（不再是老 API 的 `XC_MethodHook.PRIORITY_*`）。
 */

// ---------------------------------------------------------------------------
// hook 参数
// ---------------------------------------------------------------------------

typealias Replacer = (param: MethodHookParam) -> Any?
typealias Hooker = (param: MethodHookParam) -> Unit

/**
 * 装一条 hook。
 *
 * [before] 决定 hooker 跑在原方法之前还是之后；两条路都是同一条链，所以 before / after
 * 可以共存（上游很多地方就是同一个方法既挂 before 又挂 after）。
 */
@PublishedApi
internal fun installHook(
    member: Member,
    before: Boolean,
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    hooker: (MethodHookParam) -> Unit,
): XposedInterface.HookHandle? {
    if (member !is Executable) {
        Log.e("不是可 hook 的可执行体: $member")
        return null
    }
    val module = HookEnv.module ?: run {
        Log.e("XposedModule 尚未挂载，忽略 hook ${member.declaringClass?.name}#${member.name}")
        return null
    }
    return try {
        module.hook(member)
            .setPriority(priority)
            // PROTECTIVE：hooker 抛异常时只记录并放行原调用，绝不让宿主崩。
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val param = MethodHookParam(chain, before)
                    return if (before) {
                        hooker(param)
                        when {
                            param.isOverridden -> param.result
                            param.hasProceeded() -> param.proceededResult
                            else -> chain.proceed(param.args)
                        }
                    } else {
                        val original = chain.proceed(param.args)
                        param.seedResult(original)
                        hooker(param)
                        if (param.isOverridden) param.result else original
                    }
                }
            })
    } catch (t: Throwable) {
        Log.e("hook ${member.declaringClass?.name}#${member.name} 失败")
        Log.e(t)
        null
    }
}

inline fun MethodHookParam.callHooker(crossinline hooker: Hooker) = try {
    hooker(this)
} catch (t: Throwable) {
    Log.e("Error occurred calling hooker on ${method}")
    Log.e(t)
}

inline fun MethodHookParam.callReplacer(crossinline replacer: Replacer) = try {
    replacer(this)
} catch (t: Throwable) {
    Log.e("Error occurred calling replacer on ${method}")
    Log.e(t)
    null
}

/** 直接在成员上装一条 hook（[callback] 自己决定干什么）。 */
fun Member.hook(callback: (MethodHookParam) -> Unit): XposedInterface.HookHandle? =
    installHook(this, before = true, hooker = callback)

inline fun Member.replace(crossinline replacer: Replacer): XposedInterface.HookHandle? =
    installHook(this, before = true) { param -> param.result = param.callReplacer(replacer) }

inline fun Member.hookAfter(crossinline hooker: Hooker): XposedInterface.HookHandle? =
    installHook(this, before = false) { param -> param.callHooker(hooker) }

inline fun Member.hookBefore(crossinline hooker: Hooker): XposedInterface.HookHandle? =
    installHook(this, before = true) { param -> param.callHooker(hooker) }

// ---------------------------------------------------------------------------
// 按类 / 方法名装 hook
// ---------------------------------------------------------------------------

/**
 * 找方法并按老 API 的参数写法匹配：`hookBeforeMethod("foo", Int::class.java, String::class.java)`，
 * 也接受**类名字符串**（`"int"` / `"java.lang.String"`），因为混淆后的代码里经常只有名字。
 */
fun Class<*>.hookMethod(method: String?, vararg args: Any?): XposedInterface.HookHandle? =
    try {
        findMethodExactOrNull(this, method, *args)?.let { installHook(it, before = true) {} }
    } catch (t: Throwable) {
        Log.e(t)
        null
    }

inline fun Class<*>.hookBeforeMethod(
    method: String?,
    vararg args: Any?,
    crossinline hooker: Hooker,
): XposedInterface.HookHandle? = try {
    findMethodExactOrNull(this, method, *args)?.hookBefore(hooker)
} catch (t: Throwable) {
    Log.e(t)
    null
}

inline fun Class<*>.hookAfterMethod(
    method: String?,
    vararg args: Any?,
    crossinline hooker: Hooker,
): XposedInterface.HookHandle? = try {
    findMethodExactOrNull(this, method, *args)?.hookAfter(hooker)
} catch (t: Throwable) {
    Log.e(t)
    null
}

inline fun Class<*>.hookAfterMethodWithPriority(
    method: String?,
    priority: Int,
    vararg args: Any?,
    crossinline hooker: Hooker,
): XposedInterface.HookHandle? = try {
    findMethodExactOrNull(this, method, *args)
        ?.let { installHook(it, before = false, priority = priority) { p -> p.callHooker(hooker) } }
} catch (t: Throwable) {
    Log.e(t)
    null
}

inline fun Class<*>.replaceMethod(
    method: String?,
    vararg args: Any?,
    crossinline replacer: Replacer,
): XposedInterface.HookHandle? = try {
    findMethodExactOrNull(this, method, *args)
        ?.let { installHook(it, before = true) { p -> p.result = p.callReplacer(replacer) } }
} catch (t: Throwable) {
    Log.e(t)
    null
}

fun Class<*>.hookAllMethods(
    methodName: String?,
    hooker: (MethodHookParam) -> Unit,
): List<XposedInterface.HookHandle?> =
    try {
        methodsNamed(methodName).map { installHook(it, before = true, hooker = hooker) }
    } catch (t: Throwable) {
        Log.e(t)
        emptyList()
    }

inline fun Class<*>.hookBeforeAllMethods(
    methodName: String?,
    crossinline hooker: Hooker,
): List<XposedInterface.HookHandle?> = hookAllMethods(methodName) { p -> p.callHooker(hooker) }

inline fun Class<*>.hookAfterAllMethods(
    methodName: String?,
    crossinline hooker: Hooker,
): List<XposedInterface.HookHandle?> = try {
    methodsNamed(methodName).map { installHook(it, before = false) { p -> p.callHooker(hooker) } }
} catch (t: Throwable) {
    Log.e(t)
    emptyList()
}

inline fun Class<*>.replaceAllMethods(
    methodName: String?,
    crossinline replacer: Replacer,
): List<XposedInterface.HookHandle?> = try {
    methodsNamed(methodName).map { installHook(it, before = true) { p -> p.result = p.callReplacer(replacer) } }
} catch (t: Throwable) {
    Log.e(t)
    emptyList()
}

fun Class<*>.hookConstructor(vararg args: Any?): XposedInterface.HookHandle? = try {
    findConstructorOrNull(this, *args)?.let { installHook(it, before = true) {} }
} catch (t: Throwable) {
    Log.e(t)
    null
}

inline fun Class<*>.hookBeforeConstructor(
    vararg args: Any?,
    crossinline hooker: Hooker,
): XposedInterface.HookHandle? = try {
    findConstructorOrNull(this, *args)?.hookBefore(hooker)
} catch (t: Throwable) {
    Log.e(t)
    null
}

inline fun Class<*>.hookAfterConstructor(
    vararg args: Any?,
    crossinline hooker: Hooker,
): XposedInterface.HookHandle? = try {
    findConstructorOrNull(this, *args)?.hookAfter(hooker)
} catch (t: Throwable) {
    Log.e(t)
    null
}

inline fun Class<*>.replaceConstructor(
    vararg args: Any?,
    crossinline hooker: Hooker,
): XposedInterface.HookHandle? = try {
    findConstructorOrNull(this, *args)
        ?.let { installHook(it, before = true) { p -> p.callHooker(hooker); if (!p.isOverridden) p.result = null } }
} catch (t: Throwable) {
    Log.e(t)
    null
}

fun Class<*>.hookAllConstructors(hooker: (MethodHookParam) -> Unit): List<XposedInterface.HookHandle?> =
    try {
        declaredConstructors.map { installHook(it, before = true, hooker = hooker) }
    } catch (t: Throwable) {
        Log.e(t)
        emptyList()
    }

inline fun Class<*>.hookAfterAllConstructors(crossinline hooker: Hooker): List<XposedInterface.HookHandle?> =
    try {
        declaredConstructors.map { installHook(it, before = false) { p -> p.callHooker(hooker) } }
    } catch (t: Throwable) {
        Log.e(t)
        emptyList()
    }

inline fun Class<*>.hookBeforeAllConstructors(crossinline hooker: Hooker): List<XposedInterface.HookHandle?> =
    try {
        declaredConstructors.map { installHook(it, before = true) { p -> p.callHooker(hooker) } }
    } catch (t: Throwable) {
        Log.e(t)
        emptyList()
    }

inline fun Class<*>.replaceAllConstructors(crossinline hooker: Hooker): List<XposedInterface.HookHandle?> =
    try {
        declaredConstructors.map { installHook(it, before = true) { p -> p.callHooker(hooker); if (!p.isOverridden) p.result = null } }
    } catch (t: Throwable) {
        Log.e(t)
        emptyList()
    }

// ---------------------------------------------------------------------------
// 按类名字符串装 hook
// ---------------------------------------------------------------------------

fun String.hookMethod(classLoader: ClassLoader, method: String?, vararg args: Any?) = try {
    on(classLoader).hookMethod(method, *args)
} catch (t: Throwable) {
    Log.e(t)
    null
}

inline fun String.hookBeforeMethod(
    classLoader: ClassLoader,
    method: String?,
    vararg args: Any?,
    crossinline hooker: Hooker,
): XposedInterface.HookHandle? = try {
    on(classLoader).hookBeforeMethod(method, *args, hooker = hooker)
} catch (t: Throwable) {
    Log.e(t)
    null
}

inline fun String.hookAfterMethod(
    classLoader: ClassLoader,
    method: String?,
    vararg args: Any?,
    crossinline hooker: Hooker,
): XposedInterface.HookHandle? = try {
    on(classLoader).hookAfterMethod(method, *args, hooker = hooker)
} catch (t: Throwable) {
    Log.e(t)
    null
}

inline fun String.replaceMethod(
    classLoader: ClassLoader,
    method: String?,
    vararg args: Any?,
    crossinline replacer: Replacer,
): XposedInterface.HookHandle? = try {
    on(classLoader).replaceMethod(method, *args, replacer = replacer)
} catch (t: Throwable) {
    Log.e(t)
    null
}

// ---------------------------------------------------------------------------
// 查找：类
// ---------------------------------------------------------------------------

infix fun String.on(classLoader: ClassLoader?): Class<*> =
    Class.forName(this, false, classLoader)

infix fun String.from(classLoader: ClassLoader?): Class<*>? =
    runCatchingOrNull { Class.forName(this, false, classLoader) }

fun Class<*>.findMethod(deep: Boolean = false, condition: (Method) -> Boolean): Method? {
    var c: Class<*>? = this
    while (c != null) {
        (c.declaredMethods.find(condition) ?: if (deep) c.interfaces
            .flatMap { it.declaredMethods.toList() }
            .find(condition) else null)?.also {
            it.isAccessible = true
            return it
        } ?: run { c = c?.superclass }
    }
    return null
}

@PublishedApi
internal fun Class<*>.methodsNamed(name: String?): List<Method> {
    if (name == null) return emptyList()
    val found = LinkedHashMap<String, Method>()
    var current: Class<*>? = this
    while (current != null && current != Any::class.java) {
        for (method in current.declaredMethods) {
            if (method.name != name) continue
            val key = method.name + method.parameterTypes.joinToString(",", "(", ")") { it.name }
            if (found.containsKey(key)) continue
            method.isAccessible = true
            found[key] = method
        }
        current = current.superclass
    }
    return found.values.toList()
}

/**
 * 参数匹配：既支持 `Class<*>`，也支持类名字符串（`"int"`、`"[B"`、`"java.lang.String"`），
 * 这正是老 `XposedHelpers.findAndHookMethod` 的行为。
 */
private fun matchesParameter(paramType: Class<*>, spec: Any?): Boolean = when (spec) {
    is Class<*> -> spec == paramType
    is String -> paramType.name == spec || paramType.canonicalName == spec ||
            (spec == "int" && paramType == Int::class.javaPrimitiveType) ||
            (spec == "long" && paramType == Long::class.javaPrimitiveType) ||
            (spec == "boolean" && paramType == Boolean::class.javaPrimitiveType) ||
            (spec == "void" && paramType == Void.TYPE)
    else -> true
}

fun findMethodExactOrNull(clazz: Class<*>, name: String?, vararg args: Any?): Method? {
    if (name == null) return null
    val specs = args.toList()
    var current: Class<*>? = clazz
    while (current != null) {
        current.declaredMethods.firstOrNull { m ->
            m.name == name && m.parameterCount == specs.size &&
                    m.parameterTypes.indices.all { matchesParameter(m.parameterTypes[it], specs[it]) }
        }?.let { return it.also { m -> m.isAccessible = true } }
        current = current.superclass
    }
    return null
}

@PublishedApi
internal fun findConstructorOrNull(clazz: Class<*>, vararg args: Any?): Constructor<*>? {
    val specs = args.toList()
    return clazz.declaredConstructors.firstOrNull { c ->
        c.parameterCount == specs.size &&
                c.parameterTypes.indices.all { matchesParameter(c.parameterTypes[it], specs[it]) }
    }?.also { it.isAccessible = true }
}

fun Class<*>.findField(field: String?): Field = findFieldInHierarchy(this, field)

fun Class<*>.findFieldOrNull(field: String?): Field? = runCatchingOrNull { findFieldInHierarchy(this, field) }

private fun findFieldInHierarchy(clazz: Class<*>, name: String?): Field {
    requireNotNull(name) { "字段名不能为空" }
    var current: Class<*>? = clazz
    while (current != null) {
        current.declaredFields.firstOrNull { it.name == name }?.let {
            it.isAccessible = true
            return it
        }
        current = current.superclass
    }
    throw NoSuchFieldError("${clazz.name}#$name")
}

fun Class<*>.findFirstFieldByExactType(type: Class<*>): Field {
    var current: Class<*>? = this
    while (current != null) {
        current.declaredFields.firstOrNull { it.type == type }?.let {
            it.isAccessible = true
            return it
        }
        current = current.superclass
    }
    throw NoSuchFieldError("$name: 没有类型为 ${type.name} 的字段")
}

fun Class<*>.findFirstFieldByExactTypeOrNull(type: Class<*>?): Field? =
    runCatchingOrNull { findFirstFieldByExactType(type!!) }

inline fun <reified T> Class<*>.findFieldByExactType(): Field? =
    findFirstFieldByExactTypeOrNull(T::class.java)

fun Class<*>.findFieldByExactType(type: Class<*>): Field? = findFirstFieldByExactTypeOrNull(type)

/** 老 API 的 `Class.newInstance(clazz, *args)`：按参数类型自动挑构造器。 */
fun Class<*>.new(vararg args: Any?): Any = newInstance(arrayOf(), *args)

/** 显式指定构造器参数类型创建实例。 */
fun Class<*>.new(parameterTypes: Array<Class<*>>, vararg args: Any?): Any =
    newInstance(parameterTypes, *args)

fun Class<*>.newInstance(vararg args: Any?): Any = newInstance(arrayOf(), *args)

fun Class<*>.newInstance(parameterTypes: Array<Class<*>>, vararg args: Any?): Any {
    val constructor = if (parameterTypes.isNotEmpty())
        getDeclaredConstructor(*parameterTypes)
    else declaredConstructors.firstOrNull {
        it.parameterCount == args.size &&
                it.parameterTypes.indices.all { i -> isCompatible(it.parameterTypes[i], args[i]) }
    } ?: declaredConstructors.firstOrNull { it.parameterCount == args.size }
    ?: throw NoSuchMethodError("$name: 没有 ${args.size} 个参数的构造器")
    constructor.isAccessible = true
    return try {
        constructor.newInstance(*args) as Any
    } catch (e: InvocationTargetException) {
        throw e.targetException ?: e
    }
}

private fun isCompatible(type: Class<*>, value: Any?): Boolean = when {
    value == null -> !type.isPrimitive
    type.isPrimitive -> when (type) {
        java.lang.Boolean.TYPE -> value is Boolean
        java.lang.Byte.TYPE -> value is Byte
        java.lang.Character.TYPE -> value is Char
        java.lang.Short.TYPE -> value is Short
        Integer.TYPE -> value is Int
        java.lang.Long.TYPE -> value is Long
        java.lang.Float.TYPE -> value is Float
        java.lang.Double.TYPE -> value is Double
        else -> false
    }
    else -> type.isInstance(value)
}

// ---------------------------------------------------------------------------
// 反射工具（老 XposedHelpers 的等价物）
// ---------------------------------------------------------------------------

fun Any.getObjectField(field: String?): Any? = findFieldInHierarchy(javaClass, field).get(this)

fun Any.getObjectFieldOrNull(field: String?): Any? = runCatchingOrNull { getObjectField(field) }

fun <T> Any.getObjectFieldAs(field: String?): T = getObjectField(field) as T

fun <T> Any.getObjectFieldOrNullAs(field: String?): T? = runCatchingOrNull { getObjectField(field) as T }

fun Any.getIntField(field: String?): Int = getObjectField(field) as Int

fun Any.getIntFieldOrNull(field: String?): Int? = runCatchingOrNull { getIntField(field) }

fun Any.getLongField(field: String?): Long = getObjectField(field) as Long

fun Any.getLongFieldOrNull(field: String?): Long? = runCatchingOrNull { getLongField(field) }

fun Any.getBooleanField(field: String?): Boolean = getObjectField(field) as Boolean

fun Any.getBooleanFieldOrNull(field: String?): Boolean? = runCatchingOrNull { getBooleanField(field) }

fun <T> T.setIntField(field: String?, value: Int): T = apply {
    findFieldInHierarchy(this!!.javaClass, field).set(this, value)
}

fun <T> T.setLongField(field: String?, value: Long): T = apply {
    findFieldInHierarchy(this!!.javaClass, field).set(this, value)
}

fun <T> T.setObjectField(field: String?, value: Any?): T = apply {
    findFieldInHierarchy(this!!.javaClass, field).set(this, value)
}

fun <T> T.setBooleanField(field: String?, value: Boolean): T = apply {
    findFieldInHierarchy(this!!.javaClass, field).set(this, value)
}

private fun findCompatibleMethod(clazz: Class<*>, name: String, args: Array<out Any?>, static: Boolean): Method? {
    var current: Class<*>? = clazz
    while (current != null) {
        current.declaredMethods.firstOrNull { m ->
            m.name == name &&
                    Modifier.isStatic(m.modifiers) == static &&
                    m.parameterCount == args.size &&
                    m.parameterTypes.indices.all { isCompatible(m.parameterTypes[it], args[it]) }
        }?.let { return it.also { m -> m.isAccessible = true } }
        current = current.superclass
    }
    return null
}

private fun invokeReflectively(receiver: Any?, method: Method, args: Array<out Any?>): Any? = try {
    method.invoke(receiver, *args)
} catch (e: InvocationTargetException) {
    throw e.targetException ?: e
}

fun Any.callMethod(methodName: String?, vararg args: Any?): Any? {
    requireNotNull(methodName) { "方法名不能为空" }
    val method = findCompatibleMethod(javaClass, methodName, args, static = false)
        ?: throw NoSuchMethodError("${javaClass.name}#$methodName")
    return invokeReflectively(this, method, args)
}

fun Any.callMethodOrNull(methodName: String?, vararg args: Any?): Any? =
    runCatchingOrNull { callMethod(methodName, *args) }

fun <T> Any.callMethodAs(methodName: String?, vararg args: Any?): T =
    callMethod(methodName, *args) as T

fun <T> Any.callMethodOrNullAs(methodName: String?, vararg args: Any?): T? =
    runCatchingOrNull { callMethod(methodName, *args) as T }

fun Any.callMethod(methodName: String?, parameterTypes: Array<Class<*>>, vararg args: Any?): Any? {
    requireNotNull(methodName) { "方法名不能为空" }
    val method = javaClass.getDeclaredMethod(methodName, *parameterTypes).apply { isAccessible = true }
    return invokeReflectively(this, method, args)
}

fun Any.callMethodOrNull(methodName: String?, parameterTypes: Array<Class<*>>, vararg args: Any?): Any? =
    runCatchingOrNull { callMethod(methodName, parameterTypes, *args) }

fun Class<*>.callStaticMethod(methodName: String?, vararg args: Any?): Any? {
    requireNotNull(methodName) { "方法名不能为空" }
    val method = findCompatibleMethod(this, methodName, args, static = true)
        ?: throw NoSuchMethodError("$name#$methodName")
    return invokeReflectively(null, method, args)
}

fun Class<*>.callStaticMethodOrNull(methodName: String?, vararg args: Any?): Any? =
    runCatchingOrNull { callStaticMethod(methodName, *args) }

fun <T> Class<*>.callStaticMethodAs(methodName: String?, vararg args: Any?): T =
    callStaticMethod(methodName, *args) as T

fun <T> Class<*>.callStaticMethodOrNullAs(methodName: String?, vararg args: Any?): T? =
    runCatchingOrNull { callStaticMethod(methodName, *args) as T }

fun Class<*>.callStaticMethod(methodName: String?, parameterTypes: Array<Class<*>>, vararg args: Any?): Any? {
    requireNotNull(methodName) { "方法名不能为空" }
    val method = getDeclaredMethod(methodName, *parameterTypes).apply { isAccessible = true }
    return invokeReflectively(null, method, args)
}

fun Class<*>.callStaticMethodOrNull(
    methodName: String?,
    parameterTypes: Array<Class<*>>,
    vararg args: Any?,
): Any? = runCatchingOrNull { callStaticMethod(methodName, parameterTypes, *args) }

fun Class<*>.getStaticObjectField(field: String?): Any? = findFieldInHierarchy(this, field).get(null)

fun Class<*>.getStaticObjectFieldOrNull(field: String?): Any? =
    runCatchingOrNull { getStaticObjectField(field) }

fun <T> Class<*>.getStaticObjectFieldAs(field: String?): T = getStaticObjectField(field) as T

fun <T> Class<*>.getStaticObjectFieldOrNullAs(field: String?): T? =
    runCatchingOrNull { getStaticObjectField(field) as T }

fun Class<*>.setStaticObjectField(field: String?, obj: Any?) = apply {
    findFieldInHierarchy(this, field).set(null, obj)
}

fun Class<*>.setStaticObjectFieldIfExist(field: String?, obj: Any?) = apply {
    runCatching { findFieldInHierarchy(this, field).set(null, obj) }
}

fun Any.getFirstFieldByExactType(type: Class<*>): Any? =
    javaClass.findFirstFieldByExactType(type).get(this)

fun <T> Any.getFirstFieldByExactTypeAs(type: Class<*>): T? =
    getFirstFieldByExactType(type) as? T

inline fun <reified T : Any> Any.getFirstFieldByExactType(): T? =
    getFirstFieldByExactType(T::class.java) as? T

fun Any.getFirstFieldByExactTypeOrNull(type: Class<*>?): Any? =
    runCatchingOrNull { getFirstFieldByExactType(type!!) }

fun <T> Any.getFirstFieldByExactTypeOrNullAs(type: Class<*>?): T? =
    getFirstFieldByExactTypeOrNull(type) as? T

inline fun <reified T> Any.getFirstFieldByExactTypeOrNull(): T? =
    getFirstFieldByExactTypeOrNull(T::class.java) as? T

// ---------------------------------------------------------------------------
// classloader 相关
// ---------------------------------------------------------------------------

inline fun ClassLoader.findDexClassLoader(
    crossinline delegator: (dalvik.system.BaseDexClassLoader) -> dalvik.system.BaseDexClassLoader =
        { x -> x },
): dalvik.system.BaseDexClassLoader? {
    var classLoader = this
    while (classLoader !is dalvik.system.BaseDexClassLoader) {
        if (classLoader.parent != null) classLoader = classLoader.parent!!
        else return null
    }
    return delegator(classLoader as dalvik.system.BaseDexClassLoader)
}

inline fun ClassLoader.allClassesList(
    crossinline delegator: (dalvik.system.BaseDexClassLoader) -> dalvik.system.BaseDexClassLoader =
        { x -> x },
): List<String> = runCatchingOrNull {
    findDexClassLoader(delegator)?.getObjectField("pathList")
        ?.getObjectFieldAs<Array<Any>>("dexElements")
        ?.flatMap { element ->
            element.getObjectField("dexFile")
                ?.callMethodAs<Enumeration<String>>("entries")
                ?.toList().orEmpty()
        }.orEmpty()
}.orEmpty()

// ---------------------------------------------------------------------------
// 修饰符便捷判断
// ---------------------------------------------------------------------------

val Member.isStatic: Boolean inline get() = Modifier.isStatic(modifiers)
val Member.isFinal: Boolean inline get() = Modifier.isFinal(modifiers)
val Member.isPublic: Boolean inline get() = Modifier.isPublic(modifiers)
val Member.isNotStatic: Boolean inline get() = !isStatic
val Member.isAbstract: Boolean inline get() = Modifier.isAbstract(modifiers)
val Member.isPrivate: Boolean inline get() = Modifier.isPrivate(modifiers)
val Class<*>.isAbstract: Boolean inline get() = !isPrimitive && Modifier.isAbstract(modifiers)

/** 给调试用：一条日志出口，避免到处 import android.util.Log。 */
internal fun debugLog(message: String) = ALog.d(HookEnv.TAG, message)
