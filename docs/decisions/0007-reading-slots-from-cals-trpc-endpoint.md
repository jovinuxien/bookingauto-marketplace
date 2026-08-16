# 7. Read slots from Cal's tRPC endpoint, not the v2 REST API

**Status:** accepted

## Context

The incoming architecture assumes Cal's v2 REST API throughout — `/v2/slots`,
`/v2/bookings`, and so on. That API is real and documented.

It is not in the image we run.

`calcom/cal.com` ships `apps/web` only. Probed against our running container:

| path | result |
|---|---|
| `/api/v2/slots` | 500 |
| `/v2/slots` | 404 |
| `/api/trpc/slots/getSchedule` | **200** |

`@calcom/api-v2` is a separate NestJS application in the monorepo. There is no
published image for it — the `calcom` Docker Hub organisation publishes
`cal.com`, `cal.diy` and `pgbouncer`, and nothing else. Using it means building
the monorepo ourselves and operating a second service.

This is worth stating plainly because an architecture drawn on `/v2/*` looks
finished and is not deployable as drawn.

## Decision

**Read availability from `/api/trpc/slots/getSchedule`**, served by the web
image we already run.

The call takes a superjson envelope in one query parameter:

```
/api/trpc/slots/getSchedule?input={"json":{
  "eventTypeId": 1,
  "startTime": "...", "endTime": "...",
  "timeZone": "Europe/Stockholm"}}
```

and returns `result.data.json.slots` — a map of local date to `[{"time": "..."}]`.

## Why this is defensible

It is the exact call Cal's own public booking page makes. So it returns, by
construction, what a customer would see — which is precisely what the
availability index exists to mirror. A separately maintained REST surface could
in principle disagree with the booking page; this cannot.

## Why it is a real cost

It is an **internal** API with no compatibility promise. A Cal upgrade may change
it without notice or deprecation.

Two things make that acceptable rather than reckless:

- **It is contained.** `CalSlotsClient` is the only thing in the system that
  knows Cal's wire shape. Everything else reads `availability_day`.
- **It fails visibly.** A shape change yields zero slots and a warning, the
  reconciler leaves the service stale, and `computed_at` shows the index
  standing still. Stale and visibly so, rather than confidently wrong.

Pin the Cal image version and re-probe this endpoint as part of any upgrade.

## Revisit when we build bookings

Reads and writes need not answer this the same way. A booking is a write against
the authority — the place where being wrong costs a customer their appointment —
and that may well justify building and operating `api-v2`. If we build it for
writes, reads should move with it.

## Three findings recorded so nobody rediscovers them

**An event type absent from Cal's `_user_eventtype` join table returns an empty
slot map with HTTP 200 and no error.** `EventType.userId` is only the owner; Cal
resolves bookable hosts through that join table. An empty slot map is
indistinguishable from a fully booked salon, so this failure is completely
silent. It cost real time during seeding and is guarded in `seed/cal-dev.sql`.

**The JDK HTTP client cannot talk to Cal without being pinned to HTTP/1.1.**
Spring's `RestClient` selects the JDK `HttpClient`, which defaults to HTTP/2 and
opens with a cleartext h2c upgrade; Cal's Next.js server answers by closing the
connection. The JDK reports `HTTP/1.1 header parser received no bytes` — a
connection-level error that looks nothing like protocol negotiation, while the
identical URL over curl succeeds.

**tRPC reports errors as HTTP 200 with an `error` member.** Status-code checking
alone treats a refusal as success and writes an empty day.
