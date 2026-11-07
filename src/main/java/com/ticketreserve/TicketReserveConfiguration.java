package com.ticketreserve;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.core.Configuration;
import io.dropwizard.db.DataSourceFactory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public class TicketReserveConfiguration extends Configuration {

    @Valid
    @NotNull
    private DataSourceFactory database = new DataSourceFactory();

    @Valid
    @NotNull
    private RedisSettings redis = new RedisSettings();

    @Min(30)
    @Max(900)
    private int holdTtlSeconds = 180;

    @Min(1)
    @Max(60)
    private int cacheTtlSeconds = 5;

    @JsonProperty("database")
    public DataSourceFactory getDatabase() {
        return database;
    }

    @JsonProperty("database")
    public void setDatabase(DataSourceFactory database) {
        this.database = database;
    }

    @JsonProperty("redis")
    public RedisSettings getRedis() {
        return redis;
    }

    @JsonProperty("redis")
    public void setRedis(RedisSettings redis) {
        this.redis = redis;
    }

    @JsonProperty
    public int getHoldTtlSeconds() {
        return holdTtlSeconds;
    }

    @JsonProperty
    public void setHoldTtlSeconds(int holdTtlSeconds) {
        this.holdTtlSeconds = holdTtlSeconds;
    }

    @JsonProperty
    public int getCacheTtlSeconds() {
        return cacheTtlSeconds;
    }

    @JsonProperty
    public void setCacheTtlSeconds(int cacheTtlSeconds) {
        this.cacheTtlSeconds = cacheTtlSeconds;
    }

    public static class RedisSettings {
        @NotEmpty
        private String host = "127.0.0.1";

        @Min(1)
        @Max(65535)
        private int port = 6379;

        @JsonProperty
        public String getHost() {
            return host;
        }

        @JsonProperty
        public void setHost(String host) {
            this.host = host;
        }

        @JsonProperty
        public int getPort() {
            return port;
        }

        @JsonProperty
        public void setPort(int port) {
            this.port = port;
        }
    }
}
