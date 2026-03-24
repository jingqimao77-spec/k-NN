/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

#include "opensearch_file_manager.h"

#include <algorithm>
#include <filesystem>
#include <fstream>
#include <stdexcept>
#include <vector>

namespace knn_jni {

OpenSearchFileManager::OpenSearchFileManager(JNIUtilInterface *jni_interface,
                                             JNIEnv *env,
                                             jobject directory,
                                             jobject io_context,
                                             const std::string &local_data_path)
    : jni_interface_(jni_interface),
      jvm_(nullptr),
      directory_global_(nullptr),
      io_context_global_(nullptr),
      local_data_path_(local_data_path) {
  if (jni_interface_ == nullptr || env == nullptr || directory == nullptr || io_context == nullptr) {
    throw std::invalid_argument("OpenSearchFileManager requires non-null JNI parameters");
  }
  env->GetJavaVM(&jvm_);
  directory_global_ = env->NewGlobalRef(directory);
  io_context_global_ = env->NewGlobalRef(io_context);
}

OpenSearchFileManager::~OpenSearchFileManager() {
  JNIEnv *env = GetEnv();
  if (env == nullptr) {
    return;
  }
  if (directory_global_ != nullptr) {
    env->DeleteGlobalRef(directory_global_);
    directory_global_ = nullptr;
  }
  if (io_context_global_ != nullptr) {
    env->DeleteGlobalRef(io_context_global_);
    io_context_global_ = nullptr;
  }
}

JNIEnv *OpenSearchFileManager::GetEnv() {
  if (jvm_ == nullptr) {
    return nullptr;
  }
  JNIEnv *env = nullptr;
  jint result = jvm_->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_1);
  if (result == JNI_OK) {
    return env;
  }
  if (result == JNI_EDETACHED) {
    if (jvm_->AttachCurrentThread(reinterpret_cast<void **>(&env), nullptr) == JNI_OK) {
      return env;
    }
  }
  return nullptr;
}

bool OpenSearchFileManager::AddFile(const std::string &filename) {
  JNIEnv *env = GetEnv();
  if (env == nullptr) {
    return false;
  }
  try {
    const std::string local_path = GetLocalFilePath(filename);
    if (!std::filesystem::exists(local_path)) {
      return false;
    }
    const std::string lucene_file = GetLuceneFileName(filename);
    std::ifstream input(local_path, std::ios::binary);
    if (!input.is_open()) {
      return false;
    }

    jobject index_output = CreateIndexOutput(lucene_file);
    if (index_output == nullptr) {
      return false;
    }

    jclass output_with_buffer_class = GetIndexOutputWithBufferClass(jni_interface_, env);
    jmethodID output_with_buffer_ctor = GetIndexOutputWithBufferCtor(jni_interface_, env);
    jobject output_with_buffer = env->NewObject(output_with_buffer_class, output_with_buffer_ctor, index_output);
    jni_interface_->HasExceptionInStack(env, "Failed to create IndexOutputWithBuffer");

    knn_jni::stream::NativeEngineIndexOutputMediator mediator(jni_interface_, env, output_with_buffer);
    std::vector<uint8_t> buffer(64 * 1024);
    while (input.good()) {
      input.read(reinterpret_cast<char *>(buffer.data()), buffer.size());
      std::streamsize read_bytes = input.gcount();
      if (read_bytes > 0) {
        mediator.writeBytes(buffer.data(), static_cast<size_t>(read_bytes));
      }
    }
    mediator.flush();

    CloseCloseable(index_output);
    env->DeleteLocalRef(index_output);
    env->DeleteLocalRef(output_with_buffer);
    return true;
  } catch (...) {
    jni_interface_->CatchCppExceptionAndThrowJava(env);
    return false;
  }
}

bool OpenSearchFileManager::LoadFile(const std::string &filename) {
  JNIEnv *env = GetEnv();
  if (env == nullptr) {
    return false;
  }
  try {
    const std::string local_path = GetLocalFilePath(filename);
    if (std::filesystem::exists(local_path)) {
      return true;
    }
    const std::string lucene_file = GetLuceneFileName(filename);
    std::ofstream output(local_path, std::ios::binary | std::ios::trunc);
    if (!output.is_open()) {
      return false;
    }

    jobject index_input = OpenIndexInput(lucene_file);
    if (index_input == nullptr) {
      return false;
    }

    jclass input_with_buffer_class = GetIndexInputWithBufferClass(jni_interface_, env);
    jmethodID input_with_buffer_ctor = GetIndexInputWithBufferCtor(jni_interface_, env);
    jobject input_with_buffer = env->NewObject(input_with_buffer_class, input_with_buffer_ctor, index_input);
    jni_interface_->HasExceptionInStack(env, "Failed to create IndexInputWithBuffer");

    knn_jni::stream::NativeEngineIndexInputMediator mediator(jni_interface_, env, input_with_buffer);
    std::vector<uint8_t> buffer(64 * 1024);
    while (true) {
      int64_t remaining = mediator.remainingBytes();
      if (remaining <= 0) {
        break;
      }
      const int64_t to_read = std::min<int64_t>(remaining, buffer.size());
      mediator.copyBytes(to_read, buffer.data());
      output.write(reinterpret_cast<const char *>(buffer.data()), to_read);
    }

    CloseCloseable(index_input);
    env->DeleteLocalRef(index_input);
    env->DeleteLocalRef(input_with_buffer);
    return true;
  } catch (...) {
    jni_interface_->CatchCppExceptionAndThrowJava(env);
    return false;
  }
}

