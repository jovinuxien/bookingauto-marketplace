# 11. Signup verifies before it provisions

**Status:** accepted

## Context

Until now a salon was onboarded by an operator calling `POST /api/providers`.
That endpoint is admin-only, and db/005 recorded why in the migration that
created the first login: self-serve signup "needs email verification and rate
limiting before it can face the internet".

This is the ADR for what that actually means, because the reason is sharper
than "forms on the internet get abused".

`POST /api/providers` does three things, and two of them happen at other
companies:

| step | where | reversible |
|---|---|---|
| insert a `provider` row | here | trivially |
| create a Cal user | Cal | not by us |
| create a Stripe connected account | Stripe | not by us |

Exposed as it stands, it is an endpoint that makes Stripe create a connected
account for any email address a stranger types, as fast as they can type them.
Nothing about that is fixed by validating the form better.

## Decision

**Nothing outside our database exists until the address is verified.**

Registration writes one row to `provider_signup` and enqueues one email. It
creates no provider, no Cal user and no Stripe account. Clicking the link is
what provisions.

That single ordering is the whole design, and it is worth stating as an
inversion of the usual one. The common shape is create-then-confirm: make the
account, mark it unverified, send a link, and let a background job sweep up what
nobody confirmed. It is easier to build and it is wrong here, because the
sweeping happens in systems we cannot sweep. A Stripe connected account created
for `nobody@nowhere.invalid` is not ours to delete.

### Three consequences worth naming

**The endpoint answers the same thing to everyone.** An address that already has
an account gets HTTP 202 and a body identical to a brand-new one. Anything else
turns the signup form into a way to ask which salons are on the platform —
exactly what `AuthController` already refuses to answer, and there is no point
having one door hold the line while the door beside it does not. The difference
is told, but only to the mailbox that owns the address, in a message that also
has to read sensibly to someone who did not try to sign up.

**Rate limits live in the database.** In memory would be faster and would reset
on every deploy, which on a machine that deploys often is indistinguishable from
not having them. It also would not survive a second instance. The window is
fixed rather than sliding: a caller who waits for a boundary gets two windows
back to back, which is accepted — the job is to stop a script creating a
thousand accounts, not to smooth a burst of six.

**The source is the socket address, never `X-Forwarded-For`.** A limit keyed on
a header the caller writes is a limit the caller sets for themselves, one fresh
value per request. Behind a proxy the fix is `server.forward-headers-strategy`,
which is a deployment decision.

## What the click has to survive

Provisioning is two HTTP calls to systems that are neither ours nor fast, so it
cannot be one transaction, and the states between are where the real design is.

Claiming the token is a single `UPDATE ... RETURNING` that moves the row to
`verifying`. Two clicks a second apart — a double-tap, a mail client prefetching
links — would otherwise both pass a read-then-check and both start creating
Stripe accounts. Moving the row out of a clickable state in one statement is
what makes the second click a no-op.

A failure puts the row in `failed`, which is claimable again. The address was
proved by the click and does not become unproved because Stripe timed out, so
the link keeps working. This is also why `ProviderOnboarding.start` was made
genuinely resumable: it now skips each step whose result already exists, rather
than inserting a second provider row and then colliding on the Cal username.
The alternative to a resumable retry is a support ticket for every transient
outage, which is not a self-serve flow.

## Two passwords, not one

The salon ends up with two logins — ours and Cal's — which ADR 0010 already
accepts as the cost of not having a Cal licence. They get **different**
passwords: the console password is chosen by the salon and stored as a bcrypt
hash, and the Cal password is generated, shown once and emailed once, and never
stored in plain text on our side.

Reusing the chosen password would hand a credential the person uses here to a
third-party system, and make one breach into two. It would also be the more
convenient thing to build, which is roughly how it usually happens.

The verification token is treated the same way as a password, because it is one:
256 random bits, stored only as a SHA-256 hash. A leaked backup of
`provider_signup` must not let the reader verify addresses they do not control.

## What this does not solve

**Nothing geocodes an address.** A self-serve salon has a street address and a
NULL `location`, so it appears on its city page and is invisible to the radius
search that is the product's primary filter. Left NULL rather than guessed at
the town centre, which would put it on the map in the wrong place and look
correct. Until there is a geocoder, an operator has to place each salon — which
is a smaller manual step than onboarding was, and a much less obvious one, so it
is written down here and in the README rather than discovered.

**A verified address is not a verified business.** Anyone who owns a mailbox can
register a salon called anything. What stops that salon selling is Stripe: KYC
is a real identity check on a real business, and `provider_sellable_check`
refuses to make a provider active without it. The gate is payability, and it was
already there.

**Nothing rate-limits the login endpoint.** The limiter is general and applying
it there is a few lines, but it changes the behaviour of an existing surface and
is a separate decision from this one.
