/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 *
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

#include <glog/logging.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <filesystem>
#include <cstring>
#include <iterator>
#include <fstream>
#include <limits>
#include <memory>
#include <ostream>
#include <stdexcept>
#include <string>
#include <system_error>
#include <vector>

namespace google {
namespace logging {
namespace internal {
template <typename T>
void MakeCheckOpValueString(std::ostream* os, const T& v);
}  // namespace internal
}  // namespace logging
}  // namespace google

#include "knowhere_wrapper.h"

#include "knowhere/comp/index_param.h"
#include "knowhere/dataset.h"
#include "knowhere/expected.h"
#include "knowhere/index/index.h"
#include "knowhere/index/index_factory.h"
#include "knowhere/object.h"
#include "opensearch_file_manager.h"
#include "commons.h"
#include "native_engines_stream_support.h"

namespace knn_jni {
namespace knowhere_wrapper {

namespace {

struct LoadedKnowhereIndex {
    knowhere::Index<knowhere::IndexNode> index;
    std::shared_ptr<knowhere::FileManager> file_manager;
    std::filesystem::path local_data_dir;
};

struct TempDirGuard {
    explicit TempDirGuard(std::filesystem::path dir) : dir(std::move(dir)) {}

    ~TempDirGuard() {
        if (dir.empty()) {
            return;
        }
        std::error_code ec;
        std::filesystem::remove_all(dir, ec);
    }

    std::filesystem::path dir;
};

struct WrapperContext {
    jobject directory;
    jobject io_context;
    std::string file_name;
};

struct ManifestInfo {
    std::string index_prefix;
    std::vector<std::string> files;
};

class LocalKnowhereFileManager : public knowhere::FileManager {
public:
    explicit LocalKnowhereFileManager(std::filesystem::path root_dir) : root_dir_(std::move(root_dir)) {}

    bool
    LoadFile(const std::string& filename) noexcept override {
        return std::filesystem::exists(LocalPath(filename));
    }

    bool
    AddFile(const std::string& filename) noexcept override {
        const auto local_path = LocalPath(filename);
        if (!std::filesystem::exists(local_path)) {
            return false;
        }
        added_files_.push_back(local_path.filename().string());
        return true;
    }

    std::optional<bool>
    IsExisted(const std::string& filename) noexcept override {
        return std::filesystem::exists(LocalPath(filename));
    }

    bool
    RemoveFile(const std::string& filename) noexcept override {
        std::error_code ec;
        const auto local_path = LocalPath(filename);
        return std::filesystem::remove(local_path, ec) || !std::filesystem::exists(local_path);
    }

    std::vector<std::string>
    AddedFiles() const {
        return added_files_;
    }

private:
    std::filesystem::path
    LocalPath(const std::string& filename) const {
        std::filesystem::path path(filename);
        if (path.is_absolute()) {
            return path;
        }
        return root_dir_ / path.filename();
    }

