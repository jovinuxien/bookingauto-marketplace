# 12. An agent framework, and where it may not go

**Status:** accepted

## Context

ADR 0006 split search into two problems and shipped one of them. The filter
problem — "near me, free Saturday afternoon" — is answered by PostGIS today. The
relevance problem — "balayage", misspellings, Swedish and English in the same
sentence — was deferred to OCSS, on the explicit condition that we would have
query logs to tune against before paying for an Elasticsearch cluster.

That condition has not been met and will not be met soon, because the thing that
would produce the query logs is the free-text box we have not built. The
deferral was right and it is also a deadlock.

There is a third option ADR 0006 did not consider, because in 2025 it was not
yet a boring one: a language model can read "balayage på lördag eftermiddag" and
return the structured filter PostGIS already accepts. That is not the OCSS
capability — it does no ranking, it has no index, it will not do typeahead. It
converts text into the parameters we already serve, which is the half of the
relevance problem that unblocks the other half.

## Decision

**Adopt Embabel as the agent framework, and confine what an agent is allowed to
decide.**

The framework choice is the small half of this decision. The confinement is the
rest of it.

### Embabel rather than Spring AI directly

Embabel sits on Spring AI, so this is not a choice between them; it is a choice
about whether to write the orchestration ourselves.

The property that matters is that Embabel's planner is deterministic. Goals,
actions and preconditions are declared in Java, and a GOAP planner — ordinary
code, not a model — chooses the sequence. The model is invoked *inside* an
action and nowhere else. A framework where the model chooses the control flow
would be the wrong shape for this codebase: the booking funnel is a state
machine with enumerated compensations precisely because we wanted to be able to
say what happens next, and an agent that plans its own steps gives that up in
exchange for flexibility we have no use for.

So the non-determinism has exactly one location, and it is small enough to test
around.

### Agents live in the module that owns their domain

`QueryUnderstandingAgent` is in `search`, not in an `agents` module.

`@Agent` is a Spring stereotype, so an agent is a component-scanned bean and
belongs to whatever module its package sits in — the boundary test will treat it
like any other class, which is what we want. A dedicated `agents` module would
become the second module after `booking` that knows every other module exists,
and `booking` earns that: it is the one place three external authorities have to
agree. A query parser does not earn it.

A small shared `ai` module holds the enablement gate and which model does which
kind of work. It is in `sharedModules` because otherwise every module that ever
grows an agent has to declare a dependency on it, which says nothing.

### Grounding: the model chooses from a closed set, or is overruled

The agent is handed the distinct category slugs that actually exist in the
`service` table, and its answer is checked against that set afterwards. A
category outside it is dropped, not passed through.

This is the same rule the geocoder already follows, for the same reason. A
geocoder asked for an address it cannot find answers with the city centre, and a
point wrong by kilometres is worse than no point at all because a null is
visibly missing and a wrong coordinate is not. A model asked to categorise
"balayage" against a vocabulary it cannot see will invent `harfargning`, which
is a perfectly plausible slug that matches no row. The search returns nothing,
the customer concludes the salon has no availability, and nothing anywhere
records that a filter was applied that no human chose.

Dropping the value is not a fallback path. It is the design: the model proposes,
`search` disposes, and what was dropped is returned to the caller rather than
swallowed.

### What the caller is told

`/api/search/ask` returns the interpretation alongside the hits, in the same
spirit as `indexAgeSeconds`. Someone who typed a sentence and got six salons is
entitled to know that we read it as "hår, Saturday, afternoon, 5 km" — because
sometimes we will have read it wrong, and a filter the user cannot see is a
filter they cannot correct.

## What an agent may never do

Not guidance. The list.

