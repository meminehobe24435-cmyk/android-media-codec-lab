#include "wave.h"
#include <jni.h>
#include <exception>
#include <vector>
extern "C" JNIEXPORT jintArray JNICALL
Java_org_medialab_codec_WaveNative_parse(JNIEnv* env, jclass, jbyteArray bytes) {
    if (!bytes) { env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "null WAV"); return nullptr; }
    const jsize n = env->GetArrayLength(bytes);
    if (n > 16 * 1024 * 1024) { env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "WAV exceeds 16 MiB"); return nullptr; }
    try {
        std::vector<uint8_t> data(static_cast<size_t>(n));
        env->GetByteArrayRegion(bytes, 0, n, reinterpret_cast<jbyte*>(data.data()));
        if (env->ExceptionCheck()) return nullptr;
        auto w = medialab::parse_wave(data.data(), data.size());
        jint fields[] = {jint(w.rate), jint(w.channels), jint(w.offset), jint(w.bytes)};
        auto out = env->NewIntArray(4);
        if (out) env->SetIntArrayRegion(out, 0, 4, fields);
        return out;
    } catch (const std::exception& e) { env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), e.what()); return nullptr; }
}
