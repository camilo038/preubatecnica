package com.store.inventory;

import com.store.inventory.api.ProductCategory;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

public final class CategoryPolicies {

    private CategoryPolicies() {
    }

    public static Map<ProductCategory, CategoryPolicy> defaults() {
        Map<ProductCategory, CategoryPolicy> policies = new EnumMap<>(ProductCategory.class);
        policies.put(ProductCategory.STANDARD, new CategoryPolicy(Duration.ofMinutes(15), CategoryPolicy.NO_LIMIT));
        policies.put(ProductCategory.PRE_ORDER, new CategoryPolicy(Duration.ofHours(24), CategoryPolicy.NO_LIMIT));
        policies.put(ProductCategory.FLASH_SALE, new CategoryPolicy(Duration.ofMinutes(5), 2));
        return policies;
    }
}
