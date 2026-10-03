// SPDX-License-Identifier: MPL-2.0
// JNI bindings for io.advancex.app.bridge.NativeBridge.
//
// The Kotlin side owns policy (which features are on, file locations, patch
// validation); this layer only marshals data into the native Session and
// RenderThread. Methods are registered in JNI_OnLoad so the Kotlin class can
// be refactored without renaming C symbols.
#include <android/native_window_jni.h>
#include <jni.h>

#include <cstring>
#include <deque>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "ax/audio/audio_output.h"
#include "ax/common/log.h"
#include "ax/runtime/crash_handler.h"
#include "ax/runtime/patch_blob.h"
#include "ax/runtime/session.h"
#include "render_thread.h"

#ifndef AX_VERSION
#define AX_VERSION "0.1.0"
#endif

namespace {

constexpr const char* kTag = "JNI";
constexpr const char* kBridgeClass = "io/advancex/app/bridge/NativeBridge";

struct NativeSession {
    ax::runtime::Session session;
    ax::jni::RenderThread render{&session.frames()};
    std::mutex dumpMutex;
    std::deque<ax::runtime::DumpedSprite> dumped;
    bool opened = false;
};

NativeSession* from(jlong handle) { return reinterpret_cast<NativeSession*>(handle); }

std::string toString(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string out = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(s, chars);
    return out;
}

jstring errorOrNull(JNIEnv* env, bool ok, const std::string& error) {
    if (ok) return nullptr;
    return env->NewStringUTF(error.empty() ? "unknown error" : error.c_str());
}

inline jint rgbaToArgb(uint32_t p) {
    uint32_t r = p & 0xFF, g = (p >> 8) & 0xFF, b = (p >> 16) & 0xFF;
    return static_cast<jint>(0xFF000000u | (r << 16) | (g << 8) | b);
}

inline uint32_t argbToRgba(jint v) {
    uint32_t p = static_cast<uint32_t>(v);
    uint32_t a = p >> 24, r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
    return (a << 24) | (b << 16) | (g << 8) | r;
}

// --- Methods -----------------------------------------------------------------

jboolean nInit(JNIEnv* env, jclass, jstring logPath, jstring crashPath) {
    ax::log::setFile(toString(env, logPath));
    bool ok = ax::runtime::installCrashHandler(toString(env, crashPath));
    AX_LOGI(kTag, "AdvanceX native %s initialised", AX_VERSION);
    return ok ? JNI_TRUE : JNI_FALSE;
}

jstring nVersion(JNIEnv* env, jclass) {
    std::string v = std::string("AdvanceX native ") + AX_VERSION + " / mGBA";
    return env->NewStringUTF(v.c_str());
}

jlong nCreate(JNIEnv*, jclass) { return reinterpret_cast<jlong>(new NativeSession()); }

void nDestroy(JNIEnv*, jclass, jlong handle) {
    NativeSession* s = from(handle);
    if (!s) return;
    s->render.stop();
    s->session.close();
    delete s;
}

jstring nOpen(JNIEnv* env, jclass, jlong handle, jstring romPath, jstring savePath, jstring biosPath, jstring gameId,
              jboolean advanceMode, jint audioBackend, jbyteArray patchBlob) {
    NativeSession* s = from(handle);
    if (!s) return env->NewStringUTF("invalid session");
    ax::runtime::LaunchParams p;
    p.romPath = toString(env, romPath);
    p.savePath = toString(env, savePath);
    p.biosPath = toString(env, biosPath);
    p.gameId = toString(env, gameId);
    p.advanceMode = advanceMode == JNI_TRUE;

    if (patchBlob && p.advanceMode) {
        jsize len = env->GetArrayLength(patchBlob);
        std::vector<uint8_t> blob(static_cast<size_t>(len));
        env->GetByteArrayRegion(patchBlob, 0, len, reinterpret_cast<jbyte*>(blob.data()));
        ax::runtime::PatchSet set;
        std::string err;
        if (ax::runtime::decodePatchBlob(blob.data(), blob.size(), &set, &err)) {
            p.romPatches = std::move(set.rom);
            p.memoryPatches = std::move(set.memory);
        } else {
            AX_LOGE(kTag, "Patch blob rejected (%s); starting without patches", err.c_str());
        }
    }

    auto backend = static_cast<ax::audio::Backend>(audioBackend < 0 || audioBackend > 3 ? 0 : audioBackend);
    std::string error;
    bool ok = s->session.open(p, ax::audio::createAudioOutput(backend), &error);
    if (ok) {
        s->opened = true;
        s->render.start();
    }
    return errorOrNull(env, ok, error);
}

void nStart(JNIEnv*, jclass, jlong handle) {
    if (auto* s = from(handle)) s->session.start();
}

void nSetPaused(JNIEnv*, jclass, jlong handle, jboolean paused) {
    if (auto* s = from(handle)) s->session.setPaused(paused == JNI_TRUE);
}

void nSetKeys(JNIEnv*, jclass, jlong handle, jint keys) {
    if (auto* s = from(handle)) s->session.setKeys(static_cast<uint32_t>(keys));
}

void nSetFastForward(JNIEnv*, jclass, jlong handle, jboolean active, jint multiplier) {
    if (auto* s = from(handle)) s->session.setFastForward(active == JNI_TRUE, multiplier);
}

void nSetRewinding(JNIEnv*, jclass, jlong handle, jboolean active) {
    if (auto* s = from(handle)) s->session.setRewinding(active == JNI_TRUE);
}

void nSetRewindConfig(JNIEnv*, jclass, jlong handle, jboolean enabled, jint interval, jint maxSeconds, jint maxMb) {
    auto* s = from(handle);
    if (!s) return;
    ax::engine::RewindConfig c;
    c.enabled = enabled == JNI_TRUE;
    c.intervalFrames = interval;
    c.maxSeconds = maxSeconds;
    c.maxBytes = static_cast<size_t>(maxMb < 4 ? 4 : maxMb) << 20;
    s->session.setRewindConfig(c);
}

void nSetAudioSettings(JNIEnv*, jclass, jlong handle, jfloat volume, jboolean muted, jint latencyMs,
                       jboolean lowLatency) {
    auto* s = from(handle);
    if (!s) return;
    ax::runtime::AudioSettings a;
    a.volume = volume;
    a.muted = muted == JNI_TRUE;
    a.latencyMs = latencyMs;
    a.lowLatency = lowLatency == JNI_TRUE;
    s->session.setAudioSettings(a);
}

void nSetFrameSkip(JNIEnv*, jclass, jlong handle, jint frames) {
    if (auto* s = from(handle)) s->session.setFrameSkip(frames);
}

void nSetVideoConfig(JNIEnv* env, jclass, jlong handle, jintArray ints, jfloatArray floats) {
    auto* s = from(handle);
    if (!s || !ints || !floats || env->GetArrayLength(ints) < 8 || env->GetArrayLength(floats) < 4) return;
    jint i[8];
    jfloat f[4];
    env->GetIntArrayRegion(ints, 0, 8, i);
    env->GetFloatArrayRegion(floats, 0, 4, f);
    ax::video::VideoConfig c;
    c.scaleMode = static_cast<ax::video::ScaleMode>(i[0] < 0 || i[0] > 2 ? 1 : i[0]);
    c.filter = static_cast<ax::video::Filter>(i[1] < 0 || i[1] > 2 ? 2 : i[1]);
    c.background = static_cast<ax::video::Background>(i[2] == 1 ? 1 : 0);
    c.colorCorrection = i[3] != 0;
    c.frameBlending = i[4] != 0;
    c.scanlines = i[5] != 0;
    c.lcdGrid = i[6] != 0;
    c.sharpen = i[7] != 0;
    c.scanlineStrength = f[0];
    c.gridStrength = f[1];
    c.sharpenStrength = f[2];
    c.frameBlendAmount = f[3];
    s->render.setVideoConfig(c);
}

void nSetSurface(JNIEnv* env, jclass, jlong handle, jobject surface) {
    auto* s = from(handle);
    if (!s) return;
    ANativeWindow* window = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
    s->render.setWindow(window);  // takes ownership of the reference
}

jstring nSaveState(JNIEnv* env, jclass, jlong handle, jstring path) {
    auto* s = from(handle);
    if (!s) return env->NewStringUTF("invalid session");
    std::string error;
    bool ok = s->session.saveState(toString(env, path), &error);
    return errorOrNull(env, ok, error);
}

jstring nLoadState(JNIEnv* env, jclass, jlong handle, jstring path) {
    auto* s = from(handle);
    if (!s) return env->NewStringUTF("invalid session");
    std::string error;
    bool ok = s->session.loadState(toString(env, path), &error);
    return errorOrNull(env, ok, error);
}

jboolean nReset(JNIEnv*, jclass, jlong handle) {
    auto* s = from(handle);
    return s && s->session.reset() ? JNI_TRUE : JNI_FALSE;
}

jstring nFlushSave(JNIEnv* env, jclass, jlong handle) {
    auto* s = from(handle);
    if (!s) return nullptr;
    std::string error;
    bool ok = s->session.flushSave(&error);
    return errorOrNull(env, ok, error);
}

jboolean nCaptureFrame(JNIEnv* env, jclass, jlong handle, jintArray out) {
    auto* s = from(handle);
    if (!s || !out || env->GetArrayLength(out) < 240 * 160) return JNI_FALSE;
    std::vector<uint32_t> rgba;
    if (!s->session.copyFrame(&rgba) || rgba.size() < 240 * 160) return JNI_FALSE;
    std::vector<jint> argb(rgba.size());
    for (size_t i = 0; i < rgba.size(); ++i) argb[i] = rgbaToArgb(rgba[i]);
    env->SetIntArrayRegion(out, 0, static_cast<jsize>(argb.size()), argb.data());
    return JNI_TRUE;
}

jstring nGetStats(JNIEnv* env, jclass, jlong handle, jdoubleArray out) {
    auto* s = from(handle);
    if (!s || !out || env->GetArrayLength(out) < 12) return env->NewStringUTF("none");
    auto st = s->session.stats();
    jdouble v[12] = {st.fps,
                     st.speed,
                     static_cast<double>(st.frameCounter),
                     static_cast<double>(st.audioBufferedFrames),
                     static_cast<double>(st.audioUnderrunFrames),
                     static_cast<double>(st.audioSampleRate),
                     st.fastForward ? 1.0 : 0.0,
                     st.rewinding ? 1.0 : 0.0,
                     st.rewindSeconds,
                     static_cast<double>(st.rewindBytes),
                     st.saveWriteFailed ? 1.0 : 0.0,
                     s->render.degraded() ? 1.0 : 0.0};
    env->SetDoubleArrayRegion(out, 0, 12, v);
    return env->NewStringUTF(st.audioBackend.c_str());
}

jobjectArray nGetPatchStatus(JNIEnv* env, jclass, jlong handle) {
    auto* s = from(handle);
    jclass stringClass = env->FindClass("java/lang/String");
    if (!s) return env->NewObjectArray(0, stringClass, nullptr);
    auto status = s->session.patchStatus();
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(status.size()), stringClass, nullptr);
    for (size_t i = 0; i < status.size(); ++i) {
        std::string line = status[i].id + "\t" + std::to_string(static_cast<int>(status[i].state)) + "\t" +
                           std::to_string(status[i].applyCount) + "\t" + status[i].message;
        jstring js = env->NewStringUTF(line.c_str());
        env->SetObjectArrayElement(arr, static_cast<jsize>(i), js);
        env->DeleteLocalRef(js);
    }
    return arr;
}

