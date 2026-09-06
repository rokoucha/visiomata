#include <jni.h>

#include <algorithm>
#include <memory>
#include <string>
#include <vector>

#include <aribcaption/aribcaption.hpp>

namespace {

struct DecoderState {
    aribcaption::Context context;
    aribcaption::Decoder decoder{context};
    aribcaption::Renderer renderer{context};

    explicit DecoderState(const std::vector<std::string>& font_file_paths) {
        decoder.Initialize(aribcaption::EncodingScheme::kAuto,
                           aribcaption::CaptionType::kCaption);
        renderer.Initialize(aribcaption::CaptionType::kCaption,
                            aribcaption::FontProviderType::kAndroid,
                            aribcaption::TextRendererType::kFreetype);
        // Kosugi Maru supplies the normal caption glyphs. The compact ARIB supplement is
        // consulted only when the primary face does not contain the requested codepoint.
        auto font_families = font_file_paths;
        font_families.emplace_back("sans-serif");
        renderer.SetDefaultFontFamily(font_families, true);
        renderer.SetMergeRegionImages(false);
    }
};

DecoderState* state(jlong handle) {
    return reinterpret_cast<DecoderState*>(handle);
}

jobject make_caption(JNIEnv* env, const aribcaption::Caption& caption,
                     const aribcaption::RenderResult& rendered,
                     int render_width, int render_height) {
    jclass caption_class = env->FindClass(
        "net/rokoucha/visiomata/playback/libaribcaption/NativeCaption");
    jmethodID constructor = env->GetMethodID(
        caption_class, "<init>", "(JII[[I[I[I[I[I)V");

    const jsize count = static_cast<jsize>(rendered.images.size());
    jclass int_array_class = env->FindClass("[I");
    jobjectArray bitmaps = env->NewObjectArray(count, int_array_class, nullptr);
    std::vector<jint> xs(count), ys(count), widths(count), heights(count);

    for (jsize i = 0; i < count; ++i) {
        const auto& image = rendered.images[i];
        std::vector<jint> pixels(static_cast<size_t>(image.width) * image.height);
        for (int y = 0; y < image.height; ++y) {
            const uint8_t* row = image.bitmap.data() + static_cast<size_t>(y) * image.stride;
            for (int x = 0; x < image.width; ++x) {
                const uint8_t* rgba = row + x * 4;
                pixels[static_cast<size_t>(y) * image.width + x] =
                    (static_cast<jint>(rgba[3]) << 24) |
                    (static_cast<jint>(rgba[0]) << 16) |
                    (static_cast<jint>(rgba[1]) << 8) |
                    static_cast<jint>(rgba[2]);
            }
        }
        jintArray pixel_array = env->NewIntArray(static_cast<jsize>(pixels.size()));
        env->SetIntArrayRegion(pixel_array, 0, static_cast<jsize>(pixels.size()), pixels.data());
        env->SetObjectArrayElement(bitmaps, i, pixel_array);
        xs[i] = image.dst_x;
        ys[i] = image.dst_y;
        widths[i] = image.width;
        heights[i] = image.height;
    }

    auto make_int_array = [&](const std::vector<jint>& values) {
        jintArray result = env->NewIntArray(count);
        env->SetIntArrayRegion(result, 0, count, values.data());
        return result;
    };
    jintArray x_array = make_int_array(xs);
    jintArray y_array = make_int_array(ys);
    jintArray width_array = make_int_array(widths);
    jintArray height_array = make_int_array(heights);

    return env->NewObject(caption_class, constructor,
                          static_cast<jlong>(caption.wait_duration),
                          render_width, render_height, bitmaps,
                          x_array, y_array, width_array, height_array);
}

}

extern "C" JNIEXPORT jlong JNICALL
Java_net_rokoucha_visiomata_playback_libaribcaption_AribCaptionNative_create(
    JNIEnv* env, jobject, jobjectArray font_file_paths) {
    std::vector<std::string> paths;
    const jsize count = env->GetArrayLength(font_file_paths);
    paths.reserve(static_cast<size_t>(count));
    for (jsize i = 0; i < count; ++i) {
        auto path_string = static_cast<jstring>(env->GetObjectArrayElement(font_file_paths, i));
        const char* path = env->GetStringUTFChars(path_string, nullptr);
        paths.emplace_back(path);
        env->ReleaseStringUTFChars(path_string, path);
        env->DeleteLocalRef(path_string);
    }
    auto* decoder_state = new DecoderState(paths);
    return reinterpret_cast<jlong>(decoder_state);
}

extern "C" JNIEXPORT void JNICALL
Java_net_rokoucha_visiomata_playback_libaribcaption_AribCaptionNative_destroy(
    JNIEnv*, jobject, jlong handle) {
    delete state(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_net_rokoucha_visiomata_playback_libaribcaption_AribCaptionNative_reset(
    JNIEnv*, jobject, jlong handle) {
    auto* current = state(handle);
    current->decoder.Flush();
    current->renderer.Flush();
}

extern "C" JNIEXPORT jobject JNICALL
Java_net_rokoucha_visiomata_playback_libaribcaption_AribCaptionNative_decode(
    JNIEnv* env, jobject, jlong handle, jbyteArray data, jint offset, jint length) {
    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    aribcaption::DecodeResult result;
    auto status = state(handle)->decoder.Decode(
        reinterpret_cast<uint8_t*>(bytes + offset), static_cast<size_t>(length),
        aribcaption::PTS_NOPTS, result);
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    if (status != aribcaption::DecodeStatus::kGotCaption || !result.caption) {
        return nullptr;
    }
    auto& caption = *result.caption;
    caption.pts = 0;
    auto* decoder_state = state(handle);
    decoder_state->renderer.Flush();

    // ARIB captions commonly use a 960x540 logical plane. Rendering at that size and
    // letting SubtitleView enlarge the bitmap to a 1080p display makes only the captions
    // visibly pixelated. Supersample by an integer factor so coordinates and glyph metrics
    // remain exact while the rendered plane is at least Full HD.
    constexpr int kMinimumRenderWidth = 1920;
    constexpr int kMinimumRenderHeight = 1080;
    const int width_scale =
        (kMinimumRenderWidth + caption.plane_width - 1) / caption.plane_width;
    const int height_scale =
        (kMinimumRenderHeight + caption.plane_height - 1) / caption.plane_height;
    const int render_scale = std::max({1, width_scale, height_scale});
    const int render_width = caption.plane_width * render_scale;
    const int render_height = caption.plane_height * render_scale;
    decoder_state->renderer.SetFrameSize(render_width, render_height);
    if (!decoder_state->renderer.AppendCaption(caption)) {
        return nullptr;
    }
    aribcaption::RenderResult rendered;
    auto render_status = decoder_state->renderer.Render(0, rendered);
    if (render_status != aribcaption::RenderStatus::kGotImage &&
        render_status != aribcaption::RenderStatus::kGotImageUnchanged) {
        return nullptr;
    }
    return make_caption(env, caption, rendered, render_width, render_height);
}
