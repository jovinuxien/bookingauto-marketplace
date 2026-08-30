# 18. The widget shows times, and hands off at the booking

**Status:** accepted

## Context

A workshop with its own website wants its own site to be where customers
book. Lasingoo sells this as a widget on every tier, and it earns its place
twice over: it is the reason a provider joins before the marketplace sends
them anyone (free online booking on the site they already have), and every
booking through it still runs through our funnel — Cal holds the slot, the
card is charged, the commission is taken, the work-order mail goes out.

The question is how much of the flow lives inside the third-party page. An
iframe on someone else's origin is a hostile place for the end of our
funnel: browsers partition third-party cookies, Swish bounces through an
app and back to a URL the iframe does not own, and 3-D Secure redirects
inside frames are exactly the flows banks break first. A widget that takes
payment inside the iframe would work in the demo and fail on the phone in
the customer's hand.

## Decision

**The widget is an embedded storefront, not an embedded checkout.** It
shows the workshop's services, live free times from Cal, and — for vehicle
services — asks for the registration number, exactly like the marketplace
page. Choosing a time opens the marketplace checkout in a new tab, with the
service, the slot, the plate and the quoted price carried in the URL, and
the payment happens where payments work.

One page (`/widget/{slug}`), framed. It is the only path on the site that
may be framed: everything else keeps `X-Frame-Options: DENY`, and the
widget page answers `Content-Security-Policy: frame-ancestors *` instead —
any site may embed it, because it contains nothing but what the public
provider page already shows, and holds no session.

**Bookings that start in the widget say so.** The hand-off carries
`kanal=widget`, checkout passes it on, and the funnel writes it to a
`channel` column on the attempt and the booking — validated against a
closed set, defaulted to `marketplace`, and never trusted for anything but
reporting. It is what makes "how many bookings did the widget bring"
answerable the day a provider asks, and that day is the day pricing tiers
get discussed.

The embed is one line, shown in the console:
`<script src=".../widget.js" data-verkstad="{slug}"></script>` — the script
injects the iframe and sizes it. An iframe pasted by hand works identically.

## What this does not do

- **No payment in the iframe**, for the reasons above; the day Stripe's
  embedded components make this safe is the day to revisit.
- **No styling API.** The widget looks like the marketplace. A theming
  contract is a support surface; one good default is a feature.
- **No per-widget keys or registration.** The page is public data on a
  public URL; a key would protect nothing and add a step.

## Watch items

- `channel` is client-supplied and spoofable. It decides reporting, never
  money or access; if a tier ever prices by channel, that is the moment it
  must become server-derived (a signed widget referrer), not before.
- The new tab is a hand-off the customer can feel. If drop-off between
  widget and checkout turns out to matter, the number to watch is written
  down in `channel` before the first widget ships.
