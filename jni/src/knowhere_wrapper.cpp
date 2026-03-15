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

#include <iostream>
#include <ostream>
#include <glog/export.h>

namespace google {
namespace logging {
namespace internal {
template <typename T>
GLOG_EXPORT void MakeCheckOpValueString(std::ostream* os, const T& v);
}
}
}

#include <glog/logging.h>

#include "knowhere_wrapper.h"

#include <vector>
#include <string>

#include "knowhere/index/index.h"
#include "knowhere/index/index_factory.h"
#include "knowhere/dataset.h"
#include "knowhere/comp/index_param.h"
#include "knowhere/expected.h"

namespace knn_jni {
namespace knowhere_wrapper {

// Map Java Map to knowhere::Json
static knowhere::Json MapToJson(JNIUtilInterface *jniUtil, JNIEnv *env, jobject parametersJ) {
    knowhere::Json json;
    if (parametersJ == nullptr) {
        return json;
    }

    auto paramsMap = jniUtil->ConvertJavaMapToCppMap(env, parametersJ);
    for (auto const& [key, valueJ] : paramsMap) {
        if (valueJ == nullptr) continue;
        std::string value = jniUtil->ConvertJavaObjectToCppString(env, valueJ);
        if (value.empty()) continue;

        try {
            if (key == knowhere::meta::DIM || key == knowhere::indexparam::MAX_DEGREE || 
                key == knowhere::indexparam::SEARCH_LIST_SIZE || key == knowhere::indexparam::BEAMWIDTH ||
                key == knowhere::indexparam::DISK_PQ_DIMS || key == knowhere::meta::TOPK) {
                json[key] = std::stoi(value);
            } else if (key == knowhere::indexparam::PQ_CODE_BUDGET_GB || key == knowhere::indexparam::BUILD_DRAM_BUDGET_GB ||
                       key == knowhere::indexparam::SEARCH_CACHE_BUDGET_GB) {
                json[key] = std::stof(value);
            } else {
                json[key] = value;
            }
        } catch (...) {
            json[key] = value;
        }
    }
    return json;
}

void InitLibrary() {
    // Knowhere doesn't have a global init anymore in 2.x, but we keep it for consistency
}

void CreateIndex(JNIUtilInterface *jniUtil, JNIEnv *env, jintArray idsJ, jlong vectorsAddressJ, jint dimJ, jstring indexPathJ, jobject parametersJ) {
    int64_t rows = env->GetArrayLength(idsJ);
    int64_t dim = dimJ;

    jint* ids = env->GetIntArrayElements(idsJ, nullptr);
    std::vector<int64_t> ids64(rows);
    for (int i = 0; i < rows; ++i) {
        ids64[i] = ids[i];
    }
    env->ReleaseIntArrayElements(idsJ, ids, JNI_ABORT);

    float* vectors = reinterpret_cast<float*>(vectorsAddressJ);

    auto dataset = knowhere::GenDataSet(rows, dim, vectors);
    dataset->SetIds(ids64.data());

    knowhere::Json json = MapToJson(jniUtil, env, parametersJ);
    
    // Set mandatory fields for DiskANN
    json[knowhere::meta::METRIC_TYPE] = json.value(knowhere::meta::METRIC_TYPE, "L2");
    json[knowhere::meta::INDEX_TYPE] = knowhere::IndexEnum::INDEX_DISKANN;
    
    std::string indexPath = jniUtil->ConvertJavaStringToCppString(env, indexPathJ);
    json[knowhere::meta::INDEX_PREFIX] = indexPath;

    auto expected_index = knowhere::IndexFactory::Instance().Create<knowhere::IndexNode>(knowhere::IndexEnum::INDEX_DISKANN, 1);
    if (!expected_index.has_value()) {
        throw std::runtime_error("Failed to create knowhere index: " + knowhere::Status2String(expected_index.error()));
    }
    auto index = expected_index.value();
    auto status = index.Build(dataset, json);
    if (status != knowhere::Status::success) {
        throw std::runtime_error("Failed to build knowhere index: " + knowhere::Status2String(status));
    }
}

jlong LoadIndex(JNIUtilInterface *jniUtil, JNIEnv *env, jstring indexPathJ, jobject parametersJ) {
    std::string indexPath = jniUtil->ConvertJavaStringToCppString(env, indexPathJ);
    knowhere::Json json = MapToJson(jniUtil, env, parametersJ);
    
    json[knowhere::meta::METRIC_TYPE] = json.value(knowhere::meta::METRIC_TYPE, "L2");
    json[knowhere::meta::INDEX_TYPE] = knowhere::IndexEnum::INDEX_DISKANN;
    
    auto expected_index = knowhere::IndexFactory::Instance().Create<knowhere::IndexNode>(knowhere::IndexEnum::INDEX_DISKANN, 1);
    if (!expected_index.has_value()) {
        throw std::runtime_error("Failed to create knowhere index for loading");
    }
    
    auto index_ptr = new knowhere::Index<knowhere::IndexNode>(expected_index.value());
    
    auto status = index_ptr->DeserializeFromFile(indexPath, json);
    if (status != knowhere::Status::success) {
        delete index_ptr;
        throw std::runtime_error("Failed to load knowhere index from " + indexPath + ": " + knowhere::Status2String(status));
    }

    return reinterpret_cast<jlong>(index_ptr);
}

jobjectArray QueryIndex(JNIUtilInterface *jniUtil, JNIEnv *env, jlong indexPointerJ, jfloatArray queryVectorJ, jint kJ, jobject methodParamsJ) {
    auto index_ptr = reinterpret_cast<knowhere::Index<knowhere::IndexNode>*>(indexPointerJ);
    
    jfloat* queryVector = env->GetFloatArrayElements(queryVectorJ, nullptr);
    auto dataset = knowhere::GenDataSet(1, index_ptr->Dim(), queryVector);
    
    knowhere::Json json = MapToJson(jniUtil, env, methodParamsJ);
    json[knowhere::meta::TOPK] = (int)kJ;

    auto expected_res = index_ptr->Search(dataset, json, nullptr);
    env->ReleaseFloatArrayElements(queryVectorJ, queryVector, JNI_ABORT);

    if (!expected_res.has_value()) {
        throw std::runtime_error("Failed to search knowhere index: " + knowhere::Status2String(expected_res.error()));
    }

    auto res = expected_res.value();
    const int64_t* ids = res->GetIds();
    const float* dists = res->GetDistance();
    int resultSize = (int)res->GetDim(); // for nq=1, dim is topk

    jclass resultClass = jniUtil->FindClass(env, "org/opensearch/knn/index/query/KNNQueryResult");
    jmethodID allArgs = jniUtil->FindMethod(env, "org/opensearch/knn/index/query/KNNQueryResult", "<init>");

    jobjectArray results = jniUtil->NewObjectArray(env, resultSize, resultClass, nullptr);

    for (int i = 0; i < resultSize; ++i) {
        jobject result = jniUtil->NewObject(env, resultClass, allArgs, (int)ids[i], dists[i]);
        jniUtil->SetObjectArrayElement(env, results, i, result);
        env->DeleteLocalRef(result);
    }
    return results;
}

void Free(jlong indexPointerJ) {
    auto index_ptr = reinterpret_cast<knowhere::Index<knowhere::IndexNode>*>(indexPointerJ);
    delete index_ptr;
}

} // namespace knowhere_wrapper
} // namespace knn_jni
