/**
 * Telling people what happened.
 *
 * <p>An outbox rather than a mail queue, for the same reason the booking funnel
 * is a saga: there is no transaction spanning this database and an SMTP server.
 * A message is written in the same transaction as the event that owes it, and
 * delivered afterwards — so a crash between the two loses nothing, and the
 * worst case is a late email rather than a missing one.
 *
 * <p><strong>Cal sends nothing today.</strong> Its image has no {@code sendmail}
 * binary, so its own confirmations fail silently — which is why a customer who
 * books currently hears nothing at all. If Cal is ever given working SMTP, the
 * two will both send and the duplicate has to be resolved deliberately rather
 * than discovered.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Notifications")
package se.marketplace.notifications;
