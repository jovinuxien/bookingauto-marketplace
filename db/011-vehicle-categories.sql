-- A second industry, as four rows.
--
--   docker exec -i bm-market-db psql -U market -d marketplace < db/011-vehicle-categories.sql
--
-- ADR 0013 moved the list of categories into a table so that adding one would
-- be a row and a word in LandingController.CATEGORY_PATHS rather than a change
-- in three modules. This is the first time that is asked of it for something
-- that is not a salon. Nothing else in the system learns that cars exist.
--
-- Sort order starts at 100 so these follow the beauty categories wherever all
-- of them are listed, with room between the blocks for what comes next.
--
-- Synonyms are written the way a Swede writes them; the application folds
-- diacritics on the way out (Categories.fold), so 'däckbyte' also matches
-- 'dackbyte'. Both spellings are given only where the unfolded form is not a
-- plain substring of the other.
--
-- Two words are deliberately absent. 'vaxning' stays with hud -- a detailer's
-- wax is 'lackskydd' or 'polering' here. 'punktering' is not claimed by dack,
-- because a bicycle shop names an event type "Punktering" too, and a bike
-- landing in dack is worse than it landing in the default.
--
-- See ADR 0015.

INSERT INTO service_category (slug, path, label, synonyms, sort_order) VALUES
    ('dack', 'dackbyte', 'Däckbyte', ARRAY[
        'däck', 'däckbyte', 'däckskifte', 'hjulskifte', 'hjulbyte',
        'sommardäck', 'vinterdäck', 'dubbdäck', 'däckhotell', 'däckförvaring',
        'hjulförvaring', 'balansering', 'däckreparation', 'däckmontering',
        'nya däck', 'fälg', 'fälgar'
    ], 100),

    ('bilservice', 'bilservice', 'Bilservice', ARRAY[
        'bilservice', 'service', 'oljebyte', 'oljeservice', 'bromsar',
        'bromsbyte', 'bromsservice', 'kamrem', 'kamremsbyte', 'ac-service',
        'ac service', 'luftkonditionering', 'klimatanläggning', 'felsökning',
        'diagnos', 'diagnostik', 'förbesiktning', 'besiktning', 'avgas',
        'batteri', 'batteribyte', 'hjulinställning', 'fyrhjulsinställning',
        'verkstad', 'bilverkstad', 'reparation'
    ], 110),

    ('bilvard', 'bilvard', 'Bilvård', ARRAY[
        'bilvård', 'rekond', 'rekonditionering', 'biltvätt', 'handtvätt',
        'polering', 'lackpolering', 'lackskydd', 'keramiskt lackskydd',
        'lackförsegling', 'invändig rengöring', 'interiörrengöring',
        'läderrengöring', 'ozonbehandling', 'motortvätt'
    ], 120),

    ('bilglas', 'bilglas', 'Bilglas', ARRAY[
        'bilglas', 'vindruta', 'vindrutebyte', 'rutbyte', 'stenskott',
        'stenskottslagning', 'bakruta', 'sidoruta', 'glasbyte'
    ], 130);