std::optional<bool> OpenSearchFileManager::IsExisted(const std::string &filename) {
  JNIEnv *env = GetEnv();
  if (env == nullptr) {
    return std::nullopt;
  }
  try {
    const std::string lucene_file = GetLuceneFileName(filename);
  jmethodID list_all_method = GetDirectoryListAllMethod(jni_interface_, env);
    jobjectArray files_array = (jobjectArray) env->CallObjectMethod(directory_global_, list_all_method);
    jni_interface_->HasExceptionInStack(env, "Failed to list directory files");

    jsize length = env->GetArrayLength(files_array);
    for (jsize i = 0; i < length; i++) {
      jstring file_j = (jstring) env->GetObjectArrayElement(files_array, i);
      std::string file_name = jni_interface_->ConvertJavaStringToCppString(env, file_j);
      env->DeleteLocalRef(file_j);
      if (file_name == lucene_file) {
        env->DeleteLocalRef(files_array);
        return true;
      }
    }
    env->DeleteLocalRef(files_array);
    return false;
  } catch (...) {
    jni_interface_->CatchCppExceptionAndThrowJava(env);
    return std::nullopt;
  }
}

bool OpenSearchFileManager::RemoveFile(const std::string &filename) {
  JNIEnv *env = GetEnv();
  if (env == nullptr) {
    return false;
  }
  try {
    const std::string lucene_file = GetLuceneFileName(filename);
    jstring file_j = env->NewStringUTF(lucene_file.c_str());
    jmethodID delete_method = GetDirectoryDeleteFileMethod(jni_interface_, env);
    env->CallVoidMethod(directory_global_, delete_method, file_j);
    jni_interface_->HasExceptionInStack(env, "Failed to delete file from directory");
    env->DeleteLocalRef(file_j);
    return true;
  } catch (...) {
    jni_interface_->CatchCppExceptionAndThrowJava(env);
    return false;
  }
}

jobject OpenSearchFileManager::OpenIndexInput(const std::string &lucene_file_name) {
  JNIEnv *env = GetEnv();
  if (env == nullptr) {
    return nullptr;
  }
  jstring file_j = env->NewStringUTF(lucene_file_name.c_str());
  jmethodID method = GetDirectoryOpenInputMethod(jni_interface_, env);
  jobject index_input = env->CallObjectMethod(directory_global_, method, file_j, io_context_global_);
  jni_interface_->HasExceptionInStack(env, "Failed to open IndexInput");
  env->DeleteLocalRef(file_j);
  return index_input;
}

jobject OpenSearchFileManager::CreateIndexOutput(const std::string &lucene_file_name) {
  JNIEnv *env = GetEnv();
  if (env == nullptr) {
    return nullptr;
  }
  jstring file_j = env->NewStringUTF(lucene_file_name.c_str());
  jmethodID method = GetDirectoryCreateOutputMethod(jni_interface_, env);
  jobject index_output = env->CallObjectMethod(directory_global_, method, file_j, io_context_global_);
  jni_interface_->HasExceptionInStack(env, "Failed to create IndexOutput");
  env->DeleteLocalRef(file_j);
  return index_output;
}

void OpenSearchFileManager::CloseCloseable(jobject closeable) {
  JNIEnv *env = GetEnv();
  if (env == nullptr || closeable == nullptr) {
    return;
  }
  jmethodID close_method = GetCloseMethod(jni_interface_, env);
  env->CallVoidMethod(closeable, close_method);
  jni_interface_->HasExceptionInStack(env, "Failed to close Closeable");
}

std::string OpenSearchFileManager::GetLuceneFileName(const std::string &filename) const {
  std::filesystem::path path(filename);
  return path.filename().string();
}

std::string OpenSearchFileManager::GetLocalFilePath(const std::string &filename) const {
  std::filesystem::path path(filename);
  if (path.is_absolute()) {
    return path.string();
  }
  std::filesystem::path base(local_data_path_);
  return (base / path).string();
}

jclass OpenSearchFileManager::GetDirectoryClass(JNIUtilInterface *jni_interface, JNIEnv *env) {
  static jclass DIRECTORY_CLASS =
      jni_interface->FindClassFromJNIEnv(env, "org/apache/lucene/store/Directory");
  return DIRECTORY_CLASS;
}

