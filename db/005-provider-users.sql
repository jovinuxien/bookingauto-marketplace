-- Logins for the business console.
--
--   docker exec -i bm-market-db psql -U market -d marketplace < db/005-provider-users.sql
--
-- The first authenticated surface in the system. Everything until now was
-- either public (search, a consumer booking) or verified by a shared secret
-- (Cal and Stripe webhooks), and neither of those is a person.
--
-- Credentials live here rather than being borrowed from Cal. The salon has a
-- Cal account, and authenticating against it would be convenient and wrong:
-- it would make our session depend on a system whose auth we do not control,
-- and it would mean a salon that leaves Cal loses access to its own money.

CREATE TABLE provider_user (
    id             bigserial   PRIMARY KEY,
    provider_id    bigint      NOT NULL REFERENCES provider (id) ON DELETE CASCADE,

    -- Case-insensitive by storage rather than by convention. "Anna@salong.se"
    -- and "anna@salong.se" are one person, and leaving that to every query to
    -- remember is how duplicate accounts happen.
    email          text        NOT NULL,

    -- BCrypt. The cost factor is embedded in the hash, so it can be raised
    -- later without a migration.
    password_hash  text        NOT NULL,

    display_name   text,
    role           text        NOT NULL DEFAULT 'owner'
                   CHECK (role IN ('owner', 'staff')),

    -- Kept so a departing employee can be cut off without deleting the rows
    -- that explain who did what.
    active         boolean     NOT NULL DEFAULT true,

    last_login_at  timestamptz,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX provider_user_email_idx ON provider_user (lower(email));
CREATE INDEX provider_user_provider_idx ON provider_user (provider_id) WHERE active;

-- ------------------------------------------------------------ platform admin
-- Onboarding a salon is an operator action, not something one salon does to
-- another. That needs a principal with no provider of its own, so provider_id
-- becomes optional and the role set gains a platform-level entry.
--
-- Self-serve salon signup is a separate design -- it needs email verification
-- and rate limiting before it can face the internet -- and until it exists,
-- salons are onboarded by an operator.
ALTER TABLE provider_user ALTER COLUMN provider_id DROP NOT NULL;

ALTER TABLE provider_user DROP CONSTRAINT provider_user_role_check;
ALTER TABLE provider_user ADD CONSTRAINT provider_user_role_check
    CHECK (role IN ('owner', 'staff', 'platform_admin'));

-- A provider user without a provider must be a platform admin, and a platform
-- admin must not be tied to one. Both halves matter: the first stops an
-- orphaned salon login existing at all, the second stops an operator's
-- privileges being scoped to a salon by accident.
ALTER TABLE provider_user ADD CONSTRAINT provider_user_scope_check
    CHECK ((role = 'platform_admin') = (provider_id IS NULL));
