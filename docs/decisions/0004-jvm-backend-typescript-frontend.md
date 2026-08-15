# 4. Spring on the backend, TypeScript on the frontend

**Status:** accepted

## Context

Cal.diy is TypeScript. The obvious inference is that everything around it should
also be TypeScript, for shared types and one language across the stack.

That inference is weaker than it looks. We interact with Cal over **HTTP and
webhooks** — its v2 API ships in the MIT repo under `apps/api/v2`, so it is
reachable self-hosted with no vendor dependency. An HTTP boundary is a language
boundary. Nothing about it obliges the caller to be TypeScript.

Meanwhile the domain on our side of that boundary is payments, a ledger, POS
capture against certified cash registers, settlement, and encrypted treatment
journals. That is transactional, audited, regulated work.

## Decision

**Backend services: Spring Boot (JVM).** Frontends: TypeScript.

The split follows the boundary that already exists rather than cutting across
it.

## Why the JVM for the backend

- The hard parts here are transactions, money and audit. Spring Data, declarative
  transactions, Spring Batch for settlement runs and Spring Security are mature
  in exactly those places, and the failure modes are well understood.
- Swedish accounting, kassaregister and banking integrations are long-lived,
  compliance-bound systems; the JVM is well represented there.
- Team fluency is a first-order input, not a detail. An unfamiliar runtime costs
  more than shared types save.

## Why TypeScript for the frontend, without exception

- The consumer site must be server-rendered: city-plus-service landing pages are
  how a marketplace like this is found, so SEO is a functional requirement.
- Cal ships embeddable React components. The business calendar — the screen a
  salon lives in all day — is the one place where reusing Cal's own UI is worth
  far more than owning it.

## What we give up

Shared types with Cal. Mitigated by generating a client from Cal's OpenAPI
description and keeping it inside `sync`, so exactly one component knows Cal's
shape and drift shows up as a compile error there rather than at runtime
everywhere.

## Start as a modular monolith, not four microservices

The earlier sketch and the incoming blueprint both say "microservices". For a
marketplace with no users yet that is a cost with no matching benefit:
distributed transactions, four deploy pipelines and cross-service debugging,
bought before there is any load to justify them.

Use **Spring Modulith**: one deployable, enforced module boundaries along the
same seams — `marketplace`, `search`, `payments`, `sync`. The boundaries are
checked at build time, so splitting one out later is a deployment change rather
than a rewrite. Split when a module has its own scaling curve; `search` will be
first.
