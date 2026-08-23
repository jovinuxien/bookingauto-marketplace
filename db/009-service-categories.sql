-- The list of what a salon sells, in one place.
--
--   docker exec -i bm-market-db psql -U market -d marketplace < db/009-service-categories.sql
--
-- It existed in four already: an enum in the landing module, the alternation in
-- its route pattern, an unconstrained text column here, and a configured
-- default in onboarding. Three copies of one fact, and a column not tied to any
-- of them.
--
-- The cost was not theoretical. Every service ever imported has
-- category_slug = 'har', because importServices writes the default for every
-- event type and nothing has ever written anything else -- so /frisor/{city}
-- works and /massage/{city} and /hudvard/{city} 404 for want of a single row,
-- and the sitemap correctly omits them, which is exactly why nobody noticed.
--
-- See ADR 0013.

-- --------------------------------------------------------- service_category --
-- Seeded here and nowhere else. No endpoint inserts a category, no import
-- creates one, and there is no admin screen: adding one stays what it was
-- before this table existed -- someone writing it down on purpose, in a file,
-- in a commit. What changes is that search and onboarding can now read it,
-- which they could not read out of a package-private enum in landing.
CREATE TABLE service_category (
    -- What goes in service.category_slug and into the search filter.
    slug        text        PRIMARY KEY,

    -- The URL segment, which is deliberately not the slug. The database says
    -- 'har'; the page is /frisor/stockholm, because that is what a person
    -- types. Confusing the two once pointed every canonical at a URL that 404s,
    -- which tells a search engine the real page is the one that does not exist.
    path        text        NOT NULL UNIQUE,

    -- Swedish, and the only place it is written down. Page titles, headings and
    -- anything shown to a customer come from here.
    label       text        NOT NULL,

    -- What customers actually type, as opposed to what we call it.
    --
    -- Two readers, and the second is the one that justifies the column. The
    -- query agent gets these in its prompt, which turns "balayage" from a guess
    -- into a lookup (ADR 0012 still overrules the model against the slug list
    -- afterwards -- synonyms make it right more often, not trusted). And the
    -- service import matches an event type's title against them, which needs no
    -- model at all and must not need one: provisioning does not put a third
    -- party on its path.
    --
    -- Lower case, without diacritics stripped. Matching is done in the
    -- application, which knows about Swedish folding; doing it here would mean
    -- a collation decision baked into the schema.
    synonyms    text[]      NOT NULL DEFAULT '{}',

    -- Presentation order, for lists that show all of them. Explicit rather than
    -- alphabetical: "Frisörer" first is a product decision, not a sorting one.
    sort_order  integer     NOT NULL,

    -- A category can be retired without deleting it, because service rows point
    -- at it and history should not rewrite itself. Inactive means "no new
    -- services, no page"; the existing rows keep resolving.
    active      boolean     NOT NULL DEFAULT true,

    created_at  timestamptz NOT NULL DEFAULT now()
);

-- The three that already existed, transcribed from LandingController.Category.
-- Slugs and paths are unchanged on purpose: renaming either would be a URL
-- change and a data migration, and neither is what this migration is for.
INSERT INTO service_category (slug, path, label, synonyms, sort_order) VALUES
    ('har', 'frisor', 'Frisörer', ARRAY[
        'frisor', 'frisör', 'klippning', 'klipp', 'harklippning', 'hårklippning',
        'fargning', 'färgning', 'balayage', 'slingor', 'slinga', 'toning',
        'permanent', 'harvard', 'hårvård', 'foning', 'föning', 'styling',
        'brudklippning', 'harforlangning', 'hårförlängning'
    ], 10),

    ('massage', 'massage', 'Massage', ARRAY[
        'massage', 'ryggmassage', 'nackmassage', 'klassisk massage',
        'taktil massage', 'idrottsmassage', 'triggerpunktsmassage',
        'gravidmassage', 'hotstone', 'kroppsmassage'
    ], 20),

    ('hud', 'hudvard', 'Hudvård', ARRAY[
        'hudvard', 'hudvård', 'ansiktsbehandling', 'ansikte', 'peeling',
        'harborttagning', 'hårborttagning', 'vaxning', 'fransar',
        'fransforlangning', 'fransförlängning', 'bryn', 'brynplock', 'akne'
    ], 30);

-- ------------------------------------------------------------ the constraint --
-- The point of the whole migration. A column of free text that every search
-- filters on is a column where one typo is a salon nobody can find, and the
-- present uniformity is not a constraint -- it is an accident of there being
-- one code path that writes it.
--
-- Safe to add as it stands: every existing row is 'har', which is seeded above.
-- The check is here rather than assumed, so a database that has diverged says
-- so instead of failing halfway through the ALTER.
DO $$
DECLARE
    unknown text;
BEGIN
    SELECT string_agg(DISTINCT s.category_slug, ', ')
      INTO unknown
      FROM service s
     WHERE NOT EXISTS (SELECT 1 FROM service_category c WHERE c.slug = s.category_slug);

    IF unknown IS NOT NULL THEN
        RAISE EXCEPTION
            'service rows reference categories that are not seeded: %. '
            'Add them to service_category above, or correct the rows, then re-run.',
            unknown;
    END IF;
END $$;

-- No ON DELETE. A category with services pointing at it must not be deletable,
-- and 'active = false' is how one is retired -- see the column comment.
ALTER TABLE service
    ADD CONSTRAINT service_category_fk
    FOREIGN KEY (category_slug) REFERENCES service_category (slug);

-- The FK gives Postgres no index on the referencing side, and the join from a
-- category to its services is what the landing pages do. service_category_idx
-- already covers active services; this covers the rest.
CREATE INDEX service_category_all_idx ON service (category_slug);
