package com.ticketreserve;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReservationServiceTest {

    private Jdbi jdbi;
    private ReservationService service;

    @BeforeEach
    void setUp() {
        jdbi = Jdbi.create("jdbc:h2:mem:ticketreserve;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        service = new ReservationService(jdbi, new MemoryHoldStore(), 180);
        service.migrate();
        jdbi.useHandle(handle -> {
            handle.execute("DELETE FROM reservations");
            handle.execute("DELETE FROM inventory");
            handle.execute("DELETE FROM events");
        });
        service.insertEvent(1, "City Derby", "Harbor Stadium",
                Instant.parse("2026-12-01T19:30:00Z"), 5, 7500);
    }

    @Test
    void onlyFiveHoldsWinWhenEightBuyersRaceForFiveTickets() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                String email = "fan" + i + "@example.com";
                results.add(pool.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    try {
                        service.hold(1, 1, email);
                        return true;
                    } catch (ApiException e) {
                        if (e.getStatus() == 409) {
                            return false;
                        }
                        throw e;
                    }
                }));
            }
            start.countDown();
            int wins = 0;
            for (Future<Boolean> result : results) {
                if (result.get(10, TimeUnit.SECONDS)) {
                    wins++;
                }
            }
            assertEquals(5, wins);
            assertEquals(0, service.available(1));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void confirmKeepsTheTicketsAndASecondConfirmFails() {
        ReservationService.HoldView hold = service.hold(1, 2, "a@example.com");
        ReservationService.ConfirmView confirmed = service.confirm(hold.id());
        assertEquals("CONFIRMED", confirmed.status());
        assertEquals(3, service.available(1));
        ApiException again = assertThrows(ApiException.class, () -> service.confirm(hold.id()));
        assertEquals(409, again.getStatus());
        assertEquals(3, service.available(1));
    }

    @Test
    void cancelAndExpiryPutTicketsBack() {
        ReservationService.HoldView hold = service.hold(1, 2, "a@example.com");
        assertEquals(3, service.available(1));
        service.release(hold.id());
        assertEquals(5, service.available(1));
        assertEquals("RELEASED", service.status(hold.id()));

        ReservationService.HoldView expiring = service.hold(1, 1, "b@example.com");
        jdbi.useHandle(handle -> handle.createUpdate(
                        "UPDATE reservations SET expires_at = :then WHERE id = :id")
                .bind("then", Timestamp.from(Instant.now().minusSeconds(5)))
                .bind("id", expiring.id())
                .execute());
        assertEquals(1, service.expireDue());
        assertEquals(5, service.available(1));
        assertEquals("EXPIRED", service.status(expiring.id()));
    }

    @Test
    void eventReadUsesCacheUntilTheNextHold() {
        service.hold(1, 1, "a@example.com");
        assertEquals(4, service.getEvent(1).available());
        jdbi.useHandle(handle -> handle.execute("UPDATE inventory SET available = 1 WHERE event_id = 1"));
        assertEquals(4, service.getEvent(1).available());
        service.hold(1, 1, "b@example.com");
        assertEquals(0, service.getEvent(1).available());
    }

    private static final class MemoryHoldStore implements HoldStore {
        private final ConcurrentHashMap<String, Boolean> holds = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<Long, Integer> cache = new ConcurrentHashMap<>();

        @Override
        public void save(String reservationId, int ttlSeconds) {
            holds.put(reservationId, Boolean.TRUE);
        }

        @Override
        public boolean exists(String reservationId) {
            return holds.containsKey(reservationId);
        }

        @Override
        public void delete(String reservationId) {
            holds.remove(reservationId);
        }

        @Override
        public Optional<Integer> cachedAvailable(long eventId) {
            return Optional.ofNullable(cache.get(eventId));
        }

        @Override
        public void cacheAvailable(long eventId, int available) {
            cache.put(eventId, available);
        }

        @Override
        public void invalidate(long eventId) {
            cache.remove(eventId);
        }

        @Override
        public String ping() {
            return "PONG";
        }
    }
}
