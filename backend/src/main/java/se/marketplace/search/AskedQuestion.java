package se.marketplace.search;

import java.time.LocalDate;

/**
 * What someone typed, and where they were standing.
 *
 * <p>The text is the only part a model ever sees. Coordinates come from the
 * browser and the radius from the UI, and neither is up for interpretation — a
 * model that could move the user would be able to answer "salons near me" with
 * a different "me".
 *
 * <p>{@code today} is passed in rather than read inside the agent so that "on
 * Saturday" resolves against a date the caller can state and a test can fix.
 */
public record AskedQuestion(
	String text,
	double latitude,
	double longitude,
	int radiusMetres,
	LocalDate today,
	/**
	 * How far ahead the availability index actually reaches. A day beyond it has
	 * no rows and never will, so a query for one is refused rather than run:
	 * an empty result looks exactly like a fully booked city.
	 */
	int horizonDays,
	int limit
) {}
