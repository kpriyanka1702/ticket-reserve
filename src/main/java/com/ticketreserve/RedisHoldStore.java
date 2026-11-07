package com.ticketreserve;

import redis.clients.jedis.JedisPooled;

import java.util.Optional;

public final class RedisHoldStore implements HoldStore {

    private final JedisPooled redis;
    private final int cacheTtlSeconds;

    public RedisHoldStore(String host, int port, int cacheTtlSeconds) {
        this.redis = new JedisPooled(host, port);
        this.cacheTtlSeconds = cacheTtlSeconds;
    }

    @Override
    public void save(String reservationId, int ttlSeconds) {
        String result = redis.setex(holdKey(reservationId), ttlSeconds, "1");
        if (!"OK".equals(result)) {
            throw new IllegalStateException("redis hold was not stored");
        }
    }

    @Override
    public boolean exists(String reservationId) {
        return redis.exists(holdKey(reservationId));
    }

    @Override
    public void delete(String reservationId) {
        redis.del(holdKey(reservationId));
    }

    @Override
    public Optional<Integer> cachedAvailable(long eventId) {
        // ponytail: cache misses and Redis blips fall through to MySQL; a broken cache must not fail the read
        try {
            String value = redis.get(availKey(eventId));
            if (value == null) {
                return Optional.empty();
            }
            return Optional.of(Integer.valueOf(value));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    @Override
    public void cacheAvailable(long eventId, int available) {
        try {
            redis.setex(availKey(eventId), cacheTtlSeconds, Integer.toString(available));
        } catch (RuntimeException ignored) {
            // read path still has MySQL
        }
    }

    @Override
    public void invalidate(long eventId) {
        try {
            redis.del(availKey(eventId));
        } catch (RuntimeException ignored) {
            // key expires on its own within cacheTtlSeconds
        }
    }

    @Override
    public String ping() {
        return redis.ping();
    }

    public void close() {
        redis.close();
    }

    private static String holdKey(String reservationId) {
        return "tr:hold:" + reservationId;
    }

    private static String availKey(long eventId) {
        return "tr:avail:" + eventId;
    }
}
