/*
 * Weimo Zygisk module - native side.
 *
 * Design (modeled after WeKite's zygisk implementation):
 *  - Only targets the WeChat main process (com.tencent.mm).
 *  - In preAppSpecialize (still running with zygote privileges) it queries the
 *    root companion for the per-feature mask persisted by the WebUI/config.sh
 *    at /data/adb/weimo_zygisk/config.tsv, and keeps the module-dir fd open.
 *  - In postAppSpecialize (now running as the app uid) it copies the bundled
 *    APK + native hooking libraries from the module dir via the saved fd,
 *    builds a PathClassLoader over the copied APK and hands control to
 *    com.ziymmx.wx.loader.zygisk.ZygiskEntry (Kotlin, uses Pine + DexKit).
 *  - Every other process is unloaded immediately (DLCLOSE_MODULE_LIBRARY) so
 *    the runtime overhead outside WeChat is effectively zero.
 */
#include <jni.h>
#include <android/log.h>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>
#include "zygisk.hpp"

#define LOG_TAG "WeimoZ"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#ifdef WEIMO_DEBUG
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#else
#define LOGI(...) ((void)0)
#endif

namespace {

constexpr const char* kTargetPackage = "com.tencent.mm";
constexpr const char* kConfigPath = "/data/adb/weimo_zygisk/config.tsv";

// Keep in sync with com.ziymmx.wx.util.FeatureFlags
constexpr uint32_t kAntiRecall = 1u << 0;
constexpr uint32_t kForceTablet = 1u << 1;
constexpr uint32_t kAntiXposedDetect = 1u << 2;
constexpr uint32_t kDisableHotUpdate = 1u << 3;
constexpr uint32_t kMomentsAntiRecall = 1u << 4;
constexpr uint32_t kMomentsCommentAntiRecall = 1u << 5;
constexpr uint32_t kMomentsAdBlock = 1u << 6;

constexpr uint32_t kAllFeatures =
    kAntiRecall | kForceTablet | kAntiXposedDetect | kDisableHotUpdate |
    kMomentsAntiRecall | kMomentsCommentAntiRecall | kMomentsAdBlock;

struct Feature {
    const char* name;
    uint32_t bit;
};

constexpr Feature kFeatures[] = {
    {"anti-recall", kAntiRecall},
    {"force-tablet", kForceTablet},
    {"anti-xposed-detect", kAntiXposedDetect},
    {"disable-hot-update", kDisableHotUpdate},
    {"moments-anti-recall", kMomentsAntiRecall},
    {"moments-comment-anti-recall", kMomentsCommentAntiRecall},
    {"moments-ad-block", kMomentsAdBlock},
};

constexpr size_t kFeatureCount = sizeof(kFeatures) / sizeof(kFeatures[0]);

#if defined(__aarch64__)
constexpr const char* kAbiDir = "arm64-v8a";
#elif defined(__arm__)
constexpr const char* kAbiDir = "armeabi-v7a";
#else
#error "Unsupported ABI for Weimo Zygisk module"
#endif

void write_u32(int fd, uint32_t value) {
    // Little-endian on all supported Android ABIs.
    unsigned char buf[4] = {
        static_cast<unsigned char>(value & 0xff),
        static_cast<unsigned char>((value >> 8) & 0xff),
        static_cast<unsigned char>((value >> 16) & 0xff),
        static_cast<unsigned char>((value >> 24) & 0xff),
    };
    ssize_t off = 0;
    while (off < 4) {
        ssize_t n = write(fd, buf + off, 4 - off);
        if (n <= 0) return;
        off += n;
    }
}

bool read_exact(int fd, void* out, size_t len) {
    auto* p = static_cast<unsigned char*>(out);
    size_t got = 0;
    while (got < len) {
        ssize_t n = read(fd, p + got, len - got);
        if (n <= 0) return false;
        got += static_cast<size_t>(n);
    }
    return true;
}

uint32_t read_u32_le(int fd) {
    unsigned char buf[4] = {0, 0, 0, 0};
    if (!read_exact(fd, buf, 4)) return 0;
    return static_cast<uint32_t>(buf[0]) |
           (static_cast<uint32_t>(buf[1]) << 8) |
           (static_cast<uint32_t>(buf[2]) << 16) |
           (static_cast<uint32_t>(buf[3]) << 24);
}

bool parse_bool(const char* s) {
    if (!s) return true;
    // Trim surrounding whitespace.
    while (*s == ' ' || *s == '\t' || *s == '\r' || *s == '\n') ++s;
    return *s == '1';
}

// Read the persisted feature mask. Any missing/unknown line keeps the default
// (enabled). A missing file therefore means "everything enabled", matching the
// module-wide "features enabled by default" policy.
uint32_t read_feature_mask_from_file(const char* path) {
    uint32_t mask = kAllFeatures;
    int fd = open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) return mask;
    char buf[512];
    ssize_t n;
    while ((n = read(fd, buf, sizeof(buf) - 1)) > 0) {
        buf[n] = '\0';
        char* line = buf;
        while (line && *line) {
            char* nl = strchr(line, '\n');
            if (nl) *nl = '\0';
            if (*line && *line != '#') {
                char* tab = strchr(line, '\t');
                if (!tab) tab = strchr(line, ' ');
                if (tab) {
                    *tab = '\0';
                    const char* name = line;
                    const char* value = tab + 1;
                    for (size_t i = 0; i < kFeatureCount; ++i) {
                        if (strcmp(name, kFeatures[i].name) == 0) {
                            if (!parse_bool(value)) {
                                mask &= ~kFeatures[i].bit;
                            }
                            break;
                        }
                    }
                }
            }
            line = nl ? nl + 1 : nullptr;
        }
    }
    close(fd);
    return mask;
}

