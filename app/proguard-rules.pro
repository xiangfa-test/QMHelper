-repackageclasses "qmhelper"

# 模块入口：java_init.list 里写的是完整类名，混淆掉框架就找不到它了。
-keep class me.kofua.qmhelper.XposedInit {
    <init>();
}

# DexKit / libxposed 是运行时由框架或 native 反射调用的，保留名字。
-keep class io.github.libxposed.api.** { *; }
-keep class org.luckypray.dexkit.** { *; }
-keepclassmembers class org.luckypray.dexkit.DexKitBridge {
    native <methods>;
}

# 注入进宿主进程后要按名字反射的类（宿主类名/字段名 + 本模块数据类）。 
-keep class me.kofua.qmhelper.data.** { *; }

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

-allowaccessmodification
-overloadaggressively