    std::filesystem::path root_dir_;
    std::vector<std::string> added_files_;
};

std::string
GetRawDataPath(const std::string& index_prefix) {
    return index_prefix + ".raw_data";
}

void
WriteRawDataFile(const std::string& path, const float* vectors, uint32_t rows, uint32_t dim) {
    std::ofstream writer(path, std::ios::binary | std::ios::trunc);
    if (!writer.is_open()) {
        throw std::runtime_error("Failed to open knowhere raw data file for write: " + path);
    }

    writer.write(reinterpret_cast<const char*>(&rows), sizeof(rows));
    writer.write(reinterpret_cast<const char*>(&dim), sizeof(dim));
    writer.write(reinterpret_cast<const char*>(vectors), static_cast<std::streamsize>(rows) * dim * sizeof(float));

    if (!writer.good()) {
        throw std::runtime_error("Failed to write knowhere raw data file: " + path);
    }
}

bool
IsIntJsonKey(const std::string& key) {
    return key == knowhere::meta::DIM || key == knowhere::meta::TOPK || key == "max_degree" || key == "search_list_size" ||
           key == "beamwidth" || key == "disk_pq_dims" || key == "num_build_thread" || key == "num_threads";
}

bool
IsFloatJsonKey(const std::string& key) {
    return key == "pq_code_budget_gb" || key == "build_dram_budget_gb" || key == "search_cache_budget_gb" ||
           key == "pq_code_budget_gb_ratio" || key == "search_cache_budget_gb_ratio" || key == "beamwidth_ratio" ||
           key == "num_build_thread_ratio" || key == "num_load_thread_ratio";
}

bool
IsBoolJsonKey(const std::string& key) {
    return key == "accelerate_build" || key == "warm_up" || key == "use_bfs_cache" || key == "use_mmap" ||
           key == "trace_visit";
}

constexpr float kBytesPerGiB = 1024.0f * 1024.0f * 1024.0f;

void
NormalizeDiskannBudgetParams(knowhere::Json& json, int64_t rows, int64_t dim) {
    const float raw_vector_gib = static_cast<float>(rows) * static_cast<float>(dim) * static_cast<float>(sizeof(float)) / kBytesPerGiB;

    if (!json.contains("pq_code_budget_gb") && json.contains("pq_code_budget_gb_ratio")) {
        json["pq_code_budget_gb"] = raw_vector_gib * json["pq_code_budget_gb_ratio"].get<float>();
    }
    if (!json.contains("search_cache_budget_gb") && json.contains("search_cache_budget_gb_ratio")) {
        json["search_cache_budget_gb"] = raw_vector_gib * json["search_cache_budget_gb_ratio"].get<float>();
    }
}

void
NormalizeDiskannThreadParams(knowhere::Json& json) {
    json["num_build_thread"] = 1;
    json["num_threads"] = 1;
}

std::string
NormalizeMetricType(const std::string& value) {
    if (value == "l2" || value == "L2") {
        return "L2";
    }
    if (value == "innerproduct" || value == "inner_product" || value == "ip" || value == "IP") {
        return "IP";
    }
    if (value == "cosinesimil" || value == "cosine" || value == "COSINESIMIL" || value == "COSINE") {
        return "COSINE";
    }
    return value;
}

std::string
ConvertJavaObjectToString(JNIEnv* env, jobject valueJ) {
    if (valueJ == nullptr) {
        return "";
    }

    jclass string_class = env->FindClass("java/lang/String");
    if (env->IsInstanceOf(valueJ, string_class) == JNI_TRUE) {
        const char* c_string = env->GetStringUTFChars(reinterpret_cast<jstring>(valueJ), nullptr);
        if (c_string == nullptr) {
            throw std::runtime_error("Unable to convert java string to cpp string");
        }
        std::string result(c_string);
        env->ReleaseStringUTFChars(reinterpret_cast<jstring>(valueJ), c_string);
        env->DeleteLocalRef(string_class);
        return result;
    }
    env->DeleteLocalRef(string_class);

    jclass object_class = env->GetObjectClass(valueJ);
    if (object_class == nullptr) {
        throw std::runtime_error("Unable to inspect java object for string conversion");
    }

    jmethodID to_string = env->GetMethodID(object_class, "toString", "()Ljava/lang/String;");
    if (to_string == nullptr) {
        env->DeleteLocalRef(object_class);
        throw std::runtime_error("Unable to find toString method for java object");
    }

    auto string_obj = reinterpret_cast<jstring>(env->CallObjectMethod(valueJ, to_string));
    env->DeleteLocalRef(object_class);
    if (string_obj == nullptr) {
        throw std::runtime_error("Unable to convert java object to string");
    }

    const char* c_string = env->GetStringUTFChars(string_obj, nullptr);
    if (c_string == nullptr) {
        env->DeleteLocalRef(string_obj);
        throw std::runtime_error("Unable to convert java object string to cpp string");
    }

    std::string result(c_string);
    env->ReleaseStringUTFChars(string_obj, c_string);
    env->DeleteLocalRef(string_obj);
    return result;
}

void
MergeJavaMapIntoJson(JNIUtilInterface* jniUtil, JNIEnv* env, jobject mapJ, knowhere::Json& json) {
    if (mapJ == nullptr) {
        return;
    }

    jclass map_class = env->FindClass("java/util/Map");
    auto paramsMap = jniUtil->ConvertJavaMapToCppMap(env, mapJ);
    for (auto const& [key, valueJ] : paramsMap) {
        if (valueJ == nullptr) {
            continue;
        }

        if ((key == "parameters" || key == "method_parameters") && env->IsInstanceOf(valueJ, map_class) == JNI_TRUE) {
            MergeJavaMapIntoJson(jniUtil, env, valueJ, json);
            continue;
        }

        std::string value = ConvertJavaObjectToString(env, valueJ);
        if (value.empty()) {
            continue;
        }

        try {
            if (key == "space_type" || key == "spaceType") {
                json[knowhere::meta::METRIC_TYPE] = NormalizeMetricType(value);
            } else if (key == "path") {
                json[knowhere::meta::INDEX_PREFIX] = value;
            } else if (IsIntJsonKey(key)) {
                json[key] = std::stoi(value);
            } else if (IsFloatJsonKey(key)) {
                json[key] = std::stof(value);
            } else if (IsBoolJsonKey(key)) {
                json[key] = value == "true";
            } else {
                json[key] = value;
            }
        } catch (...) {
            json[key] = value;
        }
    }
    env->DeleteLocalRef(map_class);
}

knowhere::Json
MapToJson(JNIUtilInterface* jniUtil, JNIEnv* env, jobject parametersJ) {
    knowhere::Json json;
    MergeJavaMapIntoJson(jniUtil, env, parametersJ, json);
    return json;
}

std::filesystem::path
CreateUniqueTempDir(const std::string& prefix) {
    const auto base_dir = std::filesystem::temp_directory_path();
    const auto now = std::chrono::steady_clock::now().time_since_epoch().count();
    for (int attempt = 0; attempt < 32; ++attempt) {
        auto candidate = base_dir / (prefix + "-" + std::to_string(now) + "-" + std::to_string(attempt));
        std::error_code ec;
        if (std::filesystem::create_directories(candidate, ec)) {
            return candidate;
        }
    }
    throw std::runtime_error("Failed to create temporary directory for knowhere integration");
}

std::string
DeriveDiskannPrefixBase(const std::string& manifest_file_name) {
    constexpr const char* kKnowhereSuffix = ".knowhere";
    constexpr const char* kKnowhereCompoundSuffix = ".knowherec";

    std::string base = manifest_file_name;
    if (base.size() >= std::strlen(kKnowhereCompoundSuffix) &&
        base.compare(base.size() - std::strlen(kKnowhereCompoundSuffix), std::strlen(kKnowhereCompoundSuffix), kKnowhereCompoundSuffix) == 0) {
        base.erase(base.size() - std::strlen(kKnowhereCompoundSuffix));
        return base + ".diskann";
    }
    if (base.size() >= std::strlen(kKnowhereSuffix) &&
        base.compare(base.size() - std::strlen(kKnowhereSuffix), std::strlen(kKnowhereSuffix), kKnowhereSuffix) == 0) {
        base.erase(base.size() - std::strlen(kKnowhereSuffix));
        return base + ".diskann";
    }
    throw std::runtime_error("Invalid knowhere manifest file name: " + manifest_file_name);
}

WrapperContext
GetWrapperContext(JNIUtilInterface* jniUtil, JNIEnv* env, jobject wrapperJ, const char* class_name) {
    jclass wrapper_class = jniUtil->FindClassFromJNIEnv(env, class_name);
    jfieldID directory_field = jniUtil->GetFieldID(env, wrapper_class, "directory", "Lorg/apache/lucene/store/Directory;");
    jfieldID io_context_field = jniUtil->GetFieldID(env, wrapper_class, "ioContext", "Lorg/apache/lucene/store/IOContext;");
    jfieldID file_name_field = jniUtil->GetFieldID(env, wrapper_class, "fileName", "Ljava/lang/String;");

    jobject directory = env->GetObjectField(wrapperJ, directory_field);
    jobject io_context = env->GetObjectField(wrapperJ, io_context_field);
    auto file_name_j = reinterpret_cast<jstring>(env->GetObjectField(wrapperJ, file_name_field));

    if (directory == nullptr || io_context == nullptr || file_name_j == nullptr) {
        if (directory != nullptr) {
            env->DeleteLocalRef(directory);
        }
        if (io_context != nullptr) {
            env->DeleteLocalRef(io_context);
        }
        if (file_name_j != nullptr) {
            env->DeleteLocalRef(file_name_j);
        }
        throw std::runtime_error(std::string("Missing Lucene file-management context for ") + class_name);
    }

    WrapperContext context { directory, io_context, jniUtil->ConvertJavaStringToCppString(env, file_name_j) };
    env->DeleteLocalRef(file_name_j);
    return context;
}

std::shared_ptr<LocalKnowhereFileManager>
CreateLocalFileManager(const std::filesystem::path& local_data_dir) {
    return std::make_shared<LocalKnowhereFileManager>(local_data_dir);
}

void
ReleaseWrapperContext(JNIEnv* env, const WrapperContext& context) {
    env->DeleteLocalRef(context.directory);
    env->DeleteLocalRef(context.io_context);
}

void
CopyLocalFilesToDirectory(JNIUtilInterface* jniUtil,
                          JNIEnv* env,
                          const WrapperContext& context,
                          const std::filesystem::path& local_data_dir,
                          const std::vector<std::string>& file_names) {
    OpenSearchFileManager manager(jniUtil, env, context.directory, context.io_context, local_data_dir.string());
    for (const auto& file_name : file_names) {
        const auto local_path = local_data_dir / file_name;
        if (!manager.AddFile(local_path.string())) {
            throw std::runtime_error("Failed to copy knowhere artifact into Lucene directory: " + local_path.string());
        }
    }
}

bool
HasSuffix(const std::string& value, const std::string& suffix) {
    return value.size() >= suffix.size() && value.compare(value.size() - suffix.size(), suffix.size(), suffix) == 0;
}

void
CopyDirectoryFilesToLocal(JNIUtilInterface* jniUtil,
                          JNIEnv* env,
                          const WrapperContext& context,
                          const std::filesystem::path& local_data_dir,
                          const std::vector<std::string>& file_names,
                          bool use_compound_suffix = false) {
    OpenSearchFileManager manager(jniUtil, env, context.directory, context.io_context, local_data_dir.string());
    for (const auto& file_name : file_names) {
        const std::string lucene_file_name = use_compound_suffix ? file_name + "c" : file_name;
        const auto source_local_path = local_data_dir / lucene_file_name;
        const auto target_local_path = local_data_dir / file_name;
        if (!manager.LoadFile(source_local_path.string())) {
            throw std::runtime_error("Failed to copy knowhere artifact from Lucene directory: " + file_name);
        }
        if (use_compound_suffix && source_local_path != target_local_path) {
            std::error_code ec;
            std::filesystem::rename(source_local_path, target_local_path, ec);
            if (ec) {
                throw std::runtime_error("Failed to rename knowhere compound artifact locally: " + source_local_path.string());
            }
        }
    }
}

void
WriteManifest(const std::filesystem::path& manifest_path,
              const std::string& index_prefix,
              std::vector<std::string> files) {
    std::sort(files.begin(), files.end());
    files.erase(std::unique(files.begin(), files.end()), files.end());

    knowhere::Json manifest;
    manifest["version"] = 1;
    manifest["engine"] = "knowhere";
    manifest["method"] = "diskann";
    manifest["index_prefix"] = index_prefix;
    manifest["files"] = knowhere::Json::array();
    for (const auto& file_name : files) {
        manifest["files"].push_back({ { "name", file_name }, { "required", true } });
    }

    std::ofstream writer(manifest_path, std::ios::binary | std::ios::trunc);
    if (!writer.is_open()) {
        throw std::runtime_error("Failed to open knowhere manifest for write: " + manifest_path.string());
    }
    writer << manifest.dump();
    if (!writer.good()) {
        throw std::runtime_error("Failed to write knowhere manifest: " + manifest_path.string());
    }
}

ManifestInfo
ReadManifest(const std::filesystem::path& manifest_path) {
    std::ifstream reader(manifest_path, std::ios::binary);
    if (!reader.is_open()) {
        throw std::runtime_error("Failed to open knowhere manifest: " + manifest_path.string());
    }

    knowhere::Json manifest;
    reader >> manifest;

    ManifestInfo info;
    info.index_prefix = manifest.at("index_prefix").get<std::string>();
    if (manifest.contains("files")) {
        for (const auto& file : manifest.at("files")) {
            info.files.push_back(file.at("name").get<std::string>());
        }
    }
    return info;
}

}  // namespace

void InitLibrary() {
    // Knowhere doesn't have a global init anymore in 2.x, but we keep it for consistency.
}

void CreateIndex(JNIUtilInterface* jniUtil, JNIEnv* env, jintArray idsJ, jlong vectorsAddressJ, jint dimJ, jobject outputJ, jobject parametersJ) {
    const int64_t rows = env->GetArrayLength(idsJ);
    const int64_t dim = dimJ;

    jint* ids = env->GetIntArrayElements(idsJ, nullptr);
    std::vector<int64_t> ids64(rows);
    for (int64_t i = 0; i < rows; ++i) {
        ids64[i] = ids[i];
    }
    env->ReleaseIntArrayElements(idsJ, ids, JNI_ABORT);

    if (vectorsAddressJ <= 0) {
        throw std::runtime_error("VectorsAddress cannot be less than or equal to 0");
    }

    auto* input_vectors = reinterpret_cast<std::vector<float>*>(vectorsAddressJ);
    if (input_vectors->empty()) {
        throw std::runtime_error("Number of vectors cannot be 0");
    }

    if (input_vectors->size() % static_cast<uint64_t>(dim) != 0) {
        throw std::runtime_error("Vector data size does not align with the provided dimension");
    }

    const int64_t num_vectors = static_cast<int64_t>(input_vectors->size() / static_cast<uint64_t>(dim));
    if (num_vectors != rows) {
        throw std::runtime_error("Number of IDs does not match number of vectors");
    }

    float* vectors = input_vectors->data();

    float min_value = std::numeric_limits<float>::infinity();
    float max_value = -std::numeric_limits<float>::infinity();
    size_t nan_count = 0;
    size_t inf_count = 0;
    const int64_t value_count = rows * dim;
    for (int64_t i = 0; i < value_count; ++i) {
        const float value = vectors[i];
        if (std::isnan(value)) {
            ++nan_count;
            continue;
        }
        if (!std::isfinite(value)) {
            ++inf_count;
            continue;
        }
        min_value = std::min(min_value, value);
        max_value = std::max(max_value, value);
    }

    auto dataset = knowhere::GenDataSet(rows, dim, vectors);
    dataset->SetIds(ids64.data());

    knowhere::Json json = MapToJson(jniUtil, env, parametersJ);
    json[knowhere::meta::METRIC_TYPE] = json.value(knowhere::meta::METRIC_TYPE, "L2");
    json[knowhere::meta::INDEX_TYPE] = knowhere::IndexEnum::INDEX_DISKANN;

    WrapperContext context = GetWrapperContext(jniUtil, env, outputJ, "org/opensearch/knn/index/store/IndexOutputWithBuffer");
    const auto local_data_dir = CreateUniqueTempDir("opensearch-knn-knowhere-build");
    TempDirGuard temp_dir_guard(local_data_dir);
    const std::string diskann_prefix_base = DeriveDiskannPrefixBase(context.file_name);
    const std::string local_index_prefix = (local_data_dir / diskann_prefix_base).string();

    json[knowhere::meta::INDEX_PREFIX] = local_index_prefix;
    json["data_path"] = GetRawDataPath(local_index_prefix);
    NormalizeDiskannBudgetParams(json, rows, dim);
    NormalizeDiskannThreadParams(json);

    WriteRawDataFile(json["data_path"].get<std::string>(), vectors, static_cast<uint32_t>(rows), static_cast<uint32_t>(dim));

    auto file_manager = CreateLocalFileManager(local_data_dir);

    auto diskann_index_pack = knowhere::Pack(std::static_pointer_cast<knowhere::FileManager>(file_manager));

    auto expected_index = knowhere::IndexFactory::Instance().Create<float>(knowhere::IndexEnum::INDEX_DISKANN, 1, diskann_index_pack);
    if (!expected_index.has_value()) {
        throw std::runtime_error("Failed to create knowhere index: " + knowhere::Status2String(expected_index.error()));
    }

    LOG(INFO) << "[KNN][KNOWHERE][CreateIndex] doc_id_range="
              << (rows > 0 ? std::to_string(ids64.front()) : std::string("empty"))
              << ".."
              << (rows > 0 ? std::to_string(ids64.back()) : std::string("empty"))
              << " first_vector_prefix="
              << (value_count > 0 ? std::to_string(vectors[0]) : std::string("empty")) << ","
              << (value_count > 1 ? std::to_string(vectors[1]) : std::string("empty")) << ","
              << (value_count > 2 ? std::to_string(vectors[2]) : std::string("empty")) << ","
              << (value_count > 3 ? std::to_string(vectors[3]) : std::string("empty"))
              << " vector_stats[min=" << min_value
              << ", max=" << max_value
              << ", nan_count=" << nan_count
              << ", inf_count=" << inf_count << "]";

    LOG(INFO) << "[KNN][KNOWHERE][CreateIndex] rows=" << rows
              << " dim=" << dim
              << " manifest_file=" << context.file_name
              << " local_data_dir=" << local_data_dir.string()
              << " index_prefix=" << local_index_prefix
              << " data_path=" << json["data_path"].get<std::string>()
              << " params=" << json.dump();

    auto index = expected_index.value();
    auto status = index.Build(*dataset, json);
    LOG(INFO) << "[KNN][KNOWHERE][CreateIndex] build_status=" << knowhere::Status2String(status);
    LOG(INFO) << "[KNN][KNOWHERE][CreateIndex] added_files_count=" << file_manager->AddedFiles().size();
    for (const auto& added_file : file_manager->AddedFiles()) {
        LOG(INFO) << "[KNN][KNOWHERE][CreateIndex] added_file=" << added_file;
    }

    knn_jni::commons::freeVectorData(vectorsAddressJ);

    if (status != knowhere::Status::success) {
        throw std::runtime_error("Failed to build knowhere index: " + knowhere::Status2String(status));
    }
    CopyLocalFilesToDirectory(jniUtil, env, context, local_data_dir, file_manager->AddedFiles());
    ReleaseWrapperContext(env, context);

    const auto manifest_path = local_data_dir / context.file_name;
    WriteManifest(manifest_path, diskann_prefix_base, file_manager->AddedFiles());
    std::ifstream manifest_input(manifest_path, std::ios::binary);
    if (!manifest_input.is_open()) {
        throw std::runtime_error("Failed to reopen knowhere manifest: " + manifest_path.string());
    }
    std::vector<uint8_t> manifest_bytes((std::istreambuf_iterator<char>(manifest_input)), std::istreambuf_iterator<char>());
    knn_jni::stream::NativeEngineIndexOutputMediator manifest_output(jniUtil, env, outputJ);
    if (!manifest_bytes.empty()) {
        manifest_output.writeBytes(manifest_bytes.data(), manifest_bytes.size());
    }
    manifest_output.flush();
}

jlong LoadIndex(JNIUtilInterface* jniUtil, JNIEnv* env, jobject readStreamJ, jobject parametersJ) {
    knowhere::Json json = MapToJson(jniUtil, env, parametersJ);
    json[knowhere::meta::METRIC_TYPE] = json.value(knowhere::meta::METRIC_TYPE, "L2");
    json[knowhere::meta::INDEX_TYPE] = knowhere::IndexEnum::INDEX_DISKANN;
    NormalizeDiskannBudgetParams(json, 0, 0);

    WrapperContext context = GetWrapperContext(jniUtil, env, readStreamJ, "org/opensearch/knn/index/store/IndexInputWithBuffer");
    const auto local_data_dir = CreateUniqueTempDir("opensearch-knn-knowhere-load");
    auto file_manager = CreateLocalFileManager(local_data_dir);

    LOG(INFO) << "[KNN][KNOWHERE][LoadIndex] manifest_file=" << context.file_name
              << " local_data_dir=" << local_data_dir.string()
              << " params_before_manifest=" << json.dump();

    CopyDirectoryFilesToLocal(jniUtil, env, context, local_data_dir, { context.file_name });

    const auto manifest = ReadManifest(local_data_dir / context.file_name);
    LOG(INFO) << "[KNN][KNOWHERE][LoadIndex] manifest_index_prefix=" << manifest.index_prefix
              << " manifest_files_count=" << manifest.files.size();
    for (const auto& manifest_file : manifest.files) {
        LOG(INFO) << "[KNN][KNOWHERE][LoadIndex] manifest_file_entry=" << manifest_file;
    }
    const bool use_compound_sidecars = HasSuffix(context.file_name, ".knowherec");
    CopyDirectoryFilesToLocal(jniUtil, env, context, local_data_dir, manifest.files, use_compound_sidecars);
    ReleaseWrapperContext(env, context);
    json[knowhere::meta::INDEX_PREFIX] = (local_data_dir / manifest.index_prefix).string();

    auto diskann_index_pack = knowhere::Pack(std::static_pointer_cast<knowhere::FileManager>(file_manager));
    auto expected_index = knowhere::IndexFactory::Instance().Create<float>(knowhere::IndexEnum::INDEX_DISKANN, 1, diskann_index_pack);
    if (!expected_index.has_value()) {
        throw std::runtime_error("Failed to create knowhere index for loading");
    }

    auto holder = std::make_unique<LoadedKnowhereIndex>();
    holder->file_manager = std::static_pointer_cast<knowhere::FileManager>(file_manager);
    holder->index = expected_index.value();
    holder->local_data_dir = local_data_dir;

    knowhere::BinarySet binary_set;
    LOG(INFO) << "[KNN][KNOWHERE][LoadIndex] params_after_manifest=" << json.dump();

    auto status = holder->index.Deserialize(binary_set, json);
    LOG(INFO) << "[KNN][KNOWHERE][LoadIndex] deserialize_status=" << knowhere::Status2String(status);
    if (status != knowhere::Status::success) {
        throw std::runtime_error("Failed to load knowhere index from manifest " + context.file_name + ": " + knowhere::Status2String(status));
    }

    return reinterpret_cast<jlong>(holder.release());
}

jobjectArray QueryIndex(JNIUtilInterface* jniUtil, JNIEnv* env, jlong indexPointerJ, jfloatArray queryVectorJ, jint kJ, jobject methodParamsJ) {
    auto holder = reinterpret_cast<LoadedKnowhereIndex*>(indexPointerJ);
    auto& index = holder->index;

    jfloat* queryVector = env->GetFloatArrayElements(queryVectorJ, nullptr);
    auto dataset = knowhere::GenDataSet(1, index.Dim(), queryVector);

    knowhere::Json json = MapToJson(jniUtil, env, methodParamsJ);
    json[knowhere::meta::TOPK] = static_cast<int>(kJ);
    json[knowhere::meta::METRIC_TYPE] = json.value(knowhere::meta::METRIC_TYPE, "L2");

    auto expected_res = index.Search(*dataset, json, knowhere::BitsetView());
    env->ReleaseFloatArrayElements(queryVectorJ, queryVector, JNI_ABORT);

    if (!expected_res.has_value()) {
        throw std::runtime_error("Failed to search knowhere index: " + knowhere::Status2String(expected_res.error()));
    }

    auto res = expected_res.value();
    const int64_t* ids = res->GetIds();
    const float* dists = res->GetDistance();
    int resultSize = static_cast<int>(res->GetDim());

    jclass resultClass = jniUtil->FindClass(env, "org/opensearch/knn/index/query/KNNQueryResult");
    jmethodID allArgs = jniUtil->FindMethod(env, "org/opensearch/knn/index/query/KNNQueryResult", "<init>");

    jobjectArray results = jniUtil->NewObjectArray(env, resultSize, resultClass, nullptr);

    for (int i = 0; i < resultSize; ++i) {
        jobject result = jniUtil->NewObject(env, resultClass, allArgs, static_cast<jint>(ids[i]), dists[i]);
        jniUtil->SetObjectArrayElement(env, results, i, result);
    }

    return results;
}

void Free(jlong indexPointerJ) {
    auto holder = std::unique_ptr<LoadedKnowhereIndex>(reinterpret_cast<LoadedKnowhereIndex*>(indexPointerJ));
    if (holder == nullptr) {
        return;
    }

    std::error_code ec;
    if (!holder->local_data_dir.empty()) {
        std::filesystem::remove_all(holder->local_data_dir, ec);
    }
}

}  // namespace knowhere_wrapper
}  // namespace knn_jni