jclass OpenSearchFileManager::GetIndexInputClass(JNIUtilInterface *jni_interface, JNIEnv *env) {
  static jclass INDEX_INPUT_CLASS =
      jni_interface->FindClassFromJNIEnv(env, "org/apache/lucene/store/IndexInput");
  return INDEX_INPUT_CLASS;
}

jclass OpenSearchFileManager::GetIndexOutputClass(JNIUtilInterface *jni_interface, JNIEnv *env) {
  static jclass INDEX_OUTPUT_CLASS =
      jni_interface->FindClassFromJNIEnv(env, "org/apache/lucene/store/IndexOutput");
  return INDEX_OUTPUT_CLASS;
}

jclass OpenSearchFileManager::GetIndexInputWithBufferClass(JNIUtilInterface *jni_interface, JNIEnv *env) {
  static jclass INDEX_INPUT_WITH_BUFFER_CLASS =
      jni_interface->FindClassFromJNIEnv(env, "org/opensearch/knn/index/store/IndexInputWithBuffer");
  return INDEX_INPUT_WITH_BUFFER_CLASS;
}

jclass OpenSearchFileManager::GetIndexOutputWithBufferClass(JNIUtilInterface *jni_interface, JNIEnv *env) {
  static jclass INDEX_OUTPUT_WITH_BUFFER_CLASS =
      jni_interface->FindClassFromJNIEnv(env, "org/opensearch/knn/index/store/IndexOutputWithBuffer");
  return INDEX_OUTPUT_WITH_BUFFER_CLASS;
}

jmethodID OpenSearchFileManager::GetDirectoryOpenInputMethod(JNIUtilInterface *jni_interface, JNIEnv *env) {
  static jmethodID OPEN_INPUT_METHOD =
      jni_interface->GetMethodID(env, GetDirectoryClass(jni_interface, env), "openInput",
                                 "(Ljava/lang/String;Lorg/apache/lucene/store/IOContext;)Lorg/apache/lucene/store/IndexInput;");
  return OPEN_INPUT_METHOD;
}

jmethodID OpenSearchFileManager::GetDirectoryCreateOutputMethod(JNIUtilInterface *jni_interface, JNIEnv *env) {
  static jmethodID CREATE_OUTPUT_METHOD =
      jni_interface->GetMethodID(env, GetDirectoryClass(jni_interface, env), "createOutput",
                                 "(Ljava/lang/String;Lorg/apache/lucene/store/IOContext;)Lorg/apache/lucene/store/IndexOutput;");
  return CREATE_OUTPUT_METHOD;
}

jmethodID OpenSearchFileManager::GetDirectoryDeleteFileMethod(JNIUtilInterface *jni_interface, JNIEnv *env) {
  static jmethodID DELETE_FILE_METHOD =
      jni_interface->GetMethodID(env, GetDirectoryClass(jni_interface, env), "deleteFile", "(Ljava/lang/String;)V");
  return DELETE_FILE_METHOD;
}

jmethodID OpenSearchFileManager::GetDirectoryListAllMethod(JNIUtilInterface *jni_interface, JNIEnv *env) {
  static jmethodID LIST_ALL_METHOD =
      jni_interface->GetMethodID(env, GetDirectoryClass(jni_interface, env), "listAll", "()[Ljava/lang/String;");
  return LIST_ALL_METHOD;
}

jmethodID OpenSearchFileManager::GetIndexInputWithBufferCtor(JNIUtilInterface *jni_interface, JNIEnv *env) {
  static jmethodID INPUT_WITH_BUFFER_CTOR =
      jni_interface->GetMethodID(env, GetIndexInputWithBufferClass(jni_interface, env), "<init>",
                                 "(Lorg/apache/lucene/store/IndexInput;)V");
  return INPUT_WITH_BUFFER_CTOR;
}

jmethodID OpenSearchFileManager::GetIndexOutputWithBufferCtor(JNIUtilInterface *jni_interface, JNIEnv *env) {
  static jmethodID OUTPUT_WITH_BUFFER_CTOR =
      jni_interface->GetMethodID(env, GetIndexOutputWithBufferClass(jni_interface, env), "<init>",
                                 "(Lorg/apache/lucene/store/IndexOutput;)V");
  return OUTPUT_WITH_BUFFER_CTOR;
}

jmethodID OpenSearchFileManager::GetCloseMethod(JNIUtilInterface *jni_interface, JNIEnv *env) {
  static jclass CLOSEABLE_CLASS =
      jni_interface->FindClassFromJNIEnv(env, "java/io/Closeable");
  static jmethodID CLOSE_METHOD =
      jni_interface->GetMethodID(env, CLOSEABLE_CLASS, "close", "()V");
  return CLOSE_METHOD;
}

}  // namespace knn_jni
