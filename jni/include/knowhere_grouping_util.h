#ifndef OPENSEARCH_KNN_KNOWHERE_GROUPING_UTIL_H
#define OPENSEARCH_KNN_KNOWHERE_GROUPING_UTIL_H

#include <algorithm>
#include <cstdint>
#include <stdexcept>
#include <string>
#include <vector>

namespace knn_jni {
namespace knowhere_wrapper {

struct GroupingCache {
    std::vector<int64_t> parent_segment_doc_ids;
    std::vector<uint32_t> internal_to_parent_ord;
    uint64_t cache_key = 0;
};

inline constexpr const char* kGroupSearchInternalToParentOrdAddressKey = "opensearch_group_search_internal_to_parent_ord_address";
inline constexpr const char* kGroupSearchParentCountKey = "opensearch_group_search_parent_count";

inline uint64_t
HashParentIds(const std::vector<int64_t>& parent_ids) {
    uint64_t hash = 1469598103934665603ULL;
    for (const int64_t parent_id : parent_ids) {
        hash ^= static_cast<uint64_t>(parent_id);
        hash *= 1099511628211ULL;
    }
    return hash;
}

inline GroupingCache
BuildGroupingCache(const std::vector<int64_t>& internal_to_external, const std::vector<int64_t>& parent_ids_sorted) {
    if (parent_ids_sorted.empty()) {
        throw std::invalid_argument("Knowhere parent ids must not be empty when building grouping cache");
    }
    if (!std::is_sorted(internal_to_external.begin(), internal_to_external.end())) {
        throw std::invalid_argument("Knowhere internal doc id sidecar must be sorted when building grouping cache");
    }

    GroupingCache cache;
    cache.parent_segment_doc_ids = parent_ids_sorted;
    cache.internal_to_parent_ord.resize(internal_to_external.size());

    size_t parent_idx = 0;
    for (size_t internal_id = 0; internal_id < internal_to_external.size(); ++internal_id) {
        const int64_t child_doc_id = internal_to_external[internal_id];
        while (parent_idx < parent_ids_sorted.size() && parent_ids_sorted[parent_idx] < child_doc_id) {
            ++parent_idx;
        }
        if (parent_idx == parent_ids_sorted.size()) {
            throw std::runtime_error(
                "Failed to resolve knowhere child doc " + std::to_string(child_doc_id) + " to a parent doc"
            );
        }
        cache.internal_to_parent_ord[internal_id] = static_cast<uint32_t>(parent_idx);
    }

    cache.cache_key = HashParentIds(parent_ids_sorted);
    return cache;
}

}  // namespace knowhere_wrapper
}  // namespace knn_jni

#endif  // OPENSEARCH_KNN_KNOWHERE_GROUPING_UTIL_H
