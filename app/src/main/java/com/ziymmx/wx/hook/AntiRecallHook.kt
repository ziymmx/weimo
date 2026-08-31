package com.ziymmx.wx.hook

import android.content.ContentValues
import android.content.Context
import android.util.Log
import android.view.View
import com.ziymmx.wx.util.HookUtils
import io.github.libxposed.api.XposedInterface
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * 防撤回。
 *
 * 微信撤回消息时，服务端会下发一条 sysmsg/revokemsg 类型的 XML，由
 * XmlParser 解析成 Map 后交给业务层处理。本模块在解析完成后：
 *
 *  1. 将解析结果中的类型置空，阻止微信执行「撤回」流程，原消息保留在聊天界面；
 *  2. 通过 MsgInfoStorage 反查被撤回的原始消息，插入一条 type=10000 的系统提示，
 *     文案与微信原生撤回提示一致（如「张三」撤回了一条消息），不附加任何自定义内容；
 *  3. 使该提示可点击：点击后调用微信自身的 ChattingDataAdapterV3#T0，
 *     滚动并高亮定位到被撤回的原消息。
 *
 * 全部为本地实现，不申请任何网络权限，也不修改微信布局、不嵌入微信界面。
 */
internal object AntiRecallHook {

    private const val TYPE_KEY = ".sysmsg.\$type"
    private const val TYPE_REVOKE = "revokemsg"
    private const val TYPE_SYSTEM = 10000

    private data class RecallInfo(val talker: String, val msgId: Long)

    // ---- 安装期解析出的微信内部类 / 方法 / 实例 ----
    private var msgInfoClass: Class<*>? = null
    private var msgInfoStorageClass: Class<*>? = null
    private var storageFeatureClass: Class<*>? = null
    private var getServiceMethod: Method? = null
    private var msgInfoStorageInstance: Any? = null
    private var insertMsgMethod: Method? = null
    private var getMsgInfoMethod: Method? = null

    // ---- 点击跳转所需的微信类 ----
    private var zc5zClass: Class<*>? = null
    private var oe5nClass: Class<*>? = null
    private var zc5yClass: Class<*>? = null
    private var actionPosition: Any? = null

    // 提示内容 -> 原始消息定位信息，供点击跳转使用
    private val recallMap = ConcurrentHashMap<String, RecallInfo>()

    fun install(xposed: XposedInterface, bridge: DexKitBridge, classLoader: ClassLoader) {
        prepareStorage(xposed, bridge, classLoader)
        loadClickClasses(classLoader)
        hookXmlParser(xposed, bridge, classLoader)
        hookSystemItemClick(xposed, bridge, classLoader)
    }