jboolean nSetPatchEnabled(JNIEnv* env, jclass, jlong handle, jstring id, jboolean enabled) {
    auto* s = from(handle);
    return s && s->session.setPatchEnabled(toString(env, id), enabled == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
}

void nSetReplacementKeys(JNIEnv* env, jclass, jlong handle, jlongArray keys) {
    auto* s = from(handle);
    if (!s) return;
    std::vector<uint64_t> out;
    if (keys) {
        jsize n = env->GetArrayLength(keys);
        std::vector<jlong> raw(static_cast<size_t>(n));
        env->GetLongArrayRegion(keys, 0, n, raw.data());
        for (jlong k : raw) out.push_back(static_cast<uint64_t>(k));
    }
    s->session.setReplacementKeys(out);
}

void nAddReplacementTexture(JNIEnv* env, jclass, jlong handle, jlong key, jintArray argb, jint width, jint height) {
    auto* s = from(handle);
    if (!s || !argb || width <= 0 || height <= 0 || width > 1024 || height > 1024) return;
    jsize n = env->GetArrayLength(argb);
    if (n < width * height) return;
    std::vector<jint> raw(static_cast<size_t>(width) * height);
    env->GetIntArrayRegion(argb, 0, width * height, raw.data());
    std::vector<uint32_t> rgba(raw.size());
    for (size_t i = 0; i < raw.size(); ++i) rgba[i] = argbToRgba(raw[i]);
    s->render.addReplacementTexture(static_cast<uint64_t>(key), std::move(rgba), width, height);
}

void nClearReplacementTextures(JNIEnv*, jclass, jlong handle) {
    if (auto* s = from(handle)) s->render.clearReplacementTextures();
}

void nSetAssetDump(JNIEnv*, jclass, jlong handle, jboolean enabled) {
    if (auto* s = from(handle)) s->session.setAssetDumpEnabled(enabled == JNI_TRUE);
}

jlong nPollDumpedSprite(JNIEnv* env, jclass, jlong handle, jintArray outArgb, jintArray outSize) {
    auto* s = from(handle);
    if (!s || !outArgb || !outSize || env->GetArrayLength(outSize) < 2) return 0;
    std::lock_guard<std::mutex> lock(s->dumpMutex);
    if (s->dumped.empty()) {
        for (auto& d : s->session.takeDumpedSprites()) s->dumped.push_back(std::move(d));
    }
    while (!s->dumped.empty()) {
        ax::runtime::DumpedSprite d = std::move(s->dumped.front());
        s->dumped.pop_front();
        if (env->GetArrayLength(outArgb) < d.width * d.height) continue;  // too large for caller buffer
        std::vector<jint> argb(d.rgba.size());
        for (size_t i = 0; i < d.rgba.size(); ++i) {
            uint32_t p = d.rgba[i];
            argb[i] = p ? rgbaToArgb(p) : 0;
        }
        env->SetIntArrayRegion(outArgb, 0, static_cast<jsize>(argb.size()), argb.data());
        jint size[2] = {d.width, d.height};
        env->SetIntArrayRegion(outSize, 0, 2, size);
        return static_cast<jlong>(d.key);
    }
    return 0;
}

jbyteArray nReadMemory(JNIEnv* env, jclass, jlong handle, jint address, jint size) {
    auto* s = from(handle);
    if (!s || size <= 0) return nullptr;
    std::vector<uint8_t> data;
    if (!s->session.readMemory(static_cast<uint32_t>(address), static_cast<size_t>(size), &data)) return nullptr;
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(data.size()));
    env->SetByteArrayRegion(arr, 0, static_cast<jsize>(data.size()), reinterpret_cast<const jbyte*>(data.data()));
    return arr;
}

