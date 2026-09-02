package com.ziymmx.wx.hook.xposed

import com.ziymmx.wx.hook.common.HookBridge
import com.ziymmx.wx.hook.common.HookChain
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Constructor
import java.lang.reflect.Member
import java.lang.reflect.Method

/**
 * 基于 libxposed 的 [HookBridge] 实现（LSPosed 路径）。
 */
internal class XposedHookBridge(private val xposed: XposedInterface) : HookBridge {

    override fun hookMethod(method: Method, hookId: String, callback: (HookChain) -> Any?): Any? {
        method.isAccessible = true
        return xposed.hook(method)
            .setId(hookId)
            .intercept { chain -> callback(LsposedChain(chain)) }
    }

    override fun hookConstructor(
        constructor: Constructor<*>,
        hookId: String,
        callback: (HookChain) -> Any?,
    ): Any? {
        constructor.isAccessible = true
        return xposed.hook(constructor)
            .setId(hookId)
            .intercept { chain -> callback(LsposedChain(chain)) }
    }

    override fun log(level: Int, tag: String, msg: String) {
        xposed.log(level, tag, msg, null)
    }

    private class LsposedChain(
        private val chain: io.github.libxposed.api.XposedInterface.Chain,
    ) : HookChain {
        override val executable: Member get() = chain.executable
        override val thisObject: Any? get() = chain.thisObject
        override val args: List<Any?> get() = chain.args
        override fun proceed(): Any? = chain.proceed()
        override fun proceed(args: Array<out Any?>): Any? = chain.proceed(args)
    }
}
