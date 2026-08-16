# 8. Auto-accepting event types, because confirming is licence-gated

**Status:** accepted — supersedes the reserve-first mechanics in ADR 0005

## Context

ADR 0005 chose reserve-first: create the booking in Cal in a *pending* state,
then charge, then confirm. The slot is held by the authority that owns it, and
no money moves against a slot we did not secure.

Building it surfaced a constraint that the ordering argument never anticipated.

## What each operation actually requires

Measured against `api-v2` v6.2.0 running locally, not inferred from docs:

| operation | endpoint | auth | works unlicensed |
|---|---|---|---|
| reserve | `POST /api/book/event` (web) | none | **yes** |
| cancel | `POST /v2/bookings/:uid/cancel` | *optional* | **yes** |
| confirm | `POST /v2/bookings/:uid/confirm` | **required** | **no** |

`confirm` is guarded by `ApiAuthGuard` with a `BOOKING_WRITE` permission. Api-key
authentication begins like this:

```ts
async apiKeyStrategy(apiKey: string, request: ApiAuthGuardRequest) {
  const isLicenseValid = await this.deploymentsService.checkLicense();
  if (!isLicenseValid) throw new UnauthorizedException(
    "ApiAuthStrategy - api key - Invalid or missing CALCOM_LICENSE_KEY ...");
```

The licence check runs *before* the key is even looked at, and validates by
calling `console.cal.com`. So **any authenticated api-v2 call requires a paid Cal
licence**. The self-hosted application is MIT; programmatic authenticated access
to it is not.

`cancel` is different: `OptionalApiAuthGuard`. With no credentials it performs
the attendee-style cancellation, which is exactly the role we want when
releasing a reservation whose sale failed.

## Decision

**Event types are created auto-accepting** — neither `requiresConfirmation` nor
`requiresConfirmationWillBlockSlot`.

`POST /api/book/event` then returns a booking that is already `ACCEPTED` and
already holds the slot (verified: a day drops from 12 offered slots to 10 for a
45-minute service). There is nothing left to confirm, so the one licence-gated
call never happens.

The funnel decides this from **what Cal returned**, not from configuration on our
side: `Reservation.awaitingConfirmation()` is true only for `PENDING`. Both
event-type configurations therefore work correctly, and a licensed deployment
that prefers explicit confirmation needs no code change.

## What this costs

Cal emails the customer a confirmation before payment has succeeded, and a
cancellation if payment then fails. That is a real wrinkle, and it is a Cal
workflow setting to tune rather than an architectural problem — whereas the
licence is neither tunable nor cheap.

The slot is still held before money moves, so ADR 0005's central ordering
argument is untouched. Only its mechanism changes.

## The alternative, recorded so it is a choice

With a licence, `requiresConfirmation` **plus**
`requiresConfirmationWillBlockSlot` gives a genuinely provisional booking:
`PENDING`, holding the slot, invisible to the customer as a commitment until
confirmed. Cleaner semantics, and worth revisiting if a licence is bought for
other reasons.

Note that `requiresConfirmation` **alone** is the worst of both: the booking is
`PENDING` and the slot stays on sale, so the reservation reserves nothing.

## Two findings that cost real time

**Sending credentials to `cancel` is strictly worse than sending none.** Auth is
optional, but a token that *is* supplied gets validated — and validation starts
with the licence check. On an unlicensed deployment our api key turned a working
cancel into a 401. The identical request succeeds with the header removed.
`CalBookingClient.cancel` therefore sends no `Authorization` header, with the
reason written next to it so nobody "fixes" the omission.

**Every request needs `cal-api-version: 2024-08-13`.** Cal's own documentation
says an absent or wrong value "will default to an older version", and the older
bookings controller has a cancel route and no confirm route at all. Omitting the
header does not fail loudly; it 404s half of what we need.
