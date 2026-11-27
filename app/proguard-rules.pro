-repackageclasses "qmhelper"

# 模块入口：java_init.list 里写的是完整类名，混淆掉框架就找不到它了。
-keep class me.kofua.qmhelper.XposedInit {
    <init>();
}

# libxposed 是运行时由框架调用的，保留名字。
-keep class io.github.libxposed.api.** { *; }

# DexKit：native 方法由 C++ 侧按 JNI 名字回调，且 keep 规则类被反射使用。
-keep class org.luckypray.dexkit.** { *; }

# 注入进宿主进程后要按名字反射的数据模型（HookInfo 序列化）。
-keep class me.kofua.qmhelper.data.** { *; }

# dexmaker：运行时生成子类 + 反射读写 $__handler 字段，混淆会破坏约定。
-keep class com.android.dx.** { *; }
-dontwarn com.android.dx.**

# 注意：不能加 -overloadaggressively（dexmaker 的 super$xxx 桥接方法会被
# 重命名破坏），也不能依赖它解决 hook 签名冲突 —— libxposed 链式 hook 不需要。

-keepattributes RuntimeVisible*Annotations

-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

-keepclassmembernames class me.kofua.qmhelper.data.* implements java.io.Serializable {
    <fields>;
}

-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void check*(...);
    public static void throw*(...);
}

-assumenosideeffects class java.util.Objects {
    public static ** requireNonNull(...);
}