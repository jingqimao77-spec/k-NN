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

#include "gtest/gtest.h"
#include "knowhere_grouping_util.h"
#include <vector>

namespace knn_jni {
namespace knowhere_wrapper {

TEST(KnowhereGroupingUtilTest, BuildGroupingCacheMapsChildrenToExpectedParents) {
    std::vector<int64_t> internalToExternal = {120, 121, 122, 124, 125};
    std::vector<int64_t> parentIds = {123, 126};

    GroupingCache cache = BuildGroupingCache(internalToExternal, parentIds);

    ASSERT_EQ(parentIds, cache.parent_segment_doc_ids);
    ASSERT_EQ(std::vector<uint32_t>({0, 0, 0, 1, 1}), cache.internal_to_parent_ord);
    ASSERT_EQ(HashParentIds(parentIds), cache.cache_key);
}

TEST(KnowhereGroupingUtilTest, BuildGroupingCacheWhenChildCannotResolveParentThenThrow) {
    std::vector<int64_t> internalToExternal = {120, 121, 130};
    std::vector<int64_t> parentIds = {123, 126};

    EXPECT_THROW(BuildGroupingCache(internalToExternal, parentIds), std::runtime_error);
}

TEST(KnowhereGroupingUtilTest, BuildGroupingCacheWhenInternalIdsNotSortedThenThrow) {
    std::vector<int64_t> internalToExternal = {121, 120, 122};
    std::vector<int64_t> parentIds = {123};

    EXPECT_THROW(BuildGroupingCache(internalToExternal, parentIds), std::invalid_argument);
}

} // namespace knowhere_wrapper
} // namespace knn_jni
