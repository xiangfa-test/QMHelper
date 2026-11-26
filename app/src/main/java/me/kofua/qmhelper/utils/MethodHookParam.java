package me.kofua.qmhelper.utils;

import io.github.libxposed.api.XposedInterface;

import java.lang.reflect.Executable;

/**
 * Hook 参数载体（老 API 的 {@code XC_MethodHook.MethodHookParam} 的等价物）。
 *
 * <p>为什么用 <b>Java</b> 写而不是 Kotlin：老模块里大量代码是这样的——
 *
 * <pre>{@code
 * param.result.getObjectField("alert")?.setIntField("revertTheme", 0)
 * param.result as MutableList<*>   // 直接点号调用，没有 ?. / !!
 * }</pre>
 *
 * <p>Kotlin 里 {@code Any?} 不允许这种写法（必须 {@code ?.} 或 {@code !!}），而 Java 的
 * {@code Object} 在 Kotlin 眼里是 <b>平台类型</b> {@code Object!}，点号调用合法、编译期也不插入
 * null 检查。用 Java 写这个类，就能让上游 1 万多行 hook 代码在 API 102 下原样编过，
 * 一行都不用改（改了反而容易漏掉语义）。
 */
public final class MethodHookParam {

    private final XposedInterface.Chain chain;
    private final boolean beforeHook;
    private final Object[] args;

    private Object result;
    private boolean overridden;
    private boolean proceeded;
    private Object proceededResult;

    public MethodHookParam(XposedInterface.Chain chain, boolean beforeHook) {
        this.chain = chain;
        this.beforeHook = beforeHook;
        this.args = chain.getArgs().toArray();
    }

    /** 被 hook 的方法或构造器。 */
    public Executable getMethod() {
        return chain.getExecutable();
    }

    /** {@code this} 指针；静态方法上是 null。 */
    public Object getThisObject() {
        return chain.getThisObject();
    }

    public boolean isBeforeHook() {
        return beforeHook;
    }

    /** 参数数组（可变拷贝，改动会随拦截结果生效）。 */
    public Object[] getArgs() {
        return args;
    }

    /**
     * 返回值。
     *
     * <p>前置 hook 里赋值 = 跳过原方法直接返回该值（null 也算跳过）；
     * 后置 hook 里初值是原方法返回值，赋值即替换。
     */
    public Object getResult() {
        return result;
    }

    public void setResult(Object value) {
        this.result = value;
        this.overridden = true;
    }

    public boolean isOverridden() {
        return overridden;
    }

    /** 等价于让原方法返回 {@code value}。 */
    public void returnConstant(Object value) {
        setResult(value);
    }

    /** 显式调用原方法（前置 hook 里用；调用过之后框架不会再次调用它）。 */
    public Object invokeOriginalMethod() throws Throwable {
        Object value = chain.proceed(args);
        this.proceeded = true;
        this.proceededResult = value;
        return value;
    }

    /** 框架内部用：后置 hook 的返回值初值。 */
    public void seedResult(Object value) {
        this.result = value;
    }

    public boolean hasProceeded() {
        return proceeded;
    }

    public Object getProceededResult() {
        return proceededResult;
    }

    @Override
    public String toString() {
        return "MethodHookParam(" + getMethod().getName() + ", before=" + beforeHook
                + ", overridden=" + overridden + ")";
    }
}