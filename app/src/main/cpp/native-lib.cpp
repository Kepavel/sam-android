#include <jni.h>
#include <string>
#include "sam.h"
#include <android/log.h>
#define LOG_TAG "SamLib"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#include <mutex>
#include <unordered_map>

// Global map and mutex to safely store and access shared_ptr instances
std::mutex g_state_map_mutex;
std::unordered_map<jlong, std::shared_ptr<sam_state>> g_state_map;
jlong g_next_state_id = 1; // Start with 1 to avoid using 0 as a valid ID


extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_samandroid_SamLib_stringFromJNI(JNIEnv *env, jclass clazz) {
    // TODO: implement stringFromJNI()
    std::string hello = "He"
                        "llo from C++";
    return env->NewStringUTF(hello.c_str());
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_example_samandroid_SamLib_samLoadModel(JNIEnv *env, jclass clazz, jstring model_path) {
    LOGI("Entering samLoadModel");

    if (model_path == nullptr) {
        LOGE("Model path is null");
        return 0;
    }

    const char *modelPathCStr = env->GetStringUTFChars(model_path, nullptr);
    if (modelPathCStr == nullptr) {
        LOGE("Failed to convert jstring to C string");
        return 0;
    }

    LOGI("Model path: %s", modelPathCStr);

    sam_params params;
    params.model = modelPathCStr;

    std::shared_ptr<sam_state> state;
    try {
        state = sam_load_model(params);
    } catch (const std::exception& e) {
        LOGE("Exception in sam_load_model: %s", e.what());
        env->ReleaseStringUTFChars(model_path, modelPathCStr);
        return 0;
    } catch (...) {
        LOGE("Unknown exception in sam_load_model");
        env->ReleaseStringUTFChars(model_path, modelPathCStr);
        return 0;
    }

    env->ReleaseStringUTFChars(model_path, modelPathCStr);

    if (!state) {
        LOGE("Failed to load model");
        return 0;
    }

    LOGI("Model loaded successfully");

    // Safely store the shared_ptr in the global map and return the unique ID
    std::lock_guard<std::mutex> lock(g_state_map_mutex);
    jlong state_id = g_next_state_id++;
    g_state_map[state_id] = state;
    LOGI("in %s, state_id = %l", __func__, state_id);
    return state_id;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_example_samandroid_SamLib_samComputeEmbImg(JNIEnv *env, jclass clazz, jlong state_id,
                                                    jbyteArray image_data, jint width,
                                                    jint height) {
    LOGI("Entering samComputeEmbImg");

    if (state_id == 0) {
        LOGE("Invalid state pointer (null)");
        return JNI_FALSE;
    }

    if (image_data == nullptr) {
        LOGE("Image data array is null");
        return JNI_FALSE;
    }

    if (env->GetArrayLength(image_data) < width * height * 3) {
        LOGE("Image data array size does not match expected size");
        return JNI_FALSE;
    }

    // Retrieve the shared_ptr from the global map
    std::shared_ptr<sam_state> state;
    {
        std::lock_guard<std::mutex> lock(g_state_map_mutex);
        auto it = g_state_map.find(state_id);
        if (it == g_state_map.end()) {
            LOGE("Invalid state ID or state has been deinitialized");
            return JNI_FALSE;
        }
        state = it->second;
    }
    jbyte *imageDataC = env->GetByteArrayElements(image_data, nullptr);
    if (imageDataC == nullptr) {
        LOGE("Failed to get byte array elements for image data");
        return JNI_FALSE;
    }

    LOGI("Image data received. Width: %d, Height: %d", width, height);

    // Create a std::vector from the raw byte array
    std::vector<uint8_t> imgData(imageDataC, imageDataC + width * height * 3);

    sam_image_u8 img;
    img.nx = width;
    img.ny = height;
    img.data = std::move(imgData); // Use std::move to transfer ownership of the data

    // Determine the number of threads to use
    int n_threads = std::min(4, (int)std::thread::hardware_concurrency());
    LOGI("Number of threads to use: %d", n_threads);

    bool result = false;
    try {
        result = sam_compute_embd_img(img, n_threads, *state);
    } catch (const std::exception &e) {
        LOGE("Exception in sam_compute_embd_img: %s", e.what());
    } catch (...) {
        LOGE("Unknown exception in sam_compute_embd_img");
    }

    // Release the JNI array elements
    env->ReleaseByteArrayElements(image_data, imageDataC, JNI_ABORT);

    if (result) {
        LOGI("samComputeEmbImg succeeded");
    } else {
        LOGE("samComputeEmbImg failed");
    }

    return result ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_example_samandroid_SamLib_samComputeMasks(JNIEnv *env, jclass clazz, jlong state_id,
                                                   jbyteArray image_data, jint width, jint height,
                                                   jfloat x, jfloat y) {
    // TODO: implement samComputeMasks()
    if (state_id == 0) {
        LOGE("Invalid state ID (0)");
        return nullptr;
    }

    // Retrieve the shared_ptr from the global map
    std::shared_ptr<sam_state> state;
    {
        std::lock_guard<std::mutex> lock(g_state_map_mutex);
        auto it = g_state_map.find(state_id);
        if (it == g_state_map.end()) {
            LOGE("Invalid state ID or state has been deinitialized");
            return nullptr;
        }
        state = it->second;
    }
    jbyte *imageDataC = env->GetByteArrayElements(image_data, nullptr);

    if (imageDataC == nullptr) {
        LOGE("Failed to get byte array elements for image data");
        return nullptr;
    }

    sam_image_u8 img;
    img.nx = width;
    img.ny = height;
    img.data = std::vector<uint8_t>(imageDataC, imageDataC + width * height * 3); // Assuming RGB image

    sam_point pt;
    pt.x = x;
    pt.y = y;

    // Determine the number of threads to use
    int n_threads = std::min(4, (int)std::thread::hardware_concurrency());

    std::vector<sam_image_u8> masks = sam_compute_masks(img, n_threads, pt, *state);

    // Check if masks are empty or have different sizes
    if (masks.empty() || masks[0].nx * masks[0].ny == 0) {
        LOGE("Masks are empty or have invalid size");
        env->ReleaseByteArrayElements(image_data, imageDataC, JNI_ABORT);
        return nullptr;
    }

    // Assuming all masks are the same size, calculate the total size needed for the 1D byte array
    size_t totalSize = masks.size() * masks[0].nx * masks[0].ny;

    jbyteArray maskArray = env->NewByteArray(totalSize);

    // Release the JNI array elements
    env->ReleaseByteArrayElements(image_data, imageDataC, JNI_ABORT);
    // Copy each mask's data into the 1D byte array
    for (size_t i = 0; i < masks.size(); ++i) {
        env->SetByteArrayRegion(maskArray, i * masks[i].nx * masks[i].ny, masks[i].nx * masks[i].ny, reinterpret_cast<jbyte*>(masks[i].data.data()));
    }
    return maskArray;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_samandroid_SamLib_samDeinit(JNIEnv *env, jclass clazz, jlong state_id) {
    LOGI("Entering samDeinit");
    if (state_id == 0) {
        return; // Invalid ID
    }

    // Safely remove the shared_ptr from the global map
    std::lock_guard<std::mutex> lock(g_state_map_mutex);
    auto it = g_state_map.find(state_id);
    if (it != g_state_map.end()) {
        sam_deinit(*it->second); // Deinitialize the state
        g_state_map.erase(it); // Remove from map
    }
    LOGI("Existing samDeinit");
}
