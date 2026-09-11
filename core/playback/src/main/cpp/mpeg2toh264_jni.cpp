#include <jni.h>

#include <cstddef>
#include <cstdint>

extern "C" {

struct NativeBuffer {
  std::uint8_t* data;
  std::size_t len;
};

void* visiomata_mpeg2_create();
void visiomata_mpeg2_destroy(void* handle);
void visiomata_mpeg2_reset(void* handle);
NativeBuffer visiomata_mpeg2_push(void* handle,
                                  const std::uint8_t* data,
                                  std::size_t len,
                                  std::int64_t pts_us,
                                  bool has_pts,
                                  bool finish);
void visiomata_mpeg2_free(NativeBuffer buffer);
void* visiomata_aac_create();
void visiomata_aac_destroy(void* handle);
void visiomata_aac_reset(void* handle);
NativeBuffer visiomata_aac_push(void* handle,
                                const std::uint8_t* data,
                                std::size_t len,
                                bool finish);

JNIEXPORT jlong JNICALL
Java_net_rokoucha_visiomata_playback_mpeg2toh264_Mpeg2ToH264Native_create(JNIEnv*, jclass) {
  return reinterpret_cast<jlong>(visiomata_mpeg2_create());
}

JNIEXPORT void JNICALL
Java_net_rokoucha_visiomata_playback_mpeg2toh264_Mpeg2ToH264Native_destroy(JNIEnv*,
                                                                     jclass,
                                                                     jlong handle) {
  visiomata_mpeg2_destroy(reinterpret_cast<void*>(handle));
}

JNIEXPORT void JNICALL
Java_net_rokoucha_visiomata_playback_mpeg2toh264_Mpeg2ToH264Native_reset(JNIEnv*,
                                                                   jclass,
                                                                   jlong handle) {
  visiomata_mpeg2_reset(reinterpret_cast<void*>(handle));
}

JNIEXPORT jobject JNICALL
Java_net_rokoucha_visiomata_playback_mpeg2toh264_Mpeg2ToH264Native_pushDirect(JNIEnv* env,
                                                                        jclass,
                                                                        jlong handle,
                                                                        jbyteArray input,
                                                                        jlong pts_us,
                                                                        jboolean has_pts,
                                                                        jboolean finish) {
  const jsize length = input == nullptr ? 0 : env->GetArrayLength(input);
  jbyte* bytes = input == nullptr ? nullptr : env->GetByteArrayElements(input, nullptr);
  if (input != nullptr && bytes == nullptr) {
    return nullptr;
  }
  NativeBuffer output = visiomata_mpeg2_push(
      reinterpret_cast<void*>(handle), reinterpret_cast<std::uint8_t*>(bytes),
      static_cast<std::size_t>(length), static_cast<std::int64_t>(pts_us), has_pts == JNI_TRUE,
      finish == JNI_TRUE);
  if (input != nullptr) {
    env->ReleaseByteArrayElements(input, bytes, JNI_ABORT);
  }

  // Hand the native allocation to the caller without copying; it is released exactly once
  // via freeDirect below. A null return means the bridge failed and must fail the stream.
  if (output.len == 0) {
    visiomata_mpeg2_free(output);
    return nullptr;
  }
  return env->NewDirectByteBuffer(output.data, static_cast<jlong>(output.len));
}

JNIEXPORT void JNICALL
Java_net_rokoucha_visiomata_playback_mpeg2toh264_Mpeg2ToH264Native_freeDirect(JNIEnv* env,
                                                                        jclass,
                                                                        jobject buffer) {
  if (buffer == nullptr) {
    return;
  }
  void* address = env->GetDirectBufferAddress(buffer);
  const jlong capacity = env->GetDirectBufferCapacity(buffer);
  if (address == nullptr || capacity < 0) {
    return;
  }
  visiomata_mpeg2_free(
      NativeBuffer{reinterpret_cast<std::uint8_t*>(address), static_cast<std::size_t>(capacity)});
}

JNIEXPORT jlong JNICALL
Java_net_rokoucha_visiomata_playback_media3_AribAacNative_create(JNIEnv*, jclass) {
  return reinterpret_cast<jlong>(visiomata_aac_create());
}

JNIEXPORT void JNICALL
Java_net_rokoucha_visiomata_playback_media3_AribAacNative_destroy(JNIEnv*, jclass, jlong handle) {
  visiomata_aac_destroy(reinterpret_cast<void*>(handle));
}

JNIEXPORT void JNICALL
Java_net_rokoucha_visiomata_playback_media3_AribAacNative_reset(JNIEnv*, jclass, jlong handle) {
  visiomata_aac_reset(reinterpret_cast<void*>(handle));
}

JNIEXPORT jbyteArray JNICALL
Java_net_rokoucha_visiomata_playback_media3_AribAacNative_push(JNIEnv* env,
                                                               jclass,
                                                               jlong handle,
                                                               jbyteArray input,
                                                               jboolean finish) {
  const jsize length = input == nullptr ? 0 : env->GetArrayLength(input);
  jbyte* bytes = input == nullptr ? nullptr : env->GetByteArrayElements(input, nullptr);
  if (input != nullptr && bytes == nullptr) {
    return nullptr;
  }
  NativeBuffer output = visiomata_aac_push(
      reinterpret_cast<void*>(handle), reinterpret_cast<std::uint8_t*>(bytes),
      static_cast<std::size_t>(length), finish == JNI_TRUE);
  if (input != nullptr) {
    env->ReleaseByteArrayElements(input, bytes, JNI_ABORT);
  }
  jbyteArray result = env->NewByteArray(static_cast<jsize>(output.len));
  if (result != nullptr && output.len != 0) {
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(output.len),
                            reinterpret_cast<const jbyte*>(output.data));
  }
  visiomata_mpeg2_free(output);
  return result;
}

}