    // ------------------------------------------------------------------
    // 解析消息存储相关类与方法（参考 WeKite WeServiceApi/WeMessageApi）
    // ------------------------------------------------------------------
    private fun prepareStorage(xposed: XposedInterface, bridge: DexKitBridge, classLoader: ClassLoader) {
        runCatching {
            val msgInfoDex = bridge.findClass {
                searchPackages("com.tencent.mm.storage")
                matcher {
                    usingEqStrings("MicroMsg.MsgInfo", "[parseNewXmlSysMsg]")
                }
            }.firstOrNull() ?: return

            val msgInfoStorageDex = bridge.findClass {
                searchPackages("com.tencent.mm.storage")
                matcher {
                    usingEqStrings("MicroMsg.MsgInfoStorage", "deleted dirty msg ,count is %d")
                }
            }.firstOrNull() ?: return

            // MsgInfoStorage.insert(msgInfo) —— 用于把提示消息写入会话
            val insertDex = bridge.findMethod {
                matcher {
                    declaredClass(msgInfoStorageDex.name)
                    usingEqStrings("MsgInfo processAddMsg insert db error")
                }
            }.firstOrNull() ?: return

            // MsgInfoStorage.getMsgInfoByTalkerAndSvrId(String, long) —— 反查原消息
            val getBySvrIdDex = bridge.findMethod {
                matcher {
                    declaredClass(msgInfoStorageDex.name)
                    paramTypes("java.lang.String", "long")
                    returnType(msgInfoDex.name)
                    usingEqStrings("msgSvrId=?")
                }
            }.firstOrNull() ?: return

            // ServiceManager.getService(Class) —— 微信服务定位入口
            val getServiceDex = bridge.findMethod {
                matcher {
                    modifiers(Modifier.STATIC)
                    paramTypes(Class::class.java)
                    usingEqStrings("calling getService(...)")
                }
            }.firstOrNull() ?: return

            // StorageFeatureService —— 提供 MsgInfoStorage 的获取方法
            val storageFeatureDex = bridge.findClass {
                searchPackages("com.tencent.mm.plugin.messenger.foundation")
                matcher {
                    methods {
                        add {
                            returnType {
                                usingEqStrings("PRAGMA table_info( contact_ext )")
                            }
                        }
                        add {
                            returnType {
                                usingEqStrings("MicroMsg.MsgInfoStorage", "deleted dirty msg ,count is %d")
                            }
                        }
                        add {
                            returnType {
                                usingEqStrings("PRAGMA table_info( rconversation)")
                            }
                        }
                    }
                }
            }.firstOrNull() ?: return

            val msgInfoCls = msgInfoDex.getInstance(classLoader)
            val msgInfoStorageCls = msgInfoStorageDex.getInstance(classLoader)
            val storageFeatureCls = storageFeatureDex.getInstance(classLoader)

            // 此处只解析类与方法引用；真正调用 getService 获取存储实例的动作
            // 延迟到第一条撤回消息到达时（ensureStorage），此时微信已完成初始化。
            msgInfoClass = msgInfoCls
            msgInfoStorageClass = msgInfoStorageCls
            storageFeatureClass = storageFeatureCls
            getServiceMethod = getServiceDex.getMethodInstance(classLoader).apply { isAccessible = true }
            insertMsgMethod = insertDex.getMethodInstance(classLoader).apply { isAccessible = true }
            getMsgInfoMethod = getBySvrIdDex.getMethodInstance(classLoader).apply { isAccessible = true }

        }.onFailure { xposed.log(Log.WARN, HookUtils.TAG, "防撤回：消息存储解析失败，仅保留撤回拦截", it) }
    }

    /**
     * 懒加载 MsgInfoStorage 实例。
     *
     * 微信 ServiceManager 在进程启动初期尚未 initialize()，此时调用
     * getService(Class) 会抛 IllegalStateException: please call initialize(...)。
     * 因此把真正获取实例的动作推迟到第一条撤回消息到达时执行——那时微信
     * 已完成初始化，getService 可用；失败也只影响提示插入，不影响撤回拦截。
     */
    private fun ensureStorage(): Boolean {
        if (msgInfoStorageInstance != null) return true
        val getService = getServiceMethod ?: return false
        val featureCls = storageFeatureClass ?: return false
        val storageCls = msgInfoStorageClass ?: return false
        return runCatching {
            val storageService = getService.invoke(null, featureCls) ?: return false
            val storage = findStorageGetter(storageService, storageCls)?.invoke(storageService) ?: return false
            msgInfoStorageInstance = storage
            true
        }.getOrElse { false }
    }

    // 在服务实例上查找「无参且返回 MsgInfoStorage」的获取方法（含父类）
    private fun findStorageGetter(service: Any, returnType: Class<*>): Method? {
        var c: Class<*>? = service.javaClass
        while (c != null) {
            val m = c.declaredMethods.firstOrNull {
                it.parameterCount == 0 && it.returnType == returnType
            }
            if (m != null) return m.apply { isAccessible = true }
            c = c.superclass
        }
        return null
    }

    // ------------------------------------------------------------------
    // 加载点击跳转所需的微信类（zc5.z / oe5.n / zc5.y）
    // ------------------------------------------------------------------
    private fun loadClickClasses(classLoader: ClassLoader) {
        zc5zClass = runCatching { classLoader.loadClass("zc5.z") }.getOrNull()
        oe5nClass = runCatching { classLoader.loadClass("oe5.n") }.getOrNull()
        zc5yClass = runCatching { classLoader.loadClass("zc5.y") }.getOrNull()
        actionPosition = oe5nClass?.enumConstants?.getOrNull(4) // ACTION_POSITION
    }