const JNINativeMethod kMethods[] = {
    {"init", "(Ljava/lang/String;Ljava/lang/String;)Z", reinterpret_cast<void*>(nInit)},
    {"version", "()Ljava/lang/String;", reinterpret_cast<void*>(nVersion)},
    {"create", "()J", reinterpret_cast<void*>(nCreate)},
    {"destroy", "(J)V", reinterpret_cast<void*>(nDestroy)},
    {"open", "(JLjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ZI[B)Ljava/lang/String;",
     reinterpret_cast<void*>(nOpen)},
    {"start", "(J)V", reinterpret_cast<void*>(nStart)},
    {"setPaused", "(JZ)V", reinterpret_cast<void*>(nSetPaused)},
    {"setKeys", "(JI)V", reinterpret_cast<void*>(nSetKeys)},
    {"setFastForward", "(JZI)V", reinterpret_cast<void*>(nSetFastForward)},
    {"setRewinding", "(JZ)V", reinterpret_cast<void*>(nSetRewinding)},
    {"setRewindConfig", "(JZIII)V", reinterpret_cast<void*>(nSetRewindConfig)},
    {"setAudioSettings", "(JFZIZ)V", reinterpret_cast<void*>(nSetAudioSettings)},
    {"setFrameSkip", "(JI)V", reinterpret_cast<void*>(nSetFrameSkip)},
    {"setVideoConfig", "(J[I[F)V", reinterpret_cast<void*>(nSetVideoConfig)},
    {"setSurface", "(JLandroid/view/Surface;)V", reinterpret_cast<void*>(nSetSurface)},
    {"saveState", "(JLjava/lang/String;)Ljava/lang/String;", reinterpret_cast<void*>(nSaveState)},
    {"loadState", "(JLjava/lang/String;)Ljava/lang/String;", reinterpret_cast<void*>(nLoadState)},
    {"reset", "(J)Z", reinterpret_cast<void*>(nReset)},
    {"flushSave", "(J)Ljava/lang/String;", reinterpret_cast<void*>(nFlushSave)},
    {"captureFrame", "(J[I)Z", reinterpret_cast<void*>(nCaptureFrame)},
    {"getStats", "(J[D)Ljava/lang/String;", reinterpret_cast<void*>(nGetStats)},
    {"getPatchStatus", "(J)[Ljava/lang/String;", reinterpret_cast<void*>(nGetPatchStatus)},
    {"setPatchEnabled", "(JLjava/lang/String;Z)Z", reinterpret_cast<void*>(nSetPatchEnabled)},
    {"setReplacementKeys", "(J[J)V", reinterpret_cast<void*>(nSetReplacementKeys)},
    {"addReplacementTexture", "(JJ[III)V", reinterpret_cast<void*>(nAddReplacementTexture)},
    {"clearReplacementTextures", "(J)V", reinterpret_cast<void*>(nClearReplacementTextures)},
    {"setAssetDump", "(JZ)V", reinterpret_cast<void*>(nSetAssetDump)},
    {"pollDumpedSprite", "(J[I[I)J", reinterpret_cast<void*>(nPollDumpedSprite)},
    {"readMemory", "(JII)[B", reinterpret_cast<void*>(nReadMemory)},
};

}  // namespace

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass cls = env->FindClass(kBridgeClass);
    if (!cls) return JNI_ERR;
    if (env->RegisterNatives(cls, kMethods, sizeof(kMethods) / sizeof(kMethods[0])) != JNI_OK) return JNI_ERR;
    return JNI_VERSION_1_6;
}
