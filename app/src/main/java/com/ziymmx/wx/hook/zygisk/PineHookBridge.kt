package com.ziymmx.wx.hook.zygisk

import android.util.Log
import com.ziymmx.wx.hook.common.HookBridge
import com.ziymmx.wx.hook.common.HookChain
import top.canyie.pine.Pine
import top.canyie.pine.PineConfig
import top.canyie.pine.callback.MethodHook
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Member
import java.lang.reflect.Method

/**
 * 基于 Pine 的 [HookBridge] 实现（Zygisk 路径）。
 *
 * 使用 beforeCall + setResult 实现「拦截式」语义：
 *  - 回调内调用 [HookChain.proceed] 会通过 Pine 执行原方法（不重新进入本 hook）；
 *  - 回调未调用 proceed 时，其返回值通过 [Pine.CallFrame.setResult] 提前返回，跳过原方法；
 *  - 回调抛出异常时转为 CallFrame 的 throwable，由 Pine 原样抛出。
 */
internal class PineHookBridge : HookBridge {

    override fun hookMethod(method: Method, hookId: String, callback: (HookChain) -> Any?): Any? {
        method.isAccessible = true
        return Pine.hook(method, InterceptHook(hookId, callback))
    }

    override fun hookConstructor(
        constructor: Constructor<*>,
        hookId: String,
        callback: (HookChain) -> Any?,
    ): Any? {
        constructor.isAccessible = true
        return Pine.hook(constructor, InterceptHook(hookId, callback))
    }

    override fun log(level: Int, tag: String, msg: String, t: Throwable?) {
        val sb = StringBuilder(msg)
        if (t != null) {
            sb.append("\n").append(Log.getStackTraceString(t))
        }
        Log.println(level, tag, sb.toString())
    }

    private class InterceptHook(
        @Suppress("unused") private val hookId: String,
        private val callback: (HookChain) -> Any?,
    ) : MethodHook() {
        override fun beforeCall(frame: Pine.CallFrame) {
            val result = try {
                callback(PineChain(frame))
            } catch (t: Throwable) {
                frame.setThrowable(unwrap(t))
                return
            }
            frame.setResult(result)
        }
    }

    private class PineChain(private val frame: Pine.CallFrame) : HookChain {
        override val executable: Member get() = frame.method
        override val thisObject: Any? get() = frame.thisObject
        override val args: List<Any?> get() = frame.args?.toList() ?: emptyList()

        override fun proceed(): Any? = invokeOriginal(frame.thisObject, frame.args)

        override fun proceed(args: Array<out Any?>): Any? = invokeOriginal(frame.thisObject, args)

        private fun invokeOriginal(thisObject: Any?, args: Array<out Any?>): Any? {
            return try {
                frame.invokeOriginalMethod(thisObject, args)
            } catch (e: InvocationTargetException) {
                throw (e.cause ?: e)
            }
        }
    }

    companion object {
        /** 初始化 Pine：加载 libpine.so、关闭 debug 日志并绕过隐藏 API 限制。 */
        fun ensureInitialized(libPath: String) {
            PineConfig.libLoader = Pine.LibLoader { System.load(libPath) }
            PineConfig.debug = false
            PineConfig.disableHiddenApiPolicy = true
            PineConfig.disableHiddenApiPolicyForPlatformDomain = true
            Pine.ensureInitialized()
        }
    }
}

/** 将 InvocationTargetException 拆包为真实原因。 */
private fun unwrap(t: Throwable): Throwable =
    if (t is InvocationTargetException) (t.cause ?: t) else t
