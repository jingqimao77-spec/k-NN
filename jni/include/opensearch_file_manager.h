/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

#ifndef OPENSEARCH_KNN_JNI_OPENSEARCH_FILE_MANAGER_H
#define OPENSEARCH_KNN_JNI_OPENSEARCH_FILE_MANAGER_H

#include "jni_util.h"
#include "knowhere/file_manager.h"
#include "native_engines_stream_support.h"

#include <filesystem>
#include <jni.h>
#include <optional>
#include <string>
#include <vector>

namespace knn_jni {

class OpenSearchFileManager : public knowhere::FileManager {
 public:
  OpenSearchFileManager(JNIUtilInterface *jni_interface,
                        JNIEnv *env,
                        jobject directory,
                        jobject io_context,
                        const std::string &local_data_path);

  ~OpenSearchFileManager();

  bool AddFile(const std::string &filename) noexcept override;
  bool LoadFile(const std::string &filename) noexcept override;
  std::optional<bool> IsExisted(const std::string &filename) noexcept override;
  bool RemoveFile(const std::string &filename) noexcept override;

  std::vector<std::string> AddedFiles() const;
  bool SyncTrackedFilesToDirectory();
  bool MaterializeFilesFromDirectory(const std::vector<std::string> &file_names, bool use_compound_suffix = false);

 private:
  JNIEnv *GetEnv();
  jobject OpenIndexInput(const std::string &lucene_file_name);
  jobject CreateIndexOutput(const std::string &lucene_file_name);
  void CloseCloseable(jobject closeable);
  std::string GetLuceneFileName(const std::string &filename) const;
  std::string GetLocalFilePath(const std::string &filename) const;
  bool CopyLocalFileToDirectory(const std::string &filename);
  bool CopyLuceneFileToLocal(const std::string &lucene_file_name, const std::string &target_local_path);

  static jclass GetDirectoryClass(JNIUtilInterface *jni_interface, JNIEnv *env);
  static jclass GetIndexInputClass(JNIUtilInterface *jni_interface, JNIEnv *env);
  static jclass GetIndexOutputClass(JNIUtilInterface *jni_interface, JNIEnv *env);
  static jclass GetIndexInputWithBufferClass(JNIUtilInterface *jni_interface, JNIEnv *env);
  static jclass GetIndexOutputWithBufferClass(JNIUtilInterface *jni_interface, JNIEnv *env);

  static jmethodID GetDirectoryOpenInputMethod(JNIUtilInterface *jni_interface, JNIEnv *env);
  static jmethodID GetDirectoryCreateOutputMethod(JNIUtilInterface *jni_interface, JNIEnv *env);
  static jmethodID GetDirectoryDeleteFileMethod(JNIUtilInterface *jni_interface, JNIEnv *env);
  static jmethodID GetDirectoryListAllMethod(JNIUtilInterface *jni_interface, JNIEnv *env);
  static jmethodID GetIndexInputWithBufferCtor(JNIUtilInterface *jni_interface, JNIEnv *env);
  static jmethodID GetIndexOutputWithBufferCtor(JNIUtilInterface *jni_interface, JNIEnv *env);
  static jmethodID GetCloseMethod(JNIUtilInterface *jni_interface, JNIEnv *env);

  JNIUtilInterface *jni_interface_;
  JavaVM *jvm_;
  jobject directory_global_;
  jobject io_context_global_;
  std::filesystem::path root_dir_;
  std::vector<std::string> added_files_;
};

}  // namespace knn_jni

#endif  // OPENSEARCH_KNN_JNI_OPENSEARCH_FILE_MANAGER_H
