package com.ziymmx.wx.hook.common

import java.lang.reflect.Constructor
import java.lang.reflect.Member
import java.lang.reflect.Method

/**
 * 抽象 Hook 桥接层，屏蔽不同 Hook 框架（libxposed / Pine）的差异。
 *
 * 所有 hook 回调统一使用「拦截式」语义：
 *  - 回调返回值即方法的最终返回值；
 *  - 回调内调用 [HookChain.proceed] 才会执行原方法；
 *  - 不调用 [HookChain.proceed] 则直接短路返回。
 */
interface HookBridge {

    /** Hook 一个成员方法（含静态方法）。 */
    fun hookMethod(method: Method, hookId: String, callback: (HookChain) -> Any?): Any?

    /** Hook 一个构造函数。 */
    fun hookConstructor(constructor: Constructor<*>, hookId: String, callback: (HookChain) -> Any?): Any?

    /** 输出日志（release 构建仅保留 ERROR 级别）。 */
    fun log(level: Int, tag: String, msg: String)
}

/**
 * 一次方法调用的拦截上下文。
 */
interface HookChain {
    val executable: Member

    /** 当前被调用对象，静态方法为 null。 */
    val thisObject: Any?

    /** 当前调用参数。 */
    val args: List<Any?>

    /** 以原始参数执行原方法。 */
    fun proceed(): Any?

    /** 以给定参数执行原方法。 */
    fun proceed(args: Array<out Any?>): Any?
}
