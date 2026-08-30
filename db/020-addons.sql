-- Something extra, chosen at checkout.
--
--   docker exec -i bm-market-db psql -U market -d marketplace < db/020-addons.sql
--
-- An add-on is a line the provider offers with a service -- spolarvätska
-- with a däckskifte, hårinpackning with a klippning -- that the customer
-- ticks at checkout and pays for up front with the rest. It changes the
-- price and nothing else: the Cal event type has one length, and time is
-- Cal's (ADR 0001), so anything that takes extra minutes is a separate
-- service, not an add-on. See ADR 0017.
--
-- What was chosen is copied onto the attempt and the booking by name and
-- price, the way the quote is: a later edit to the add-on must not rewrite
-- what was sold.
CREATE TABLE service_addon (
    id          bigserial   PRIMARY KEY,
    service_id  bigint      NOT NULL REFERENCES service (id) ON DELETE CASCADE,
    name        text        NOT NULL CHECK (length(name) BETWEEN 1 AND 80),
    price_minor integer     NOT NULL CHECK (price_minor >= 0),
    active      boolean     NOT NULL DEFAULT true,
    sort_order  integer     NOT NULL DEFAULT 0,
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX service_addon_service_idx ON service_addon (service_id) WHERE active;

CREATE TABLE booking_attempt_addon (
    attempt_id  bigint  NOT NULL REFERENCES booking_attempt (id) ON DELETE CASCADE,
    addon_id    bigint  REFERENCES service_addon (id) ON DELETE SET NULL,
    name        text    NOT NULL,
    price_minor integer NOT NULL,
    PRIMARY KEY (attempt_id, name)
);

CREATE TABLE booking_addon (
    booking_id  bigint  NOT NULL REFERENCES booking (id) ON DELETE CASCADE,
    addon_id    bigint  REFERENCES service_addon (id) ON DELETE SET NULL,
    name        text    NOT NULL,
    price_minor integer NOT NULL,
    PRIMARY KEY (booking_id, name)
);
