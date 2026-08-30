# 19. Messages ride the booking, and the mailbox stays the account

**Status:** accepted

## Context

"Can I bring the wheels the evening before?" — the question every workshop
gets, and the reason Lasingoo has messaging. Without it, the customer's
only channel is the phone, and the marketplace never sees the conversation
it brokered.

The temptation is chat: presence, sockets, unread badges, an inbox. All of
it presumes an account, and this marketplace deliberately has none for
consumers (ADR 0014) — the mailbox is the account, and the signed link in
the confirmation mail is the credential.

## Decision

**A message thread per booking, and nothing else.** The customer writes
from the booking page they already reach through the mailed link; the
token in the request body is the proof, exactly as it is for cancel,
reschedule and review. The provider reads and replies in the console,
scoped by the session as everything there is. There is no thread without a
booking, which is also the spam defence: writing to a workshop costs a
paid appointment.

**Delivery is the outbox, not a socket.** Each message enqueues one mail
to the other party — the customer's carries the booking link, the
provider's points at the console — deduped per message. Both sides already
live in their mail; a "real-time" channel would be presence theatre for a
conversation whose natural cadence is hours. The thread on the page is the
history; the mail is the notification.

Plain text, 1–2 000 characters, stored as written. No attachments — a
photo of the squeaking brake is what the visit is for.

## What this does not do

No provider-initiated threads (the booking's mails already carry
everything the workshop needs to say uninvited). No read receipts, no
typing indicators, no unread state beyond a count in the console list. No
moderation queue — the parties have a transaction between them and each
other's names; this is e-mail with a shared history, not a social surface.

## Watch items

- A thread outlives its booking's relevance. Messages on a booking whose
  appointment is long past still notify; if that turns into noise, the
  ceiling to add is time-based, not count-based.
- The excerpt in the notification mail is customer-written text in a mail
  we send. It is plain-texted and truncated; the day HTML mail arrives,
  escaping is the thing to re-check.
