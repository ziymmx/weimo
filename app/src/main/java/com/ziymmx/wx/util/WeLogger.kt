package com.ziymmx.wx.util

import android.util.Log
import com.ziymmx.wx.BuildConfig
import io.github.libxposed.api.XposedInterface

/**
 * 统一日志出口。
 *
 * 参考 I-Am-Pad 的做法：不打印任何「已激活 / 启动」类日志，只输出有意义的
 * 调试信息与真实错误，避免每次打开微信都刷屏 LSPosed 日志。
 *
 * 等级策略：
 *  - Debug 构建：DEBUG / INFO / WARN / ERROR 全部输出，便于开发排查；
 *  - Release 构建：仅输出 ERROR（真实异常），正常运行零日志。
 */
internal object WeLogger {

    const val TAG = "Weimo"

    fun d(xposed: XposedInterface?, msg: String, t: Throwable? = null) {
        if (BuildConfig.DEBUG) xposed?.log(Log.DEBUG, TAG, msg, t)
    }

    fun i(xposed: XposedInterface?, msg: String, t: Throwable? = null) {
        if (BuildConfig.DEBUG) xposed?.log(Log.INFO, TAG, msg, t)
    }

    fun w(xposed: XposedInterface?, msg: String, t: Throwable? = null) {
        if (BuildConfig.DEBUG) xposed?.log(Log.WARN, TAG, msg, t)
    }

    fun e(xposed: XposedInterface?, msg: String, t: Throwable? = null) {
        xposed?.log(Log.ERROR, TAG, msg, t)
    }
}