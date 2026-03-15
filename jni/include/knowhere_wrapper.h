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

#ifndef _Included_knowhere_wrapper
#define _Included_knowhere_wrapper

#include <jni.h>

#include "jni_util.h"
#include "org_opensearch_knn_jni_KnowhereService.h"

namespace knn_jni {
namespace knowhere_wrapper {

void InitLibrary();

void CreateIndex(JNIUtilInterface *jniUtil, JNIEnv *env, jintArray idsJ, jlong vectorsAddressJ, jint dimJ, jstring indexPathJ, jobject parametersJ);

jlong LoadIndex(JNIUtilInterface *jniUtil, JNIEnv *env, jstring indexPathJ, jobject parametersJ);

jobjectArray QueryIndex(JNIUtilInterface *jniUtil, JNIEnv *env, jlong indexPointerJ, jfloatArray queryVectorJ, jint kJ, jobject methodParamsJ);

void Free(jlong indexPointerJ);

} // namespace knowhere_wrapper
} // namespace knn_jni

#endif
