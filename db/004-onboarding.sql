-- Provider onboarding.
--
--   docker exec -i bm-market-db psql -U market -d marketplace < db/004-onboarding.sql
--
-- Onboarding is where the two halves of a provider are assembled: something to
-- sell (Cal) and somewhere to be paid (Stripe). Neither is instant and neither
-- is ours, so this is a state machine rather than a form submission.
--
-- The shape is forced by what Cal permits without a paid licence. Creating a
-- user is public; creating that user's schedule and event types is not --
-- /v2/event-types and /v2/schedules answer 401. So the salon builds its
-- services in Cal's own UI, which is built for exactly that, and we IMPORT
-- them. Reading Cal's database as a read-model is sanctioned by ADR 0001;
-- writing to it would be the fork-by-stealth that ADR rejects.

-- ---------------------------------------------------------------- provider --
-- cal_team_id becomes optional. A provider maps to a Cal USER today: a Team is
-- the right model for a salon with several staff, but creating one needs the
-- same licensed API as everything else. Recorded rather than hidden -- see the
-- known shape problem about Cal scheduling people, not rooms.
ALTER TABLE provider ALTER COLUMN cal_team_id DROP NOT NULL;

ALTER TABLE provider
    ADD COLUMN cal_user_id  integer UNIQUE,
    ADD COLUMN cal_username text    UNIQUE,
    ADD COLUMN contact_email text;

-- Where onboarding has got to. Deliberately explicit rather than derived from
-- "are all the columns filled in": a provider stuck halfway is the thing
-- operations needs to see, and a NULL somewhere does not say which half failed.
ALTER TABLE provider
    ADD COLUMN onboarding_state text NOT NULL DEFAULT 'started'
        CHECK (onboarding_state IN (
            'started',          -- provider row exists, nothing else does
            'cal_created',      -- Cal user exists; the salon can now set up services
            'awaiting_kyc',     -- Stripe account created, salon has not finished
            'ready',            -- payable AND has at least one importable service
            'blocked'           -- Stripe restricted the account, or KYC was rejected
        ));

COMMENT ON COLUMN provider.status IS
    'Whether the provider is sellable. Distinct from onboarding_state on '
    'purpose: onboarding says how far setup got, status says whether we will '
    'take money for it. Only ever set to active by the activation check.';

-- ------------------------------------------------------ the activation rule --
-- A provider must not be sellable unless it can be paid and has something to
-- sell. Enforced here rather than only in code because "active but unpayable"
-- is the single worst state this table can hold: it takes a customer's money
-- with nowhere to send it.
ALTER TABLE provider ADD CONSTRAINT provider_sellable_check
    CHECK (
        status <> 'active'
        OR (stripe_account_id IS NOT NULL AND payouts_enabled)
    );

CREATE INDEX provider_onboarding_idx ON provider (onboarding_state)
    WHERE onboarding_state <> 'ready';
