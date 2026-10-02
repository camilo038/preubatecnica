package com.store.inventory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.OrderLimitExceededException;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ReservationRulesTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-10T10:00:00Z"));
    private final List<String> alerts = new ArrayList<>();
    private InventoryService service;

    @BeforeEach
    void setUp() {
        service = Inventory.create(clock, (sku, units) -> alerts.add(sku + ":" + units));
    }

    @Test
    void reservationExpiresByCategory() {
        service.registerProduct("TV", ProductCategory.STANDARD);
        service.addStock("TV", 10);

        Reservation r = service.reserve("O-1", "TV", 4);

        assertEquals(clock.instant().plus(Duration.ofMinutes(15)), r.expiresAt());
        clock.advance(Duration.ofMinutes(14));
        assertEquals(6, service.available("TV"));
        clock.advance(Duration.ofMinutes(1));
        assertEquals(10, service.available("TV"));
    }

    @Test
    void preOrderGivesOneDayToPay() {
        service.registerProduct("BOOK", ProductCategory.PRE_ORDER);
        service.addStock("BOOK", 10);

        Reservation r = service.reserve("O-1", "BOOK", 1);

        assertEquals(clock.instant().plus(Duration.ofHours(24)), r.expiresAt());
    }

    @Test
    void cannotConfirmExpiredReservation() {
        service.registerProduct("PHONE", ProductCategory.FLASH_SALE);
        service.addStock("PHONE", 10);
        service.reserve("O-1", "PHONE", 2);

        clock.advance(Duration.ofMinutes(5));

        assertThrows(IllegalStateException.class, () -> service.confirm("O-1"));
        assertEquals(10, service.available("PHONE"));
    }

    @Test
    void confirmWithoutReservationFails() {
        assertThrows(IllegalStateException.class, () -> service.confirm("NOPE"));
    }

    @Test
    void flashSaleAllowsTwoUnitsPerOrder() {
        service.registerProduct("PHONE", ProductCategory.FLASH_SALE);
        service.addStock("PHONE", 10);

        assertThrows(OrderLimitExceededException.class, () -> service.reserve("O-1", "PHONE", 3));
        assertDoesNotThrow(() -> service.reserve("O-2", "PHONE", 2));
    }

    @Test
    void retriedOrderDoesNotReserveTwice() {
        service.registerProduct("TV", ProductCategory.STANDARD);
        service.addStock("TV", 10);

        Reservation first = service.reserve("O-1", "TV", 3);
        Reservation retry = service.reserve("O-1", "TV", 3);

        assertEquals(first, retry);
        assertEquals(7, service.available("TV"));
    }

    @Test
    void sameOrderCannotBeReusedForAnotherProduct() {
        service.registerProduct("TV", ProductCategory.STANDARD);
        service.registerProduct("RADIO", ProductCategory.STANDARD);
        service.addStock("TV", 10);
        service.addStock("RADIO", 10);
        service.reserve("O-1", "TV", 1);

        assertThrows(IllegalStateException.class, () -> service.reserve("O-1", "RADIO", 1));
    }

    @Test
    void unknownProductHasNoStock() {
        assertEquals(0, service.available("GHOST"));
        assertThrows(InsufficientStockException.class, () -> service.reserve("O-1", "GHOST", 1));
        assertThrows(IllegalArgumentException.class, () -> service.addStock("GHOST", 1));
    }

    @Test
    void productCannotChangeCategory() {
        service.registerProduct("TV", ProductCategory.STANDARD);
        assertDoesNotThrow(() -> service.registerProduct("TV", ProductCategory.STANDARD));
        assertThrows(IllegalArgumentException.class, () -> service.registerProduct("TV", ProductCategory.FLASH_SALE));
    }

    @Test
    void invalidQuantitiesAreRejected() {
        service.registerProduct("TV", ProductCategory.STANDARD);
        assertThrows(IllegalArgumentException.class, () -> service.addStock("TV", 0));
        assertThrows(IllegalArgumentException.class, () -> service.reserve("O-1", "TV", -1));
    }

    @Test
    void lowStockAlertIsSentOnceUntilRestock() {
        service.registerProduct("TV", ProductCategory.STANDARD);
        service.addStock("TV", 8);

        service.reserve("O-1", "TV", 3);
        service.reserve("O-2", "TV", 1);

        assertEquals(List.of("TV:5"), alerts);

        service.addStock("TV", 10);
        service.reserve("O-3", "TV", 10);

        assertEquals(List.of("TV:5", "TV:4"), alerts);
    }

    @Test
    void failingAlertDoesNotBreakTheReservation() {
        InventoryService broken = Inventory.create(clock, (sku, units) -> {
            throw new RuntimeException("smtp caido");
        });
        broken.registerProduct("TV", ProductCategory.STANDARD);
        broken.addStock("TV", 6);

        assertNotNull(broken.reserve("O-1", "TV", 2));
        assertEquals(4, broken.available("TV"));
    }

    @ParameterizedTest
    @EnumSource(ProductCategory.class)
    void everyCategoryHasRules(ProductCategory category) {
        assertNotNull(CategoryPolicies.defaults().get(category),
                "Falta configurar " + category + " en CategoryPolicies");
    }

    @Test
    void concurrentBuyersNeverOversell() throws InterruptedException {
        service.registerProduct("PHONE", ProductCategory.FLASH_SALE);
        service.addStock("PHONE", 50);
        int buyers = 200;
        AtomicInteger reserved = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(16);

        for (int i = 0; i < buyers; i++) {
            String orderId = "O-" + i;
            pool.submit(() -> {
                start.await();
                try {
                    service.reserve(orderId, "PHONE", 1);
                    reserved.incrementAndGet();
                } catch (InsufficientStockException ignored) {
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        assertEquals(50, reserved.get());
        assertEquals(0, service.available("PHONE"));
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
