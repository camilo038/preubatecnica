package com.store.inventory;

import java.time.Duration;

public record CategoryPolicy(Duration timeToPay, int maxUnitsPerOrder) {

    public static final int NO_LIMIT = Integer.MAX_VALUE;

    public CategoryPolicy {
        if (timeToPay == null || timeToPay.isNegative() || timeToPay.isZero()) {
            throw new IllegalArgumentException("timeToPay must be positive");
        }
        if (maxUnitsPerOrder <= 0) {
            throw new IllegalArgumentException("maxUnitsPerOrder must be positive");
        }
    }

    public boolean allows(int quantity) {
        return quantity <= maxUnitsPerOrder;
    }
}
