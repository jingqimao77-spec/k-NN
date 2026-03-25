# Knowhere Integration Test Results

Date: March 25, 2026

## Test command

```bash
su -s /bin/bash opensearch -c 'cd /workspace/opensearch/k-NN && export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64 && export GRADLE_USER_HOME=/tmp/opensearch-gradle && export CONAN_USER_HOME=/root && ./gradlew --offline integTest --rerun-tasks --tests org.opensearch.knn.index.knowhere.KnowhereIT --tests org.opensearch.knn.index.knowhere.KnowhereValidationIT --tests org.opensearch.knn.index.knowhere.KnowhereFilterAndNestedIT'
```

Environment notes:
- Tests were executed as the `opensearch` user.
- The native dependency cache under `/root/.conan` was reassigned to `opensearch` so the JNI/Knowhere build could reuse the existing Conan packages.
- Gradle used `GRADLE_USER_HOME=/tmp/opensearch-gradle`.

## Stable failures that reproduce across reruns

The following failures reproduced consistently across multiple reruns and are the strongest signal for missing Knowhere functionality.

### 1. Filtered ANN search ignores the filter for Knowhere

Failing tests:
- `KnowhereFilterAndNestedIT.testFilteredSearchWithKnowhereDiskann_whenFilterSelectsSubset_thenOnlyFilteredDocsReturned`
- `KnowhereFilterAndNestedIT.testFilteredSearchWithKnowhereDiskann_whenMultipleShards_thenOnlyFilteredDocsReturned`

Observed behavior:
- Expected filtered red docs, but the query returned the globally nearest blue docs instead.
- This reproduced for both single-shard and multi-shard cases.

Required functionality:
- Pass `filteredIds` through the Knowhere query path.
- Add a Knowhere filtered-query implementation analogous to the existing Faiss filtered path.

Relevant code path:
- `JNIService.queryIndex(...)` accepts `filteredIds`, `filterIdsType`, and `parentIds`, but the Knowhere branch discards them and calls `KnowhereService.queryIndex(indexPointer, queryVector, k, methodParameters)` directly.

References:
- `src/test/java/org/opensearch/knn/index/knowhere/KnowhereFilterAndNestedIT.java:51`
- `src/test/java/org/opensearch/knn/index/knowhere/KnowhereFilterAndNestedIT.java:76`
- `src/main/java/org/opensearch/knn/jni/JNIService.java:286`
- `src/main/java/org/opensearch/knn/jni/JNIService.java:319`

### 2. Nested ANN search does not honor parent collapsing / parent ids for Knowhere

Failing tests:
- `KnowhereFilterAndNestedIT.testNestedSearchWithKnowhere_whenKIsTwo_thenReturnTwoParentResults`
- `KnowhereFilterAndNestedIT.testNestedSearchWithKnowhere_whenFilterApplied_thenReturnOnlyMatchingParents`

Observed behavior:
- Expected two top-level parent docs, but the query returned a single unexpected parent.
- When a top-level filter was added, the query still returned the wrong parent instead of the filtered parents.

Required functionality:
- Pass `parentIds` through the Knowhere query path.
- Add parent-level result collapsing / dedup behavior for Knowhere nested ANN.
- Combine parent-aware search with the filtered-id plumbing above for nested + filter scenarios.

Relevant code path:
- Same as above: the Knowhere branch in `JNIService.queryIndex(...)` ignores both `filteredIds` and `parentIds`.

References:
- `src/test/java/org/opensearch/knn/index/knowhere/KnowhereFilterAndNestedIT.java:107`
- `src/test/java/org/opensearch/knn/index/knowhere/KnowhereFilterAndNestedIT.java:136`
- `src/main/java/org/opensearch/knn/jni/JNIService.java:286`
- `src/main/java/org/opensearch/knn/jni/JNIService.java:319`

## Additional issue that needs follow-up

### Non-default DiskANN build parameters can destabilize the test run

Failing scenario:
- `KnowhereIT.testEndToEnd_DiskANN_withNonDefaultBuildParams_thenSucceed`

Observed behavior:
- In an earlier rerun, this case returned the wrong nearest neighbor for the exact query vector.
- In the latest rerun, this scenario was followed by `Connection refused` failures from the REST client, and the rest of the Knowhere test suite cascaded because the test cluster stopped responding.

Current status:
- This is not yet proven to be a missing feature in the same way as filter / nested support.
- It does indicate a stability or correctness issue around the Knowhere DiskANN path when non-default build parameters are used.

Parameters under test:
- `max_degree=64`
- `search_list_size=96`
- `disk_pq_dims=8`
- `num_build_thread=1`
- `build_dram_budget_gb=4.0`

Reference:
- `src/test/java/org/opensearch/knn/index/knowhere/KnowhereIT.java:92`

## What passed before the stable functionality gaps were isolated

Before the cluster became unavailable in the final rerun, the following categories were already observed to work in earlier targeted reruns after fixing the test helpers:
- Basic Knowhere DiskANN search with `L2`, `INNER_PRODUCT`, and `COSINESIMIL`
- Query-time Knowhere parameters `search_list` and `beamwidth`
- Negative validation for `mode=in_memory`
- Negative validation for invalid `search_list_size`, `max_degree`, `search_list`, and `beamwidth`

## Recommended implementation order

1. Add Knowhere filtered ANN support by plumbing `filteredIds` from Java -> JNI -> Knowhere.
2. Add Knowhere nested ANN support by plumbing `parentIds` and implementing parent-level collapse/dedup.
3. Verify nested + filter on top of the above two changes.
4. Investigate the non-default build-parameter scenario separately for correctness / stability.
