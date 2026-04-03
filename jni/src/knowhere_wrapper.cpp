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
#include <cstdint>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <limits>
#include <memory>
#include <ostream>
#include <shared_mutex>
#include <stdexcept>
#include <string>
#include <system_error>
#include <unordered_map>
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
#include "knowhere_grouping_util.h"
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
    std::vector<int64_t> internal_to_external;
    std::unordered_map<int64_t, int64_t> external_to_internal;
    std::shared_ptr<GroupingCache> grouping_cache;
    mutable std::shared_mutex grouping_cache_mutex;
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

struct FilterState {
    std::vector<uint8_t> excluded_bits;
    size_t num_bits = 0;
    size_t filtered_out_count = 0;
    size_t allowed_count = 0;

    knowhere::BitsetView
    View() const {
        if (excluded_bits.empty()) {
            return knowhere::BitsetView();
        }
        return knowhere::BitsetView(excluded_bits.data(), num_bits, filtered_out_count);
    }
};

struct SearchResultEntry {
    jint id;
    float distance;
};

constexpr jint kFilterTypeBitmap = 0;
constexpr jint kFilterTypeBatch = 1;
constexpr const char* kDocIdsSuffix = ".docids.bin";
constexpr float kBytesPerGiB = 1024.0f * 1024.0f * 1024.0f;

std::string
GetRawDataPath(const std::string& index_prefix) {
    return index_prefix + ".raw_data";
}

std::string
GetDocIdsPath(const std::string& index_prefix) {
    return index_prefix + kDocIdsSuffix;
}

std::vector<std::string>
GetAdditionalDiskannSidecarPaths(const std::string& index_prefix) {
    return {
        index_prefix + "_disk.index_pq_pivots.bin",
        index_prefix + "_disk.index_pq_pivots.bin_rearrangement_perm.bin",
        index_prefix + "_disk.index_pq_pivots.bin_chunk_offsets.bin",
        index_prefix + "_disk.index_pq_pivots.bin_centroid.bin",
        index_prefix + "_disk.index_pq_compressed.bin",
    };
}

