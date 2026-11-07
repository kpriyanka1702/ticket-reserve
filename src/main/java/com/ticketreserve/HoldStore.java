package com.ticketreserve;

import java.util.Optional;

/** Redis (or a test double) for short-lived holds and the availability cache. */
public interface HoldStore {

    void save(String reservationId, int ttlSeconds);

    boolean exists(String reservationId);

    void delete(String reservationId);

    Optional<Integer> cachedAvailable(long eventId);

    void cacheAvailable(long eventId, int available);

    void invalidate(long eventId);

    String ping();
}
