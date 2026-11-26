@file:Suppress("UNUSED", "UNCHECKED_CAST")

package me.kofua.qmhelper.utils

import me.kofua.qmhelper.classLoader
import me.kofua.qmhelper.data.ClassInfo
import me.kofua.qmhelper.data.Field
import me.kofua.qmhelper.data.Method
import me.kofua.qmhelper.from
import io.github.libxposed.api.XposedInterface

/**
 * 结构化定位（`QMPackage` 里那套 `ClassInfo` / `Method` / `Field` 数据模型）之上的 hook 便捷层。
 *
 * 老版本这里每个函数都是"把 Field/Method 对象换成名字，再调 XposedHelpers 的同名静态方法"。
 * 现代 API 没有 `XposedHelpers` 了，所以这批重载**直接用标准反射**实现，
 * 与 `xposed.kt` 里按名字那批保持同样的语义。
 */


// ---------------------------------------------------------------------------
// 基于 ClassInfo 的 hook
// ---------------------------------------------------------------------------

inline fun <T : ClassInfo> T.hookBeforeMethod(
    method: T.() -> Method,
    vararg exArgs: Any?,
    crossinline hooker: Hooker,
): XposedInterface.HookHandle? {
    val m = method()
    return clazz.from(classLoader)?.hookBeforeMethod(m.name, *m.paramTypes, *exArgs, hooker = hooker)
}

inline fun <T : ClassInfo> T.hookAfterMethod(
    method: T.() -> Method,
    vararg exArgs: Any?,
    crossinline hooker: Hooker,
): XposedInterface.HookHandle? {
    val m = method()
    return clazz.from(classLoader)?.hookAfterMethod(m.name, *m.paramTypes, *exArgs, hooker = hooker)
}

inline fun <T : ClassInfo> T.replaceMethod(
    method: T.() -> Method,
    vararg exArgs: Any?,
    crossinline replacer: Replacer,
): XposedInterface.HookHandle? {
    val m = method()
    return clazz.from(classLoader)?.replaceMethod(m.name, *m.paramTypes, *exArgs, replacer = replacer)
}

// ---------------------------------------------------------------------------
// Field 重载
// ---------------------------------------------------------------------------

fun Any.getObjectField(field: Field): Any? = getObjectField(field.name)

fun Any.getObjectFieldOrNull(field: Field): Any? = runCatchingOrNull { getObjectField(field) }

fun <T> Any.getObjectFieldAs(field: Field): T = getObjectField(field) as T

fun <T> Any.getObjectFieldOrNullAs(field: Field): T? = runCatchingOrNull { getObjectField(field) as T }

fun Any.getIntField(field: Field): Int = getIntField(field.name)

fun Any.getIntFieldOrNull(field: Field): Int? = runCatchingOrNull { getIntField(field) }

fun Any.getLongField(field: Field): Long = getLongField(field.name)

fun Any.getLongFieldOrNull(field: Field): Long? = runCatchingOrNull { getLongField(field) }

fun Any.getBooleanField(field: Field): Boolean = getBooleanField(field.name)

fun Any.getBooleanFieldOrNull(field: Field): Boolean? = runCatchingOrNull { getBooleanField(field) }

fun <T> T.setIntField(field: Field, value: Int): T = setIntField(field.name, value)

fun <T> T.setLongField(field: Field, value: Long): T = setLongField(field.name, value)

fun <T> T.setObjectField(field: Field, value: Any?): T = setObjectField(field.name, value)

fun <T> T.setBooleanField(field: Field, value: Boolean): T = setBooleanField(field.name, value)

// ---------------------------------------------------------------------------
// Method 重载
// ---------------------------------------------------------------------------

fun Any.callMethod(method: Method, vararg args: Any?): Any? = callMethod(method.name, *args)

fun Any.callMethodOrNull(method: Method, vararg args: Any?): Any? = runCatchingOrNull {
    callMethod(method, *args)
}

fun <T> Any.callMethodAs(method: Method, vararg args: Any?): T = callMethod(method, *args) as T

fun <T> Any.callMethodOrNullAs(method: Method, vararg args: Any?): T? =
    runCatchingOrNull { callMethod(method, *args) as T }

fun Class<*>.callStaticMethod(method: Method, vararg args: Any?): Any? = callStaticMethod(method.name, *args)

fun Class<*>.callStaticMethodOrNull(method: Method, vararg args: Any?): Any? =
    runCatchingOrNull { callStaticMethod(method, *args) }

fun <T> Class<*>.callStaticMethodAs(method: Method, vararg args: Any?): T =
    callStaticMethod(method, *args) as T

fun <T> Class<*>.callStaticMethodOrNullAs(method: Method, vararg args: Any?): T? =
    runCatchingOrNull { callStaticMethod(method, *args) as T }

// ---------------------------------------------------------------------------
// 静态字段重载
// ---------------------------------------------------------------------------

fun Class<*>.getStaticObjectField(field: Field): Any? = getStaticObjectField(field.name)

fun Class<*>.getStaticObjectFieldOrNull(field: Field): Any? =
    runCatchingOrNull { getStaticObjectField(field) }

fun <T> Class<*>.getStaticObjectFieldAs(field: Field): T = getStaticObjectField(field) as T

fun <T> Class<*>.getStaticObjectFieldOrNullAs(field: Field): T? =
    runCatchingOrNull { getStaticObjectField(field) as T }

fun Class<*>.setStaticObjectField(field: Field, obj: Any?) = setStaticObjectField(field.name, obj)

fun Class<*>.setStaticObjectFieldIfExist(field: Field, obj: Any?) = setStaticObjectFieldIfExist(field.name, obj)