void
RegisterOptionalBuildArtifact(knowhere::FileManager& file_manager, const std::string& path) {
    if (!std::filesystem::exists(path)) {
        return;
    }
    if (!file_manager.AddFile(path)) {
        throw std::runtime_error("Failed to register knowhere optional artifact with file manager: " + path);
    }
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

void
WriteDocIdsFile(const std::string& path, const std::vector<int64_t>& ids) {
    std::ofstream writer(path, std::ios::binary | std::ios::trunc);
    if (!writer.is_open()) {
        throw std::runtime_error("Failed to open knowhere doc ids sidecar for write: " + path);
    }

    const uint64_t count = ids.size();
    writer.write(reinterpret_cast<const char*>(&count), sizeof(count));
    if (!ids.empty()) {
        writer.write(reinterpret_cast<const char*>(ids.data()), static_cast<std::streamsize>(ids.size() * sizeof(int64_t)));
    }

    if (!writer.good()) {
        throw std::runtime_error("Failed to write knowhere doc ids sidecar: " + path);
    }
}

std::vector<int64_t>
ReadDocIdsFile(const std::string& path) {
    std::ifstream reader(path, std::ios::binary);
    if (!reader.is_open()) {
        throw std::runtime_error("Failed to open knowhere doc ids sidecar: " + path);
    }

    uint64_t count = 0;
    reader.read(reinterpret_cast<char*>(&count), sizeof(count));
    if (!reader.good() && !reader.eof()) {
        throw std::runtime_error("Failed to read knowhere doc ids sidecar header: " + path);
    }

    std::vector<int64_t> ids(count);
    if (count > 0) {
        reader.read(reinterpret_cast<char*>(ids.data()), static_cast<std::streamsize>(count * sizeof(int64_t)));
        if (!reader) {
            throw std::runtime_error("Failed to read knowhere doc ids sidecar payload: " + path);
        }
    }

    char trailing = 0;
    if (reader.read(&trailing, 1)) {
        throw std::runtime_error("Knowhere doc ids sidecar has trailing data: " + path);
    }

    return ids;
}

std::unordered_map<int64_t, int64_t>
BuildExternalToInternalMap(const std::vector<int64_t>& internal_to_external) {
    std::unordered_map<int64_t, int64_t> external_to_internal;
    external_to_internal.reserve(internal_to_external.size());

    for (size_t internal_id = 0; internal_id < internal_to_external.size(); ++internal_id) {
        const auto external_id = internal_to_external[internal_id];
        auto [it, inserted] = external_to_internal.emplace(external_id, static_cast<int64_t>(internal_id));
        if (!inserted) {
            throw std::runtime_error("Duplicate external doc id in knowhere doc ids sidecar: " + std::to_string(external_id));
        }
    }

    return external_to_internal;
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
            } else if (key == "search_list") {
                json["search_list_size"] = std::stoi(value);
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

void
ReleaseWrapperContext(JNIEnv* env, const WrapperContext& context) {
    env->DeleteLocalRef(context.directory);
    env->DeleteLocalRef(context.io_context);
}

bool
HasSuffix(const std::string& value, const std::string& suffix) {
    return value.size() >= suffix.size() && value.compare(value.size() - suffix.size(), suffix.size(), suffix) == 0;
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

void
SetBit(std::vector<uint8_t>& bits, size_t bit_index) {
    bits[bit_index >> 3] |= static_cast<uint8_t>(1U << (bit_index & 0x7));
}

void
ClearBit(std::vector<uint8_t>& bits, size_t bit_index) {
    bits[bit_index >> 3] &= static_cast<uint8_t>(~(1U << (bit_index & 0x7)));
}

bool
TestBit(const std::vector<uint8_t>& bits, size_t bit_index) {
    return (bits[bit_index >> 3] & static_cast<uint8_t>(1U << (bit_index & 0x7))) != 0;
}

bool
IsBitmapBitSet(const jlong* words, size_t word_count, int64_t bit_index) {
    if (words == nullptr || bit_index < 0) {
        return false;
    }

    const size_t normalized_index = static_cast<size_t>(bit_index);
    const size_t word_index = normalized_index / 64;
    if (word_index >= word_count) {
        return false;
    }

    const uint64_t word = static_cast<uint64_t>(words[word_index]);
    return (word & (uint64_t{1} << (normalized_index % 64))) != 0;
}

FilterState
BuildFilterState(const LoadedKnowhereIndex& holder, const jlong* filtered_ids, size_t filtered_ids_length, jint filter_ids_type) {
    FilterState state;
    state.num_bits = holder.internal_to_external.size();
    state.allowed_count = state.num_bits;

    if (filtered_ids == nullptr) {
        return state;
    }

    if (state.num_bits == 0) {
        state.allowed_count = 0;
        return state;
    }

    const size_t num_bytes = (state.num_bits + 7) >> 3;
    if (filter_ids_type == kFilterTypeBitmap) {
        state.excluded_bits.assign(num_bytes, 0);
        state.allowed_count = 0;
        for (size_t internal_id = 0; internal_id < state.num_bits; ++internal_id) {
            const int64_t external_id = holder.internal_to_external[internal_id];
            if (IsBitmapBitSet(filtered_ids, filtered_ids_length, external_id)) {
                ++state.allowed_count;
                continue;
            }
            SetBit(state.excluded_bits, internal_id);
            ++state.filtered_out_count;
        }
    } else if (filter_ids_type == kFilterTypeBatch) {
        state.excluded_bits.assign(num_bytes, 0xFF);
        state.allowed_count = 0;
        for (size_t idx = 0; idx < filtered_ids_length; ++idx) {
            const auto it = holder.external_to_internal.find(filtered_ids[idx]);
            if (it == holder.external_to_internal.end()) {
                continue;
            }
            const size_t internal_id = static_cast<size_t>(it->second);
            if (!TestBit(state.excluded_bits, internal_id)) {
                continue;
            }
            ClearBit(state.excluded_bits, internal_id);
            ++state.allowed_count;
        }
        state.filtered_out_count = state.num_bits - state.allowed_count;
    } else {
        throw std::runtime_error("Unsupported knowhere filter ids type: " + std::to_string(filter_ids_type));
    }

    if (state.filtered_out_count == 0) {
        state.excluded_bits.clear();
    }
    return state;
}

jint
ToJIntId(int64_t id, const std::string& description) {
    if (id < std::numeric_limits<jint>::min() || id > std::numeric_limits<jint>::max()) {
        throw std::runtime_error("Knowhere " + description + " exceeds jint range: " + std::to_string(id));
    }
    return static_cast<jint>(id);
}

std::shared_ptr<GroupingCache>
BuildOrGetGroupingCache(LoadedKnowhereIndex& holder, const std::vector<int64_t>& parent_ids_sorted) {
    const uint64_t cache_key = HashParentIds(parent_ids_sorted);
    {
        std::shared_lock<std::shared_mutex> lock(holder.grouping_cache_mutex);
        if (
            holder.grouping_cache != nullptr && holder.grouping_cache->cache_key == cache_key
            && holder.grouping_cache->parent_segment_doc_ids == parent_ids_sorted
        ) {
            return holder.grouping_cache;
        }
    }

    auto rebuilt_cache = std::make_shared<GroupingCache>(BuildGroupingCache(holder.internal_to_external, parent_ids_sorted));
    {
        std::unique_lock<std::shared_mutex> lock(holder.grouping_cache_mutex);
        if (
            holder.grouping_cache != nullptr && holder.grouping_cache->cache_key == cache_key
            && holder.grouping_cache->parent_segment_doc_ids == parent_ids_sorted
        ) {
            return holder.grouping_cache;
        }
        holder.grouping_cache = rebuilt_cache;
        return holder.grouping_cache;
    }
}

std::vector<SearchResultEntry>
TranslateSearchResults(const LoadedKnowhereIndex& holder,
                       const int64_t* ids,
                       const float* dists,
                       int raw_result_size,
                       jint requested_k) {
    std::vector<SearchResultEntry> results;
    if (ids == nullptr || dists == nullptr || raw_result_size <= 0) {
        return results;
    }

    const size_t max_results = static_cast<size_t>(requested_k);
    results.reserve(std::min(static_cast<size_t>(raw_result_size), max_results));
    for (int i = 0; i < raw_result_size; ++i) {
        if (ids[i] == -1) {
            break;
        }
        const auto internal_id = static_cast<size_t>(ids[i]);
        if (internal_id >= holder.internal_to_external.size()) {
            throw std::runtime_error("Knowhere returned an internal id outside the loaded doc id map: " + std::to_string(ids[i]));
        }
        results.push_back({ ToJIntId(holder.internal_to_external[internal_id], "document id"), dists[i] });
        if (results.size() >= max_results) {
            break;
        }
    }
    return results;
}

jobjectArray
BuildQueryResultsArray(JNIUtilInterface* jniUtil, JNIEnv* env, const std::vector<SearchResultEntry>& entries) {
    jclass resultClass = jniUtil->FindClass(env, "org/opensearch/knn/index/query/KNNQueryResult");
    jmethodID allArgs = jniUtil->FindMethod(env, "org/opensearch/knn/index/query/KNNQueryResult", "<init>");

    jobjectArray results = jniUtil->NewObjectArray(env, entries.size(), resultClass, nullptr);
    for (size_t i = 0; i < entries.size(); ++i) {
        jobject result = jniUtil->NewObject(env, resultClass, allArgs, entries[i].id, entries[i].distance);
        jniUtil->SetObjectArrayElement(env, results, static_cast<jsize>(i), result);
        env->DeleteLocalRef(result);
    }
    return results;
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

    auto file_manager = std::make_shared<OpenSearchFileManager>(
        jniUtil, env, context.directory, context.io_context, local_data_dir.string());

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

    knn_jni::commons::freeVectorData(vectorsAddressJ);

    if (status != knowhere::Status::success) {
        throw std::runtime_error("Failed to build knowhere index: " + knowhere::Status2String(status));
    }

    const std::string doc_ids_path = GetDocIdsPath(local_index_prefix);
    WriteDocIdsFile(doc_ids_path, ids64);
    if (!file_manager->AddFile(doc_ids_path)) {
        throw std::runtime_error("Failed to register knowhere doc ids sidecar with file manager: " + doc_ids_path);
    }
    for (const auto& optional_path : GetAdditionalDiskannSidecarPaths(local_index_prefix)) {
        RegisterOptionalBuildArtifact(*file_manager, optional_path);
    }

    LOG(INFO) << "[KNN][KNOWHERE][CreateIndex] added_files_count=" << file_manager->AddedFiles().size();
    for (const auto& added_file : file_manager->AddedFiles()) {
        LOG(INFO) << "[KNN][KNOWHERE][CreateIndex] added_file=" << added_file;
    }

    if (!file_manager->SyncTrackedFilesToDirectory()) {
        throw std::runtime_error("Failed to copy knowhere artifacts into Lucene directory");
    }
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
    auto file_manager = std::make_shared<OpenSearchFileManager>(
        jniUtil, env, context.directory, context.io_context, local_data_dir.string());

    LOG(INFO) << "[KNN][KNOWHERE][LoadIndex] manifest_file=" << context.file_name
              << " local_data_dir=" << local_data_dir.string()
              << " params_before_manifest=" << json.dump();

    if (!file_manager->MaterializeFilesFromDirectory({ context.file_name })) {
        throw std::runtime_error("Failed to copy knowhere manifest from Lucene directory: " + context.file_name);
    }

    const auto manifest = ReadManifest(local_data_dir / context.file_name);
    LOG(INFO) << "[KNN][KNOWHERE][LoadIndex] manifest_index_prefix=" << manifest.index_prefix
              << " manifest_files_count=" << manifest.files.size();
    for (const auto& manifest_file : manifest.files) {
        LOG(INFO) << "[KNN][KNOWHERE][LoadIndex] manifest_file_entry=" << manifest_file;
    }

    const std::string required_doc_ids_file = std::filesystem::path(GetDocIdsPath(manifest.index_prefix)).filename().string();
    if (std::find(manifest.files.begin(), manifest.files.end(), required_doc_ids_file) == manifest.files.end()) {
        throw std::runtime_error("Knowhere manifest is missing required doc ids sidecar: " + required_doc_ids_file);
    }

    const bool use_compound_sidecars = HasSuffix(context.file_name, ".knowherec");
    if (!file_manager->MaterializeFilesFromDirectory(manifest.files, use_compound_sidecars)) {
        throw std::runtime_error("Failed to copy knowhere artifacts from Lucene directory for manifest " + context.file_name);
    }
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
    holder->internal_to_external = ReadDocIdsFile((local_data_dir / required_doc_ids_file).string());
    holder->external_to_internal = BuildExternalToInternalMap(holder->internal_to_external);

    knowhere::BinarySet binary_set;
    LOG(INFO) << "[KNN][KNOWHERE][LoadIndex] params_after_manifest=" << json.dump();

    auto status = holder->index.Deserialize(binary_set, json);
    LOG(INFO) << "[KNN][KNOWHERE][LoadIndex] deserialize_status=" << knowhere::Status2String(status);
    if (status != knowhere::Status::success) {
        throw std::runtime_error("Failed to load knowhere index from manifest " + context.file_name + ": " + knowhere::Status2String(status));
    }

    const int64_t vector_count = holder->index.Count();
    if (vector_count != static_cast<int64_t>(holder->internal_to_external.size())) {
        throw std::runtime_error(
            "Knowhere loaded vector count does not match doc ids sidecar: count=" + std::to_string(vector_count)
                + " sidecar=" + std::to_string(holder->internal_to_external.size())
        );
    }

    return reinterpret_cast<jlong>(holder.release());
}

jobjectArray QueryIndex(
    JNIUtilInterface* jniUtil,
    JNIEnv* env,
    jlong indexPointerJ,
    jfloatArray queryVectorJ,
    jint kJ,
    jobject methodParamsJ,
    jlongArray filterIdsJ,
    jint filterIdsTypeJ,
    jintArray parentIdsJ
) {
    if (queryVectorJ == nullptr) {
        throw std::runtime_error("Query Vector cannot be null");
    }

    auto holder = reinterpret_cast<LoadedKnowhereIndex*>(indexPointerJ);
    if (holder == nullptr) {
        throw std::runtime_error("Invalid pointer to index");
    }

    if (kJ <= 0) {
        return BuildQueryResultsArray(jniUtil, env, {});
    }

    jfloat* queryVector = jniUtil->GetFloatArrayElements(env, queryVectorJ, nullptr);
    JNIReleaseElements release_query([&]() { jniUtil->ReleaseFloatArrayElements(env, queryVectorJ, queryVector, JNI_ABORT); });
    auto dataset = knowhere::GenDataSet(1, holder->index.Dim(), queryVector);

    knowhere::Json json = MapToJson(jniUtil, env, methodParamsJ);
    json[knowhere::meta::TOPK] = static_cast<int>(kJ);
    json[knowhere::meta::METRIC_TYPE] = json.value(knowhere::meta::METRIC_TYPE, "L2");

    std::vector<int64_t> parent_ids;
    std::shared_ptr<GroupingCache> grouping_cache;
    if (parentIdsJ != nullptr) {
        parent_ids = jniUtil->ConvertJavaIntArrayToCppIntVector(env, parentIdsJ);
        if (!std::is_sorted(parent_ids.begin(), parent_ids.end())) {
            throw std::runtime_error("Knowhere parent ids must be sorted");
        }
        if (!parent_ids.empty()) {
            grouping_cache = BuildOrGetGroupingCache(*holder, parent_ids);
            dataset->Set<uint64_t>(
                kGroupSearchInternalToParentOrdAddressKey,
                static_cast<uint64_t>(reinterpret_cast<std::uintptr_t>(grouping_cache->internal_to_parent_ord.data()))
            );
            dataset->Set<uint64_t>(kGroupSearchParentCountKey, static_cast<uint64_t>(grouping_cache->parent_segment_doc_ids.size()));
        }
    }

    jlong* filtered_ids = nullptr;
    std::unique_ptr<JNIReleaseElements> release_filter_ids;
    size_t filtered_ids_length = 0;
    if (filterIdsJ != nullptr) {
        filtered_ids = jniUtil->GetLongArrayElements(env, filterIdsJ, nullptr);
        release_filter_ids = std::make_unique<JNIReleaseElements>([&]() {
            jniUtil->ReleaseLongArrayElements(env, filterIdsJ, filtered_ids, JNI_ABORT);
        });
        filtered_ids_length = static_cast<size_t>(jniUtil->GetJavaLongArrayLength(env, filterIdsJ));
    }

    const FilterState filter_state = BuildFilterState(*holder, filtered_ids, filtered_ids_length, filterIdsTypeJ);
    if (filterIdsJ != nullptr && filter_state.allowed_count == 0) {
        return BuildQueryResultsArray(jniUtil, env, {});
    }

    auto expected_res = holder->index.Search(*dataset, json, filter_state.View());
    if (!expected_res.has_value()) {
        throw std::runtime_error("Failed to search knowhere index: " + knowhere::Status2String(expected_res.error()));
    }

    auto res = expected_res.value();
    auto translated = TranslateSearchResults(
        *holder,
        res->GetIds(),
        res->GetDistance(),
        static_cast<int>(res->GetDim()),
        kJ
    );
    return BuildQueryResultsArray(jniUtil, env, translated);
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