// ---- Companion (runs in a root daemon process) ----
void companion_handler(int client) {
    unsigned char request = 0;
    if (!read_exact(client, &request, 1)) {
        close(client);
        return;
    }
    uint32_t mask = kAllFeatures;
    if (request == 1) {
        mask = read_feature_mask_from_file(kConfigPath);
    }
    write_u32(client, mask);
    close(client);
}

// ---- File helpers used in postAppSpecialize (app sandbox) ----
bool ensure_dir(const char* path) {
    char tmp[512];
    snprintf(tmp, sizeof(tmp), "%s", path);
    size_t len = strlen(tmp);
    if (len == 0) return false;
    if (tmp[len - 1] == '/') tmp[len - 1] = '\0';
    for (char* p = tmp + 1; *p; ++p) {
        if (*p == '/') {
            *p = '\0';
            if (mkdir(tmp, 0700) != 0 && errno != EEXIST) return false;
            chmod(tmp, 0700);
            *p = '/';
        }
    }
    if (mkdir(tmp, 0700) != 0 && errno != EEXIST) return false;
    chmod(tmp, 0700);
    return true;
}

bool copy_file_from_module(int module_fd, const char* rel, const char* dst) {
    int src = openat(module_fd, rel, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (src < 0) {
        LOGE("openat %s failed: %s", rel, strerror(errno));
        return false;
    }
    struct stat st {};
    if (fstat(src, &st) != 0 || !S_ISREG(st.st_mode) || st.st_size <= 0 ||
        st.st_size > 256 * 1024 * 1024) {
        LOGE("invalid module payload %s", rel);
        close(src);
        return false;
    }
    char tmp[768];
    snprintf(tmp, sizeof(tmp), "%s.%d.tmp", dst, static_cast<int>(getpid()));
    unlink(tmp);
    int out = open(tmp, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW, 0600);
    if (out < 0) {
        LOGE("create %s failed: %s", tmp, strerror(errno));
        close(src);
        return false;
    }
    char buf[65536];
    bool ok = true;
    for (;;) {
        ssize_t r = read(src, buf, sizeof(buf));
        if (r == 0) break;
        if (r < 0) {
            if (errno == EINTR) continue;
            ok = false;
            break;
        }
        ssize_t off = 0;
        while (off < r) {
            ssize_t w = write(out, buf + off, static_cast<size_t>(r - off));
            if (w <= 0) {
                if (w < 0 && errno == EINTR) continue;
                ok = false;
                break;
            }
            off += w;
        }
        if (!ok) break;
    }
    if (ok && fsync(out) != 0) ok = false;
    close(src);
    close(out);
    if (!ok || rename(tmp, dst) != 0) {
        LOGE("publish %s failed", dst);
        unlink(tmp);
        return false;
    }
    return true;
}

// Copy a jstring field into a fixed buffer. Returns false when unset.
bool copy_jstring(JNIEnv* env, jstring str, char* out, size_t cap) {
    if (!str) return false;
    const char* utf = env->GetStringUTFChars(str, nullptr);
    if (!utf) return false;
    bool truncated = strlen(utf) >= cap;
    snprintf(out, cap, "%s", utf);
    env->ReleaseStringUTFChars(str, utf);
    return !truncated;
}

class WeimoModule : public zygisk::ModuleBase {
public:
    void onLoad(zygisk::Api* api, JNIEnv* env) override {
        this->api = api;
        this->env = env;
    }

    void preAppSpecialize(zygisk::AppSpecializeArgs* args) override {
        // Read process name first (cheap) before any IPC.
        if (!args->nice_name) {
            api->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
            return;
        }
        const char* name = env->GetStringUTFChars(args->nice_name, nullptr);
        bool isWeChatMain = name && strcmp(name, kTargetPackage) == 0;
        if (name) env->ReleaseStringUTFChars(args->nice_name, name);
        if (!isWeChatMain) {
            api->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
            return;
        }
        char data_dir[512];
        if (!args->app_data_dir ||
            !copy_jstring(env, args->app_data_dir, data_dir, sizeof(data_dir))) {
            api->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
            return;
        }
        module_dir_fd_ = api->getModuleDir();
        if (module_dir_fd_ < 0) {
            api->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
            return;
        }
        snprintf(data_dir_, sizeof(data_dir_), "%s", data_dir);
        // Query root companion for the persisted feature mask.
        uint32_t mask = query_feature_mask();
        if (mask == 0) {
            // All features disabled: nothing to do in this process.
            api->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
            enabled_ = false;
            return;
        }
        feature_mask_ = mask;
        enabled_ = true;
        LOGI("Weimo Zygisk preAppSpecialize OK process=%s mask=%u", kTargetPackage, mask);
    }

    void postAppSpecialize(const zygisk::AppSpecializeArgs*) override {
        if (!enabled_) return;
        char dir[600];
        snprintf(dir, sizeof(dir), "%s/files", data_dir_);
        if (!ensure_dir(dir)) {
            LOGE("ensure dir %s failed", dir);
            return;
        }
        snprintf(dir, sizeof(dir), "%s/files/.weimo", data_dir_);
        if (!ensure_dir(dir)) {
            LOGE("ensure dir %s failed", dir);
            return;
        }
        char apk_path[768];
        snprintf(apk_path, sizeof(apk_path), "%s/files/.weimo/weimo.apk", data_dir_);
        if (!copy_file_from_module(module_dir_fd_, "payload/weimo.apk", apk_path)) {
            LOGE("failed to copy payload apk");
            return;
        }
        char rel[256];
        char lib_path[768];
        snprintf(rel, sizeof(rel), "lib/%s/libpine.so", kAbiDir);
        snprintf(lib_path, sizeof(lib_path), "%s/files/.weimo/libpine.so", data_dir_);
        if (!copy_file_from_module(module_dir_fd_, rel, lib_path)) {
            LOGE("failed to copy libpine.so");
            return;
        }
        snprintf(rel, sizeof(rel), "lib/%s/libdexkit.so", kAbiDir);
        snprintf(lib_path, sizeof(lib_path), "%s/files/.weimo/libdexkit.so", data_dir_);
        if (!copy_file_from_module(module_dir_fd_, rel, lib_path)) {
            LOGE("failed to copy libdexkit.so");
            return;
        }
        char lib_dir[768];
        snprintf(lib_dir, sizeof(lib_dir), "%s/files/.weimo", data_dir_);
        if (!bootstrap(apk_path, lib_dir)) {
            LOGE("Weimo Zygisk bootstrap failed");
            return;
        }
        LOGI("Weimo Zygisk bootstrap completed for %s", kTargetPackage);
    }

    void preServerSpecialize(zygisk::ServerSpecializeArgs*) override {
        api->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
    }

private:
    uint32_t query_feature_mask() {
        int fd = api->connectCompanion();
        if (fd >= 0) {
            unsigned char request = 1;
            ssize_t w = write(fd, &request, 1);
            uint32_t mask = (w == 1) ? read_u32_le(fd) : kAllFeatures;
            close(fd);
            return mask;
        }
        // No companion available (unexpected): fall back to reading the config
        // directly while we still have zygote privileges, then to defaults.
        uint32_t mask = read_feature_mask_from_file(kConfigPath);
        LOGI("companion unavailable, read config directly mask=%u", mask);
        return mask;
    }

    // Build PathClassLoader over the copied APK and call ZygiskEntry.init.
    bool bootstrap(const char* apk_path, const char* lib_dir) {
        jclass path_cls = env->FindClass("dalvik/system/PathClassLoader");
        if (!path_cls) {
            env->ExceptionClear();
            LOGE("FindClass PathClassLoader failed");
            return false;
        }
        jmethodID ctor = env->GetMethodID(
            path_cls, "<init>", "(Ljava/lang/String;Ljava/lang/ClassLoader;)V");
        if (!ctor) {
            env->ExceptionClear();
            env->DeleteLocalRef(path_cls);
            LOGE("GetMethodID PathClassLoader.<init> failed");
            return false;
        }
        jclass cl_class = env->FindClass("java/lang/ClassLoader");
        jmethodID get_sys = cl_class
            ? env->GetStaticMethodID(cl_class, "getSystemClassLoader",
                                     "()Ljava/lang/ClassLoader;")
            : nullptr;
        if (!cl_class || !get_sys) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            if (cl_class) env->DeleteLocalRef(cl_class);
            env->DeleteLocalRef(path_cls);
            LOGE("getSystemClassLoader unavailable");
            return false;
        }
        jobject sys_loader = env->CallStaticObjectMethod(cl_class, get_sys);
        env->DeleteLocalRef(cl_class);
        if (!sys_loader) {
            env->ExceptionClear();
            env->DeleteLocalRef(path_cls);
            LOGE("getSystemClassLoader returned null");
            return false;
        }

        jstring apk_j = env->NewStringUTF(apk_path);
        if (!apk_j) {
            env->ExceptionClear();
            env->DeleteLocalRef(sys_loader);
            env->DeleteLocalRef(path_cls);
            return false;
        }
        jobject loader = env->NewObject(path_cls, ctor, apk_j, sys_loader);
        env->DeleteLocalRef(apk_j);
        env->DeleteLocalRef(sys_loader);
        env->DeleteLocalRef(path_cls);
        if (!loader) {
            env->ExceptionClear();
            LOGE("PathClassLoader construction failed");
            return false;
        }
        jobject loader_global = env->NewGlobalRef(loader);
        env->DeleteLocalRef(loader);
        if (!loader_global) return false;

        // Load com.ziymmx.wx.loader.zygisk.ZygiskEntry through this loader.
        jclass cl_lookup = env->FindClass("java/lang/ClassLoader");
        jmethodID load_class = cl_lookup
            ? env->GetMethodID(cl_lookup, "loadClass",
                               "(Ljava/lang/String;)Ljava/lang/Class;")
            : nullptr;
        if (cl_lookup) env->DeleteLocalRef(cl_lookup);
        if (!load_class) {
            env->ExceptionClear();
            env->DeleteGlobalRef(loader_global);
            LOGE("ClassLoader.loadClass unavailable");
            return false;
        }
        jstring entry_j = env->NewStringUTF(
            "com.ziymmx.wx.loader.zygisk.ZygiskEntry");
        if (!entry_j) {
            env->ExceptionClear();
            env->DeleteGlobalRef(loader_global);
            return false;
        }
        jobject entry_cls = env->CallObjectMethod(loader_global, load_class, entry_j);
        env->DeleteLocalRef(entry_j);
        if (!entry_cls) {
            env->ExceptionClear();
            env->DeleteGlobalRef(loader_global);
            LOGE("ZygiskEntry class not found in payload");
            return false;
        }
        jmethodID init = env->GetStaticMethodID(
            static_cast<jclass>(entry_cls), "init",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;"
            "Ljava/lang/String;I)V");
        if (!init) {
            env->ExceptionClear();
            env->DeleteLocalRef(entry_cls);
            env->DeleteGlobalRef(loader_global);
            LOGE("ZygiskEntry.init method not found");
            return false;
        }

        jstring process_j = env->NewStringUTF(kTargetPackage);
        jstring data_j = env->NewStringUTF(data_dir_);
        apk_j = env->NewStringUTF(apk_path);
        jstring lib_j = env->NewStringUTF(lib_dir);
        if (!process_j || !data_j || !apk_j || !lib_j) {
            env->ExceptionClear();
            env->DeleteLocalRef(entry_cls);
            env->DeleteGlobalRef(loader_global);
            return false;
        }
        env->CallStaticVoidMethod(static_cast<jclass>(entry_cls), init,
                                  process_j, data_j, apk_j, lib_j,
                                  static_cast<jint>(feature_mask_));
        bool failed = env->ExceptionCheck();
        if (failed) env->ExceptionClear();
        env->DeleteLocalRef(process_j);
        env->DeleteLocalRef(data_j);
        env->DeleteLocalRef(apk_j);
        env->DeleteLocalRef(lib_j);
        env->DeleteLocalRef(entry_cls);
        // Keep the classloader alive for the whole process lifetime so that
        // loaded hook classes (and their static state) are never collected.
        s_loader_global_ = loader_global;
        return !failed;
    }

    zygisk::Api* api = nullptr;
    JNIEnv* env = nullptr;
    int module_dir_fd_ = -1;
    char data_dir_[512] = {};
    uint32_t feature_mask_ = kAllFeatures;
    bool enabled_ = false;
    static jobject s_loader_global_;
};

jobject WeimoModule::s_loader_global_ = nullptr;

}  // namespace

REGISTER_ZYGISK_MODULE(WeimoModule)
REGISTER_ZYGISK_COMPANION(companion_handler)