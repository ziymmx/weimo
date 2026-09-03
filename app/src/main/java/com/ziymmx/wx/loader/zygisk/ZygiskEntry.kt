package com.ziymmx.wx.loader.zygisk

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import com.ziymmx.wx.hook.FeatureInstaller
import com.ziymmx.wx.hook.zygisk.PineHookBridge
import com.ziymmx.wx.util.HookUtils
import com.ziymmx.wx.util.WeLogger
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 微末 Weimo —— Zygisk 路径的 JVM 入口。
 *
 * 由原生侧在微信主进程 postAppSpecialize 阶段调用：
 *   ZygiskEntry.init(processName, dataDir, apkPath, libDir, featureMask)
 *
 * 此时应用尚未开始执行任何业务代码，微信的真实 ClassLoader 也尚未创建。
 * 因此这里先初始化 Pine + DexKit（两者随 APK 一起被复制到应用数据目录），
 * 再按 WeKite 的做法挂上框架生命周期钩子：
 *   LoadedApk.createAppFactory -> AppComponentFactory.instantiateClassLoader
 * 等到微信真正的 ClassLoader 出现后再安装各功能 Hook，保证不遗漏早期调用。
 *
 * 该入口仅在 Zygisk 路径被原生代码反射调用；LSPosed 路径不会触达这里。
 * 所有功能开关由原生侧读取 WebUI 配置得到的 featureMask 决定。
 */
object ZygiskEntry {

    private const val TARGET_PACKAGE = "com.tencent.mm"

    private val entryStarted = AtomicBoolean(false)
    private val hooksStarted = AtomicBoolean(false)

    @JvmStatic
    @Suppress("unused")
    fun init(
        processName: String?,
        dataDir: String?,
        apkPath: String?,
        libDir: String?,
        featureMask: Int,
    ) {
        if (processName != TARGET_PACKAGE) return
        if (!entryStarted.compareAndSet(false, true)) return

        val apk = apkPath
        val lib = libDir
        if (apk.isNullOrEmpty() || lib.isNullOrEmpty()) {
            entryStarted.set(false)
            return
        }
        val logger = PineHookBridge()
        try {
            PineHookBridge.ensureInitialized(File(lib, "libpine.so").absolutePath)
            HookUtils.loadDexKitLibrary(File(lib, "libdexkit.so"))
            hookUntilClassLoaderReady(logger, apk, featureMask)
        } catch (t: Throwable) {
            entryStarted.set(false)
            WeLogger.e(logger, "Weimo Zygisk 初始化失败", t)
        }
    }

    /**
     * 挂上等待真实 ClassLoader 的钩子链：
     * createAppFactory 触发 -> 拿到 AppComponentFactory 实例 ->
     * 再挂 instantiateClassLoader -> 拿到微信 ClassLoader 后开始安装 Hook。
     */
    @SuppressLint("PrivateApi")
    private fun hookUntilClassLoaderReady(
        logger: PineHookBridge,
        apkPath: String,
        featureMask: Int,
    ) {
        val loadedApk = Class.forName("android.app.LoadedApk")
        val createAppFactory = loadedApk.getDeclaredMethod(
            "createAppFactory",
            ApplicationInfo::class.java,
            ClassLoader::class.java,
        ).apply { isAccessible = true }

        logger.hookMethod(createAppFactory, "weimo_zygisk_app_factory") { chain ->
            val result = chain.proceed()
            val appInfo = chain.args.getOrNull(0) as? ApplicationInfo
            val factory = result
            if (appInfo?.packageName == TARGET_PACKAGE && factory != null) {
                hookFinalClassLoader(logger, factory, apkPath, featureMask)
            }
            result
        }
    }

    private fun hookFinalClassLoader(
        logger: PineHookBridge,
        factory: Any,
        apkPath: String,
        featureMask: Int,
    ) {
        try {
            val method = factory.javaClass.getMethod(
                "instantiateClassLoader",
                ClassLoader::class.java,
                ApplicationInfo::class.java,
            )
            logger.hookMethod(method, "weimo_zygisk_final_loader") { chain ->
                val result = chain.proceed()
                val appInfo = chain.args.getOrNull(1) as? ApplicationInfo
                val loader = result as? ClassLoader
                if (appInfo?.packageName == TARGET_PACKAGE && loader != null) {
                    startHooks(logger, loader, appInfo, apkPath, featureMask)
                }
                result
            }
        } catch (t: Throwable) {
            WeLogger.e(logger, "Weimo Zygisk 挂载 ClassLoader 钩子失败", t)
        }
    }

    @SuppressLint("PrivateApi")
    private fun startHooks(
        logger: PineHookBridge,
        loader: ClassLoader,
        appInfo: ApplicationInfo,
        apkPath: String,
        featureMask: Int,
    ) {
        if (!hooksStarted.compareAndSet(false, true)) return
        try {
            val sourceDir = appInfo.sourceDir ?: apkPath
            DexKitBridge.create(sourceDir).use { dex ->
                FeatureInstaller.install(
                    hook = logger,
                    dex = dex,
                    classLoader = loader,
                    flags = featureMask,
                )
            }
        } catch (t: Throwable) {
            hooksStarted.set(false)
            WeLogger.e(logger, "Weimo Zygisk Hook 安装失败", t)
        }
    }
}
