# 17. An add-on is a price, not a length

**Status:** accepted

## Context

Lasingoo's workshops sell extras with a job — spolarvätska with a tyre
change, a filter with a service — and a salon does the same with a
treatment. Customers expect to tick them at checkout and pay once. The
question is what an add-on is allowed to change.

Two things could change: the price and the duration. The price is ours to
write (ADR 0003). The duration is not: Cal owns time (ADR 0001), a Cal
event type has exactly one length, and the slot we hold is that length.
An add-on that took twenty minutes would need either a second event type
per combination — combinatorial, and invisible to the import — or a
reservation of a different length than the event type says, which Cal does
not offer and we would not want to fake.

## Decision

**An add-on changes the price and nothing else.** It is a named line with
a price, offered per service, ticked at checkout, and frozen onto the
attempt and the booking by name and price the way the quote is. The total
— quote plus add-ons — is what is charged, what the commission is taken
on, and what the page must have shown (ADR 0016's 409 applies to the
total).

Anything that takes time is a service. A workshop that wants "däckskifte
med hjulinställning" makes an event type of that length in Cal and it
imports like any other. This is a limit the console states in one line
rather than a rule a provider has to discover.

Retired, not deleted: a booking that chose an add-on keeps its own copy,
and the row stays inactive for the history that points at it.

## Consequences

The funnel learns one more term in the sum and one more thing the client
may send that it must not trust: ids, never prices. Everything downstream
already reads the frozen total. The work-order mail and the console list
the extras, because they are the part the person in the workshop has to
fetch from the shelf.
