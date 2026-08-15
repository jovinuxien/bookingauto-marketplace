# 6. PostGIS first, OCSS when there are queries to tune

**Status:** accepted

## Context

We have a local checkout of the Open Commerce Search Stack (OCSS) — Apache-2.0,
Java/Spring services over Elasticsearch, Lucene and Querqy, built for e-commerce
product search. It is in our ecosystem, and the team has real search background.
The question is whether it should be the search layer for this marketplace.

## Two search problems that get conflated

They have opposite characteristics, and one component serving both serves
neither well.

**The filter problem.** "Near me, free Saturday afternoon." Geo plus time. Almost
no text. **High churn** — availability changes with every booking. Selectivity
comes from distance and capacity.

**The relevance problem.** "balayage", "massage ryggont", a salon by name.
Synonyms, misspellings, Swedish and English mixed, ranking, facets, typeahead.
**Low churn** — the service catalogue changes rarely.

## What OCSS actually does and does not do

Checked against the source, not the marketing:

- Field types are `STRING, NUMBER, CATEGORY, RAW`. **There is no geo type.**
- Zero occurrences of `geo_distance`, `GeoPoint` or `latitude` anywhere in the
  Java source.
- Facets are hierarchical, interval and range — no distance faceting or sorting.

Geo is the **primary filter of this entire product**, and OCSS has none of it.
The plugin SPI (`ESQueryFactory`, `RescorerProvider`, `CustomFacetCreator`) means
geo could be injected — but that is extending an abstraction to do the thing it
was not designed for, and it puts us on a fork of a 45-star project for our most
important query.

Conversely OCSS is genuinely strong at the relevance problem, and Querqy query
rewriting is exactly the tool for treatment-name synonyms in two languages.

## Decision

**Launch on PostGIS alone.** Geo, availability and category browse. No
Elasticsearch, no OCSS, no extra services.

**Introduce OCSS in phase two**, for free-text discovery and typeahead, once
there are query logs.

## Why not both from the start

Querqy rules, synonyms and relevance tuning are worth a great deal — *given real
queries to tune against*. Deployed before launch they are tuned blind, against
guesses about what Swedish customers type. Meanwhile OCSS adds four Spring
services plus an Elasticsearch cluster to operate for a marketplace with no
users.

The honest counterweight: our search background lowers that operational cost
compared with a team meeting Elasticsearch for the first time. It lowers it. It
does not remove it, and it does not create query data that does not exist yet.

## The seam

`search` exposes one port with two implementations behind it.

1. `PostgisSearch` — geo, availability, category. Launch.
2. `CompositeSearch` — OCSS answers the text query and returns ranked service
   ids; PostGIS filters those by geo and availability and re-sorts by distance.
   Text is usually the more selective side when present, so query OCSS first.

Adding the second is then a wiring change, not a rewrite.

## Watch items

- Our checkout is at 0.73.0 (September 2025); upstream has moved on. Rebase
  before building anything on it.
- 45 stars and 5 forks is a real bus-factor risk. Apache-2.0 and Java mean we
  *can* maintain it ourselves — decide deliberately whether we would.
