# Ticket Reserve

A small ticket service built with Dropwizard. It lets several people try to buy the last tickets at the same time, and only one of them succeeds.

There is no numbered seat map. Each event has one inventory count. The race is “5 tickets left, 8 people click Buy.”

## How a hold works

MySQL owns the ticket count. Redis does not decide who gets a ticket.

1. The buyer calls `POST /events/{id}/holds` with a quantity and an email.
2. Inside one database transaction the service runs:

   ```sql
   UPDATE inventory
      SET available = available - :qty
    WHERE event_id = :eventId
      AND available >= :qty
   ```

3. InnoDB locks that inventory row, so two requests for the same event run one after the other.
4. The first request updates one row and inserts a reservation with status `HELD`.
5. The second request updates zero rows. The transaction rolls back and the API returns `409 sold out`.
6. After MySQL accepts the hold, Redis stores `tr:hold:{id}` for 3 minutes. That key is the checkout window.
7. If Redis never stores the key, the tickets are returned immediately.

Confirm does not subtract tickets again. `POST /holds/{id}/confirm` only changes that reservation from `HELD` to `CONFIRMED`, and only when the Redis key still exists and `expires_at` is still in the future.

Cancel (`DELETE /holds/{id}`) and a background sweep put the tickets back. Both use `UPDATE ... WHERE status = 'HELD'`, so confirm and expiry cannot both win.

`GET /events/{id}` may show a Redis-cached count for up to 5 seconds. Every hold, confirm, and cancel deletes that cache key. The write path always uses MySQL, so a stale cache cannot create an extra ticket. `GET /events` always reads MySQL.

## Stack

| Piece | Choice |
|---|---|
| HTTP | Dropwizard 4 (Jersey, Jetty) |
| Language | Java 17 |
| SQL | JDBI, bound parameters |
| Database | MySQL 8 |
| Holds and cache | Redis 7 |
| Tests | JUnit 5 and H2, including an 8-buyer race for 5 tickets |

## Run locally

Java 17 is required. The shell default on this machine may be Java 8.

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home
cd ~/Desktop/ticket-reserve
docker compose up -d
mvn test
mvn -q package
java -jar target/ticket-reserve-1.0.0.jar server config.yml
```

| Service | URL |
|---|---|
| API | http://localhost:8088 |
| Health | http://localhost:8089/healthcheck |
| MySQL | `127.0.0.1:3307` database `ticketreserve`, user `ticket`, password `ticket` |
| Redis | `127.0.0.1:6380` |

The password in `config.yml` and `docker-compose.yml` is the local Docker default. Override it with `DB_USER`, `DB_PASSWORD`, `DB_URL`, `REDIS_HOST`, or `REDIS_PORT`.

Startup retries the database for about 30 seconds, then creates the tables and seeds two events if the database is empty:

| id | Event | Tickets | Price |
|---|---|---|---|
| 1 | City Derby | 200 | 7500 cents |
| 2 | Late Show | 40 | 3200 cents |

## Try it

```bash
curl -s localhost:8088/events

curl -s -X POST localhost:8088/events/1/holds \
  -H 'content-type: application/json' \
  -d '{"qty":2,"email":"a@example.com"}'
```

The hold response looks like this. `id` is the value you confirm or cancel.

```json
{
  "id": "2f6c1a0e-7b2a-4d1e-9c3a-6a1e8b0d4f21",
  "eventId": 1,
  "qty": 2,
  "expiresAt": "2026-12-01T19:33:00Z",
  "available": 198
}
```

```bash
curl -s -X POST localhost:8088/holds/<id>/confirm
curl -s -X DELETE localhost:8088/holds/<id>
```

Quantity must be 1 to 8. A bad email or quantity returns `400`. An unknown event or hold returns `404`.

## API

| Method | Path | Result |
|---|---|---|
| GET | `/events` | All events and the MySQL ticket count |
| GET | `/events/{id}` | One event. Availability may be cached for 5 seconds |
| POST | `/events/{id}/holds` | `201` hold, or `409` when not enough tickets remain |
| POST | `/holds/{id}/confirm` | `CONFIRMED`, or `409` when the hold expired |
| DELETE | `/holds/{id}` | `204` and the tickets go back on sale |

## Layout

```
src/main/java/com/ticketreserve/
  TicketReserveApplication.java   # server, Redis, expiry sweep
  ReservationService.java         # inventory update, hold, confirm, release
  ReservationResource.java        # HTTP API
  RedisHoldStore.java             # hold keys and availability cache
src/test/java/com/ticketreserve/ReservationServiceTest.java
```

The test `onlyFiveHoldsWinWhenEightBuyersRaceForFiveTickets` starts 8 threads against 5 tickets and asserts that exactly 5 holds succeed.