    // ------------------------------------------------------------------
    // 拦截撤回：XmlParser 解析完成后执行
    // ------------------------------------------------------------------
    private fun hookXmlParser(xposed: XposedInterface, bridge: DexKitBridge, classLoader: ClassLoader) {
        runCatching {
            val matches = bridge.findMethod {
                matcher {
                    usingEqStrings("MicroMsg.SDK.XmlParser", "[ %s ]")
                }
            }
            val dexMethod = matches.firstOrNull { it.returnTypeName.contains("Map") } ?: matches.firstOrNull() ?: return
            val method = dexMethod.getMethodInstance(classLoader)
            method.isAccessible = true

            xposed.hook(method)
                .setId("weimo_anti_recall")
                .intercept { chain ->
                    val original = chain.proceed()
                    @Suppress("UNCHECKED_CAST")
                    val result = original as? MutableMap<String, Any?>
                    if (result != null && result[TYPE_KEY] == TYPE_REVOKE) {
                        // 先阻断撤回，保证原消息一定保留
                        runCatching { result[TYPE_KEY] = null }
                            .onFailure { xposed.log(Log.WARN, HookUtils.TAG, "阻断撤回失败", it) }
                        // 再尽力插入提示（失败不影响阻断）
                        runCatching { handleRecall(result) }
                            .onFailure { xposed.log(Log.WARN, HookUtils.TAG, "插入撤回提示失败", it) }
                    }
                    original
                }

        }.onFailure { xposed.log(Log.WARN, HookUtils.TAG, "防撤回 hook 异常", it) }
    }

    private fun handleRecall(result: MutableMap<String, Any?>) {
        val newMsgSvrId = (result[".sysmsg.revokemsg.newmsgid"] as? String)?.toLongOrNull() ?: return
        val replaceMsg = result[".sysmsg.revokemsg.replacemsg"] as? String ?: return
        val session = result[".sysmsg.revokemsg.session"] as? String ?: ""

        // 懒加载存储实例：此时微信已完全初始化，getService 可安全调用。
        if (!ensureStorage()) return

        val msgInfo = lookupOriginalMsg(newMsgSvrId, session)
        if (msgInfo == null) {
            // 反查不到原消息时，仍给出不含原文的提示
            if (session.isBlank()) return
            val sender = extractSenderName(replaceMsg) ?: "对方"
            insertSystemMsg(session, "「$sender」撤回了一条消息", System.currentTimeMillis())
            return
        }

        val talker = (readField(msgInfo, "field_talker") as? String)?.takeIf { it.isNotBlank() } ?: session
        val createTime = (readField(msgInfo, "field_createTime") as? Long) ?: System.currentTimeMillis()
        val msgId = (readField(msgInfo, "field_msgId") as? Long) ?: 0L

        // 直接使用微信下发的原生撤回提示文案（replacemsg，如「张三」撤回了一条消息），
        // 与微信原始样式一致，不附加任何自定义内容；点击提示仍可定位并高亮原消息。
        val notice = replaceMsg

        // 以提示文本为键：同一提示只插一次，且点击时能按 field_content 反查定位
        if (recallMap.putIfAbsent(notice, RecallInfo(talker, msgId)) == null) {
            insertSystemMsg(talker, notice, createTime + 1)
        }
    }

    private fun lookupOriginalMsg(msgSvrId: Long, session: String): Any? {
        val method = getMsgInfoMethod ?: return null
        val storage = msgInfoStorageInstance ?: return null
        if (session.isBlank()) return null
        return runCatching { method.invoke(storage, session, msgSvrId) }.getOrNull()
    }

    private fun insertSystemMsg(talker: String, content: String, createTime: Long) {
        val cls = msgInfoClass ?: return
        val insert = insertMsgMethod ?: return
        val storage = msgInfoStorageInstance ?: return
        if (talker.isBlank()) return

        val values = ContentValues().apply {
            put("msgid", 0)
            put("msgSvrId", System.currentTimeMillis() + Random.nextInt(1_000_000))
            put("type", TYPE_SYSTEM)
            put("status", 3)
            put("createTime", createTime)
            put("talker", talker)
            put("content", content)
        }

        val msgInfo = cls.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val convertFrom = findConvertFrom(cls) ?: return
        convertFrom.invoke(msgInfo, values, true)
        insert.invoke(storage, msgInfo)
    }

    private fun findConvertFrom(cls: Class<*>): Method? {
        var c: Class<*>? = cls
        while (c != null) {
            val m = c.declaredMethods.firstOrNull {
                it.name == "convertFrom" &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == ContentValues::class.java &&
                    (it.parameterTypes[1] == Boolean::class.javaPrimitiveType ||
                        it.parameterTypes[1] == Boolean::class.javaObjectType)
            }
            if (m != null) return m.apply { isAccessible = true }
            c = c.superclass
        }
        return null
    }

