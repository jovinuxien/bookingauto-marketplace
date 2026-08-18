-- Self-serve salon signup.
--
--   docker exec -i bm-market-db psql -U market -d marketplace < db/007-self-serve-signup.sql
--
-- Until now a salon was onboarded by an operator calling POST /api/providers,
-- and db/005 recorded why: that endpoint creates a Cal account and a Stripe
-- connected account, and pointing it at the open internet without email
-- verification and rate limiting would let anyone make us do both, repeatedly,
-- for addresses they do not own.
--
-- So this migration is those two prerequisites, and the state that sits between
-- "someone filled in a form" and "a provider exists".
--
-- The ordering is the whole design. A signup creates NOTHING outside this
-- database until the address is proven: no provider row, no Cal user, no Stripe
-- account. Verification is not a courtesy step bolted onto the end of
-- provisioning -- it is the gate in front of it.

-- ---------------------------------------------------------- provider_signup --
-- A signup in progress. Deliberately not a draft provider row.
--
-- A provider that does not exist yet cannot be half-activated, cannot appear in
-- a search by accident, and cannot be counted in an operations query asking how
-- many salons are stuck. Everything unproven lives here instead, and crossing
-- into provider is the moment the address was verified.
CREATE TABLE provider_signup (
    id             bigserial   PRIMARY KEY,

    -- Case-insensitive for the same reason provider_user.email is: one person
    -- typing two capitalisations is one person.
    email          text        NOT NULL,

    salon_name     text        NOT NULL,

    -- Reserved at signup rather than at verification. Two salons called
    -- "Klipp & Co" filling in the form on the same afternoon must not both be
    -- told they succeeded and then have the second fail hours later, when the
    -- first thing it collides with is a Cal username.
    slug           text        NOT NULL,

    address_line   text,
    postal_code    text,
    city           text,

    -- The console login's password, hashed now and never held in plain text
    -- while it waits. The alternative -- asking for it again after the link is
    -- clicked -- means the person has to invent it twice.
    password_hash  text        NOT NULL,

    -- SHA-256 of the token that went out in the email, never the token itself.
    -- A leaked backup of this table must not let the reader verify addresses
    -- they do not control; the same argument as storing password hashes, and
    -- the token is a bearer credential exactly like a password.
    token_hash     text        NOT NULL,

    state          text        NOT NULL DEFAULT 'pending'
                   CHECK (state IN (
                       'pending',    -- link sent, not yet clicked
                       'verifying',  -- link clicked; Cal and Stripe are being called
                       'completed',  -- verified, and the provider exists
                       'failed',     -- verified, and provisioning did not work
                       'superseded', -- a newer signup for this address replaced it
                       'expired'     -- the link was never clicked
                   )),

-- 'verifying' is not decoration. Provisioning talks to Cal and to Stripe, which
-- takes seconds and cannot happen inside a database transaction, so two clicks
-- on the same link a second apart would otherwise both start creating accounts.
-- Claiming the row by moving it out of a clickable state is what makes the
-- second click a no-op instead of a second Stripe account.

    -- Set on success. Also set on failure when the provider row was created
    -- before the failure, which is what makes a retry resumable rather than a
    -- second half-built salon.
    provider_id    bigint      REFERENCES provider (id) ON DELETE SET NULL,

    failure        text,
    attempts       integer     NOT NULL DEFAULT 0,

    expires_at     timestamptz NOT NULL,
    verified_at    timestamptz,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now()
);

-- The lookup the verification link does. Unique because a token identifies one
-- signup or it is not a credential.
CREATE UNIQUE INDEX provider_signup_token_idx ON provider_signup (token_hash);

-- One signup in flight per address. A second attempt supersedes the first
-- rather than queueing behind it: someone who did not receive the email fills
-- the form in again, and the link that arrives must be the one that works.
CREATE UNIQUE INDEX provider_signup_pending_email_idx
    ON provider_signup (lower(email)) WHERE state = 'pending';

-- Same for the slug, so a name is held only while a signup is live.
CREATE UNIQUE INDEX provider_signup_pending_slug_idx
    ON provider_signup (slug) WHERE state = 'pending';

-- "Which signups verified and then failed to provision?" is the operations
-- question this table exists to answer, and it must stay cheap.
CREATE INDEX provider_signup_failed_idx ON provider_signup (updated_at)
    WHERE state = 'failed';

-- Expiry has to be swept rather than merely checked, because a pending row
-- holds its slug through the unique index above. Left alone, one abandoned
-- registration would reserve a salon's own name against it forever.
CREATE INDEX provider_signup_expiry_idx ON provider_signup (expires_at)
    WHERE state = 'pending';

-- ------------------------------------------------------------- rate_limit ---
-- A fixed-window counter, in the database rather than in a field of the JVM.
--
-- In memory would be faster and would reset every deploy, which is the same as
-- not existing on a machine that redeploys often. It also would not survive a
-- second instance, and this is the one surface where the cost of getting it
-- wrong is paid to Stripe and to Cal rather than to us.
--
-- Fixed window, not sliding: a caller who waits for a boundary can get two
-- windows' worth in quick succession. That is understood and accepted. The
-- limits here are about stopping a script from creating a thousand accounts,
-- not about smoothing a burst of six, and a sliding window costs a row per
-- request to buy precision nothing here needs.
CREATE TABLE rate_limit (
    bucket        text        NOT NULL,
    window_start  timestamptz NOT NULL,
    count         integer     NOT NULL DEFAULT 0,

    PRIMARY KEY (bucket, window_start)
);

-- Old windows are dead weight the moment they close. Indexed so the sweep that
-- removes them does not scan.
CREATE INDEX rate_limit_window_idx ON rate_limit (window_start);

-- ------------------------------------------------------ notification kinds ---
-- Three new messages, and the middle one is the interesting one.
--
-- signup_exists goes to an address that is already registered, in place of a
-- verification link. The signup endpoint answers identically either way -- it
-- has to, or the form becomes a way to ask which salons are on the platform --
-- so the only place the difference can be told is in the mailbox of the person
-- who owns the address.
ALTER TABLE notification_outbox DROP CONSTRAINT notification_outbox_kind_check;

ALTER TABLE notification_outbox ADD CONSTRAINT notification_outbox_kind_check
    CHECK (kind IN (
        'booking_confirmed',
        'booking_released',
        'booking_refunded',
        'booking_needs_attention',
        'signup_verification',
        'signup_exists',
        'signup_welcome'
    ));
