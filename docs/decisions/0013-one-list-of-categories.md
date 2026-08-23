# 13. One list of categories, and it is a table

**Status:** accepted

**Supersedes** the reasoning in `LandingController.Category`'s javadoc, which is
quoted and answered below rather than deleted — it was right about the thing it
was defending, and that thing survives.

## Context

The list of what a salon sells exists in four places, and no two of them are the
same list.

| where | what it holds | who can read it |
|---|---|---|
| `LandingController.Category` | `frisor`/`har`/"Frisörer", and two more | `landing` only — it is package-private, and rightly |
| `@GetMapping("/{category:frisor\|massage\|hudvard}/{city}")` | the same three paths, as a string | the router |
| `service.category_slug` | free text, `NOT NULL`, no constraint | everybody |
| `marketplace.onboarding.default-category` | `har` | `onboarding` |

Three copies of one fact plus a column that is not constrained to any of them.

### What that costs today, measured rather than argued

Every service this system has ever imported has `category_slug = 'har'`, because
`importServices` writes the configured default for every event type and nothing
has ever written anything else.

So `/frisor/{city}` works, and **`/massage/{city}` and `/hudvard/{city}` cannot
exist**. `cityCategory` 404s when a category has no salons, and neither of those
categories has ever had one. The sitemap only emits pages that have providers,
so it correctly omits them — which is exactly why nobody noticed. Two of the
three indexable page types this system was built to rank for have been
unreachable since they were written, and every signal we have says everything is
fine.

The same fact bites the query agent from the other side. ADR 0012 grounds the
model against `SELECT DISTINCT category_slug`, which is honest about what
exists and is currently the one-element set `{har}`. The agent is therefore
incapable of proposing "massage" no matter what a customer types, and the
grounding step would be right to refuse it if it did.

## The objection, which is a good one

> An enum rather than a table because a category is a URL and a piece of copy
> before it is data. Adding one is a deliberate act — a new indexable page — not
> a row someone inserts by accident.

Both halves are correct and neither is an argument for the enum.

**A category is a URL and a piece of copy.** Yes — and it is also a filter in
search, a vocabulary for the agent, and a column in `service`. It being copy
does not make it *only* copy, and the module that owns the copy is not the
module that owns the filter. `search` cannot see `landing`'s enum and must not
be given a way to; the alternative to a table is a second hard-coded list in
`search` that has to agree with the first one forever.

**Adding one must be deliberate.** Also yes, and the table keeps that. Categories
are seeded by migration. **No endpoint inserts one, no import creates one, and
there is no admin screen** — adding a category stays what it was: someone
writing it down on purpose, in a file, in a commit. What changes is who can then
read it.

The property the enum was defending was deliberateness. It was achieving it with
inaccessibility, and those are separable.

## Decision

**One `service_category` table, in a `categories` module, and
`service.category_slug` references it.**

The foreign key is the point. A column of free text that everything filters on
is a column where a typo is a salon nobody can find, and today's uniformity is
not a constraint — it is an accident of there being one code path that writes it.

### The URL surface stays compiled in

The route pattern keeps its literal alternation. Making it dynamic would mean
`/{category}/{city}`, and the existing comment already says why that is wrong:
an unbounded two-segment mapping sits in front of every other two-segment path
in the application and shadows one the day it is added. That reasoning is
untouched by any of this.

Instead the alternation becomes a `static final String` used both in the
annotation — it is still a compile-time constant — and in a startup check
against the table. A category with no route, or a route with no category, is
logged loudly at boot in both directions. Adding a category remains a
two-part deliberate act; what is new is that getting half of it wrong is
noticed at startup rather than by a 404 nobody is watching.

The check warns and does not fail. Data must not be able to stop the
application starting, for the same reason an absent API key must not (ADR 0012).

### Synonyms, which is what makes it worth doing now

The table carries what customers actually *type* alongside the slug they mean:
`balayage`, `slingor` and `klippning` are `har`; `ansiktsbehandling` is `hud`.

This is the answer to ADR 0012's watch item. The agent's prompt stops being a
list of three opaque slugs and becomes a list of categories with their Swedish
names and the words people use for them, which turns "balayage" from a guess
into a lookup. The grounding step is unchanged and still overrules the model
against the closed set — the synonyms make the model right more often, they do
not make it trusted.

They earn their place twice, because the second use needs no model at all.

### Import classifies instead of defaulting

`importServices` matches the Cal event type's title against the synonyms and
falls back to the configured default when nothing matches. Longest synonym
first, so "taktil massage" beats "massage".

Deterministic on purpose. This runs during provisioning, where ADR 0011 already
establishes that we do not put third parties on the path, and a salon's
categories should not depend on a model being reachable or on our having paid
for one. It is also simply good enough: salons name event types "Klippning
dam 45 min", and that is a substring match, not a comprehension problem.

The default stays for what does not match. A service with a category nobody
chose is worse than one in the general bucket — but only if the bucket is
honest, so the fallback is a real category rather than a guess dressed as one.

## What this does not do

- **It does not reclassify the four existing services.** They are all `har`,
  they are all hairdressing, and the migration leaves them alone. Backfilling
  by pattern-matching four rows an operator can look at would be a script
  written to avoid a five-minute conversation.
- **It does not add categories.** The three that exist are the three the
  landing pages already promised. Deciding the taxonomy of a Swedish beauty
  marketplace is a product question and this is not the ADR for it.
- **It is not a hierarchy.** No parent, no children, no facets. A flat list is
  what search filters on and what the URLs express, and the day that stops being
  true is the day to look at ADR 0006's phase two rather than at this table.

## Watch items

- **Synonyms are a list somebody has to maintain**, and the failure mode is
  silent: a word nobody thought of makes the agent fall back to no category,
  which widens the search rather than breaking it. That is the right failure,
  and it is still a failure nobody is counted. The queries that produced no
  category are worth logging once there are any.
- **The fallback default hides misclassification.** Everything unmatched
  becomes `har`, which is correct for a hairdressing marketplace today and will
  quietly be wrong the first time a massage-only salon onboards. It is visible
  in the import result, which is returned to the operator; nothing yet makes
  them look.
- **`db/009` is applied by hand**, like everything from 002 onwards, and adds a
  foreign key. A deployment that runs the application before the migration gets
  imports that fail rather than imports that silently write a category that
  does not exist — which is the right order of badness, but it is an ordering.
