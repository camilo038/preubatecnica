package com.store.inventory;

import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

final class ProductStock {

    final String sku;
    final ProductCategory category;
    final CategoryPolicy policy;
    int unsoldUnits;
    boolean lowStockNotified;
    final Map<String, Reservation> activeHolds = new HashMap<>();
    final Map<String, Reservation> confirmed = new HashMap<>();

    ProductStock(String sku, ProductCategory category, CategoryPolicy policy) {
        this.sku = sku;
        this.category = category;
        this.policy = policy;
    }

    void releaseExpired(Instant now) {
        activeHolds.values().removeIf(r -> !now.isBefore(r.expiresAt()));
    }

    int available() {
        int held = activeHolds.values().stream().mapToInt(Reservation::quantity).sum();
        return unsoldUnits - held;
    }
}