| | why |
|---|---|
| **Move money, or decide what anything costs** | ADR 0003. The commission is frozen onto an attempt at quote time so that a *rate change* cannot alter a sale in flight; a model is a much livelier source of variation than a rate change. |
| **Confirm, cancel or compensate a booking** | The funnel's compensations are the paths that run when something has already gone wrong. They are enumerated and tested, including the ones that run after a compensation itself fails. There is no version of that argument that survives a probabilistic step. |
| **Write to Cal or Stripe** | ADR 0011's ordering — nothing outside our database exists until an address is verified — exists because we cannot un-create things at other companies. |
| **Write anything without a person approving it** | An agent may propose. An operator commits. |
| **Be on the critical path of a request that must succeed** | Every agent call needs a defined answer for "the model was slow, absent, or wrong". For search that answer is the unfiltered geo query, which is what the endpoint does today. |

The first four are permanent. The fifth is what makes search a safe first
subject: the fallback is the product we already ship.

## Versions, and why not the newest one

Embabel's line splits:

| Embabel | Spring AI | Spring Boot |
|---|---|---|
| 1.0.0 | 1.1.7 | 3.5.x |
| 1.5.0 | 2.0.0 | 4.1.x |

We were on Spring Boot 3.3.5, so neither worked as it stood. **We take Embabel
1.0.0 and move to Boot 3.5**, with Spring Modulith 1.2.4 → 1.4.12 to match.

Boot 4 is the larger and more interesting upgrade, and it is a different
project. It carries Jackson 2 → 3 through every Stripe webhook payload, the
tRPC response reading in `sync`, and every DTO; and Spring Security 6 → 7
through the console's deny-by-default chain. Doing it in the same change as the
first agent means a failure has two suspects. Doing it at all is a decision
about our own stack, not about agents, and should be made on that basis.

## Cost, which is a new kind of cost here

Every dependency until now has been something we run or something we call for
free. This one is metered per request, on the busiest endpoint in the product.

So the gate defaults to off — `marketplace.ai.enabled: false` — for the same
reason `marketplace.geocoding.provider` defaults to `none`: enabling it by
default points every developer machine and every CI run at a metered third
party, and being surprised by that is not something to discover on an invoice.
Free-text search is opt-in per deployment, and the plain filter endpoint is
untouched by any of this.

## Watch items

- ~~`/api/search/ask` has no rate limit.~~ **Done.** 60 interpreted searches per
  hour per socket address, counted by the limiter that moved out of `signup`
  into its own module the moment it had a second caller. Over the limit is not
  a 429: the search still runs, unfiltered, because what is being protected here
  is an invoice rather than a resource, and the customer is still owed the
  salons. That is also why the number can be generous where ADR 0011's had to be
  small — signup was defending accounts at Cal and Stripe, where being wrong is
  not ours to undo.
- **Latency.** Search is currently a single indexed PostGIS query. An LLM call
  in front of it is two orders of magnitude slower. `/api/search/ask` is a
  separate endpoint from `/api/search` partly so this is measurable rather than
  averaged into the number we already have.
- **The vocabulary is one column of free text.** `service.category_slug` has no
  reference table and `onboarding` defaults it to `har`. Grounding against
  `SELECT DISTINCT` works and is honest about what exists, but it means the
  vocabulary is whatever salons happened to import. That is a data-model
  question worth answering on its own terms, and this ADR does not answer it.
- **Kotlin throws checked exceptions through Java signatures.** A failed model
  call arrives as `java.util.concurrent.ExecutionException` — checked — from a
  method that declares nothing, so the original `catch (RuntimeException)`
  compiled, read as thorough, and produced HTTP 500 from the one endpoint whose
  whole design is that it cannot fail. Found by running it with a bogus key. The
  invocation now sits behind a seam declared `throws Exception` so the compiler
  insists on the catch that holds; assume the same trap anywhere else this
  codebase calls into Embabel.
- **This is not OCSS.** No ranking, no typeahead, no synonym rules, no index.
  When there are query logs, ADR 0006's phase two is still the answer to the
  problem it describes — and the queries logged here are how we get them.
