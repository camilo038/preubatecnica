package com.store.inventory;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.OrderLimitExceededException;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import com.store.inventory.api.StockAlertListener;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;

final class InMemoryInventoryService implements InventoryService {

    static final int LOW_STOCK_THRESHOLD = 5;

    private static final System.Logger LOG = System.getLogger(InMemoryInventoryService.class.getName());

    private final Clock clock;
    private final StockAlertListener alertListener;
    private final Map<ProductCategory, CategoryPolicy> policies;
    private final Map<String, ProductStock> products = new ConcurrentHashMap<>();
    private final Map<String, String> skuByOrder = new ConcurrentHashMap<>();

    InMemoryInventoryService(Clock clock, StockAlertListener alertListener, Map<ProductCategory, CategoryPolicy> policies) {
        this.clock = Objects.requireNonNull(clock);
        this.alertListener = Objects.requireNonNull(alertListener);
        this.policies = Map.copyOf(policies);
    }

    @Override
    public void registerProduct(String sku, ProductCategory category) {
        requireText(sku, "sku");
        Objects.requireNonNull(category, "category");
        CategoryPolicy policy = policies.get(category);
        if (policy == null) {
            throw new IllegalArgumentException("No rules configured for category " + category);
        }
        ProductStock existing = products.putIfAbsent(sku, new ProductStock(sku, category, policy));
        if (existing != null && existing.category != category) {
            throw new IllegalArgumentException("Product " + sku + " is already registered with another category");
        }
    }

    @Override
    public void addStock(String sku, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        ProductStock product = sku == null ? null : products.get(sku);
        if (product == null) {
            throw new IllegalArgumentException("Product " + sku + " is not registered");
        }
        OptionalInt alert;
        synchronized (product) {
            product.releaseExpired(now());
            product.unsoldUnits += quantity;
            product.lowStockNotified = false;
            alert = checkLowStock(product);
        }
        notify(product.sku, alert);
    }

    @Override
    public Reservation reserve(String orderId, String sku, int quantity) {
        requireText(orderId, "orderId");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        ProductStock product = sku == null ? null : products.get(sku);
        if (product == null) {
            throw new InsufficientStockException(sku, quantity, 0);
        }
        if (!product.policy.allows(quantity)) {
            throw new OrderLimitExceededException(sku, quantity, product.policy.maxUnitsPerOrder());
        }

        Reservation reservation;
        OptionalInt alert;
        synchronized (product) {
            Instant now = now();
            product.releaseExpired(now);

            // reintento de la app
            Reservation previous = product.activeHolds.getOrDefault(orderId, product.confirmed.get(orderId));
            if (previous != null) {
                if (previous.quantity() != quantity) {
                    throw new IllegalStateException("Order " + orderId + " already has a reservation with another quantity");
                }
                return previous;
            }

            int available = product.available();
            if (quantity > available) {
                throw new InsufficientStockException(sku, quantity, available);
            }
            String usedBy = skuByOrder.putIfAbsent(orderId, sku);
            if (usedBy != null && !usedBy.equals(sku)) {
                throw new IllegalStateException("Order " + orderId + " already belongs to product " + usedBy);
            }

            reservation = new Reservation(orderId, sku, quantity, now.plus(product.policy.timeToPay()));
            product.activeHolds.put(orderId, reservation);
            alert = checkLowStock(product);
        }
        notify(sku, alert);
        return reservation;
    }

    @Override
    public void confirm(String orderId) {
        String sku = orderId == null ? null : skuByOrder.get(orderId);
        ProductStock product = sku == null ? null : products.get(sku);
        if (product == null) {
            throw new IllegalStateException("Order " + orderId + " has no active reservation");
        }
        synchronized (product) {
            product.releaseExpired(now());
            Reservation reservation = product.activeHolds.remove(orderId);
            if (reservation == null) {
                throw new IllegalStateException("Order " + orderId + " has no active reservation");
            }
            product.unsoldUnits -= reservation.quantity();
            product.confirmed.put(orderId, reservation);
        }
    }

    @Override
    public int available(String sku) {
        ProductStock product = sku == null ? null : products.get(sku);
        if (product == null) {
            return 0;
        }
        synchronized (product) {
            product.releaseExpired(now());
            return product.available();
        }
    }

    private OptionalInt checkLowStock(ProductStock product) {
        int available = product.available();
        if (available <= LOW_STOCK_THRESHOLD && !product.lowStockNotified) {
            product.lowStockNotified = true;
            return OptionalInt.of(available);
        }
        return OptionalInt.empty();
    }

    private void notify(String sku, OptionalInt alert) {
        if (alert.isEmpty()) {
            return;
        }
        try {
            alertListener.onLowStock(sku, alert.getAsInt());
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Low stock alert failed for " + sku, e);
        }
    }

    private Instant now() {
        return clock.instant();
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
