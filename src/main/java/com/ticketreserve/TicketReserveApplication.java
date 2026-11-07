package com.ticketreserve;

import com.codahale.metrics.health.HealthCheck;
import io.dropwizard.configuration.EnvironmentVariableSubstitutor;
import io.dropwizard.configuration.SubstitutingSourceProvider;
import io.dropwizard.core.Application;
import io.dropwizard.core.setup.Bootstrap;
import io.dropwizard.core.setup.Environment;
import io.dropwizard.jdbi3.JdbiFactory;
import io.dropwizard.lifecycle.Managed;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TicketReserveApplication extends Application<TicketReserveConfiguration> {

    private static final Logger log = LoggerFactory.getLogger(TicketReserveApplication.class);

    public static void main(String[] args) throws Exception {
        new TicketReserveApplication().run(args);
    }

    @Override
    public String getName() {
        return "ticket-reserve";
    }

    @Override
    public void initialize(Bootstrap<TicketReserveConfiguration> bootstrap) {
        bootstrap.setConfigurationSourceProvider(new SubstitutingSourceProvider(
                bootstrap.getConfigurationSourceProvider(),
                new EnvironmentVariableSubstitutor(false)));
    }

    @Override
    public void run(TicketReserveConfiguration config, Environment env) {
        Jdbi jdbi = new JdbiFactory().build(env, config.getDatabase(), "mysql");
        RedisHoldStore redis = new RedisHoldStore(
                config.getRedis().getHost(),
                config.getRedis().getPort(),
                config.getCacheTtlSeconds());
        ReservationService service = new ReservationService(jdbi, redis, config.getHoldTtlSeconds());

        waitForSchema(service);
        service.seedIfEmpty();

        env.lifecycle().manage(new Managed() {
            @Override
            public void start() {
                // connection is opened lazily
            }

            @Override
            public void stop() {
                redis.close();
            }
        });
        env.lifecycle().manage(sweeper(service));
        env.healthChecks().register("redis", new HealthCheck() {
            @Override
            protected Result check() {
                try {
                    return "PONG".equalsIgnoreCase(redis.ping())
                            ? Result.healthy()
                            : Result.unhealthy("redis did not respond");
                } catch (RuntimeException e) {
                    log.warn("redis health check failed");
                    return Result.unhealthy("redis did not respond");
                }
            }
        });

        env.jersey().register(new ReservationResource(service));
        env.jersey().register(ApiExceptionMapper.ClientError.class);
        env.jersey().register(ApiExceptionMapper.Unexpected.class);
    }

    private static void waitForSchema(ReservationService service) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= 30; attempt++) {
            try {
                service.migrate();
                return;
            } catch (RuntimeException e) {
                last = e;
                log.warn("database not ready (attempt {})", attempt);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw last;
    }

    private static Managed sweeper(ReservationService service) {
        return new Managed() {
            private ScheduledExecutorService executor;

            @Override
            public void start() {
                executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "hold-expiry");
                    thread.setDaemon(true);
                    return thread;
                });
                executor.scheduleWithFixedDelay(() -> {
                    try {
                        service.expireDue();
                    } catch (RuntimeException e) {
                        log.warn("hold expiry sweep failed");
                    }
                }, 5, 5, TimeUnit.SECONDS);
            }

            @Override
            public void stop() {
                if (executor != null) {
                    executor.shutdownNow();
                }
            }
        };
    }
}
