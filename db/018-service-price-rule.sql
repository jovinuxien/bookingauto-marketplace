-- A service has a list price and zero or more rules.
--
--   docker exec -i bm-market-db psql -U market -d marketplace < db/018-service-price-rule.sql
--
-- ADR 0016, phase 3. service.price_minor stays what it was -- the list
-- price, imported from Cal, charged to a car nobody knows. A rule says:
-- for a car matching these constraints, the price is this instead. Most
-- specific rule wins; ties go to the cheaper; no match means the list
-- price, so a service with no rules behaves exactly as it does today.
--
-- Written only here (ADR 0003). The import from Cal never touches this
-- table, and re-importing never deletes a rule.
CREATE TABLE service_price_rule (
    id               bigserial   PRIMARY KEY,
    service_id       bigint      NOT NULL REFERENCES service (id) ON DELETE CASCADE,

    -- The constraints. Null means "any". At least one must be set, which
    -- the application checks: a rule that matches every car is a list
    -- price with extra steps.
    make             text,
    model_prefix     text,
    model_year_from  integer,
    model_year_to    integer,
    rim_inches_from  integer,
    rim_inches_to    integer,

    price_minor      integer     NOT NULL CHECK (price_minor > 0),

    -- What the customer sees next to the price: "16-17 tum", "Volvo 2015-2019".
    label            text        NOT NULL,

    created_at       timestamptz NOT NULL DEFAULT now(),

    CHECK (model_year_from IS NULL OR model_year_to IS NULL OR model_year_from <= model_year_to),
    CHECK (rim_inches_from IS NULL OR rim_inches_to IS NULL OR rim_inches_from <= rim_inches_to)
);

CREATE INDEX service_price_rule_service_idx ON service_price_rule (service_id);
