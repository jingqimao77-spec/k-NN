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

#include "org_opensearch_knn_jni_KnowhereService.h"

#include <jni.h>
#include "jni_util.h"
#include "knowhere_wrapper.h"

static knn_jni::JNIUtil jniUtil;
static const jint KNN_KNOWHERE_JNI_VERSION = JNI_VERSION_1_1;

jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env;
    if (vm->GetEnv((void **) &env, KNN_KNOWHERE_JNI_VERSION) != JNI_OK) {
        return JNI_ERR;
    }

    jniUtil.Initialize(env);

    return KNN_KNOWHERE_JNI_VERSION;
}

void JNI_OnUnload(JavaVM *vm, void *reserved) {
    JNIEnv *env;
    vm->GetEnv((void **) &env, KNN_KNOWHERE_JNI_VERSION);
    jniUtil.Uninitialize(env);
}

JNIEXPORT void JNICALL Java_org_opensearch_knn_jni_KnowhereService_initLibrary(JNIEnv *env, jclass cls) {
    try {
        knn_jni::knowhere_wrapper::InitLibrary();
    } catch (...) {
        jniUtil.CatchCppExceptionAndThrowJava(env);
    }
}

JNIEXPORT void JNICALL Java_org_opensearch_knn_jni_KnowhereService_createIndex(JNIEnv *env, jclass cls, jintArray idsJ, jlong vectorsAddressJ, jint dimJ, jstring indexPathJ, jobject parametersJ) {
    try {
        knn_jni::knowhere_wrapper::CreateIndex(&jniUtil, env, idsJ, vectorsAddressJ, dimJ, indexPathJ, parametersJ);
    } catch (...) {
        jniUtil.CatchCppExceptionAndThrowJava(env);
    }
}

JNIEXPORT jlong JNICALL Java_org_opensearch_knn_jni_KnowhereService_loadIndex(JNIEnv *env, jclass cls, jstring indexPathJ, jobject parametersJ) {
    try {
        return knn_jni::knowhere_wrapper::LoadIndex(&jniUtil, env, indexPathJ, parametersJ);
    } catch (...) {
        jniUtil.CatchCppExceptionAndThrowJava(env);
    }
    return 0;
}

JNIEXPORT jobjectArray JNICALL Java_org_opensearch_knn_jni_KnowhereService_queryIndex(JNIEnv *env, jclass cls, jlong indexPointerJ, jfloatArray queryVectorJ, jint kJ, jobject methodParamsJ) {
    try {
        return knn_jni::knowhere_wrapper::QueryIndex(&jniUtil, env, indexPointerJ, queryVectorJ, kJ, methodParamsJ);
    } catch (...) {
        jniUtil.CatchCppExceptionAndThrowJava(env);
    }
    return nullptr;
}

JNIEXPORT void JNICALL Java_org_opensearch_knn_jni_KnowhereService_free(JNIEnv *env, jclass cls, jlong indexPointerJ) {
    try {
        knn_jni::knowhere_wrapper::Free(indexPointerJ);
    } catch (...) {
        jniUtil.CatchCppExceptionAndThrowJava(env);
    }
}