    // ------------------------------------------------------------------
    // 让撤回提示可点击：hook ChattingItemSys.m 绑定点击事件
    // ------------------------------------------------------------------
    private fun hookSystemItemClick(xposed: XposedInterface, bridge: DexKitBridge, classLoader: ClassLoader) {
        runCatching {
            val matches = bridge.findMethod {
                matcher {
                    usingEqStrings("tmpl_type_masssend_sys_tip", "tmpl_type_auto_translation_sys_tip")
                }
            }.filter { it.paramTypeNames.size == 4 }
            val dexMethod = matches.firstOrNull() ?: return
            val method = dexMethod.getMethodInstance(classLoader)
            method.isAccessible = true

            xposed.hook(method)
                .setId("weimo_recall_tip_click")
                .intercept { chain ->
                    val original = chain.proceed()
                    runCatching {
                        val holder = chain.args.getOrNull(0)
                        val dVar = chain.args.getOrNull(1)
                        val data = chain.args.getOrNull(2)
                        if (holder == null || dVar == null || data == null) return@runCatching

                        val dataField = readField(data, "d") ?: return@runCatching
                        val msgInfo = readField(dataField, "b") ?: return@runCatching
                        val content = readField(msgInfo, "field_content") as? String ?: return@runCatching
                        val info = recallMap[content] ?: return@runCatching
                        val textView = readField(holder, "b") as? View ?: return@runCatching

                        textView.setOnClickListener { view ->
                            runCatching { jumpToMessage(view, dVar, info) }
                                .onFailure { xposed.log(Log.WARN, HookUtils.TAG, "定位原消息失败", it) }
                        }
                        textView.isClickable = true
                        textView.isFocusable = true
                    }.onFailure { xposed.log(Log.WARN, HookUtils.TAG, "绑定撤回提示点击失败", it) }
                    original
                }

        }.onFailure { xposed.log(Log.WARN, HookUtils.TAG, "撤回提示点击定位 hook 失败（不影响撤回拦截）", it) }
    }

    private fun jumpToMessage(view: View, dVar: Any, info: RecallInfo) {
        val managerC = readField(dVar, "c") ?: return
        val zClass = zc5zClass ?: return
        val nClass = oe5nClass ?: return
        val yClass = zc5yClass ?: return
        val action = actionPosition ?: return

        // manager.c.a(zc5.z.class) -> ChattingDataAdapterV3 (adapter.k)
        val adapter = managerC.javaClass.getMethod("a", Class::class.java)
            .apply { isAccessible = true }
            .invoke(managerC, zClass) ?: return

        // zc5.y：跳转参数
        val yObj = yClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        setField(yObj, "b", true)  // IS_HIGHLIGHT_ITEM
        setField(yObj, "c", true)  // IS_IDLE_VISBLE
        setField(yObj, "e", dpToPx(view.context, 120))  // SELECTION_TOP_OFFSET
        setField(yObj, "f", true)  // IS_SMOOTH_SCROLL
        setField(yObj, "g", true)  // SELECT_BY_MSG_ID

        // adapter.k.T0(talker, msgId, oe5.n.ACTION_POSITION, y)
        val t0 = adapter.javaClass.getMethod(
            "T0",
            String::class.java,
            Long::class.javaPrimitiveType,
            nClass,
            yClass
        ).apply { isAccessible = true }
        t0.invoke(adapter, info.talker, info.msgId, action, yObj)
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------
    private fun extractSenderName(replaceMsg: String): String? {
        return Regex("([\"「])(.*?)([」\"])").find(replaceMsg)?.groupValues?.getOrNull(2)
    }

    private fun dpToPx(context: Context, dp: Int): Int {
        return (context.resources.displayMetrics.density * dp + 0.5f).toInt()
    }

    private fun readField(obj: Any, name: String): Any? {
        var c: Class<*>? = obj.javaClass
        while (c != null) {
            val f = runCatching { c.getDeclaredField(name) }.getOrNull()
            if (f != null) {
                f.isAccessible = true
                return f.get(obj)
            }
            c = c.superclass
        }
        return null
    }

    private fun setField(obj: Any, name: String, value: Any) {
        var c: Class<*>? = obj.javaClass
        while (c != null) {
            val f = runCatching { c.getDeclaredField(name) }.getOrNull()
            if (f != null) {
                f.isAccessible = true
                f.set(obj, value)
                return
            }
            c = c.superclass
        }
    }
}