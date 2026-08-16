package se.marketplace.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import se.marketplace.sync.CalPort.DayAvailability;
import se.marketplace.sync.CalPort.Slot;

/**
 * Tests for the one piece of logic in sync that is neither IO nor glue.
 *
 * <p>Turning slots into day rows is where a wrong answer is both easy to write
 * and invisible afterwards: nothing crashes, the index simply describes a
 * different salon's week than the one Cal knows about.
 */
class AvailabilityReconcilerTest {

	private static final ZoneId ZONE = ZoneId.of("Europe/Stockholm");

	private static Instant at(String isoLocal) {
		return LocalDate.parse(isoLocal.substring(0, 10))
			.atTime(Integer.parseInt(isoLocal.substring(11, 13)), 0)
			.atZone(ZONE)
			.toInstant();
	}

	private static Slot slot(String isoLocal) {
		return new Slot(at(isoLocal));
	}

	private static DayAvailability dayOf(List<DayAvailability> days, String date) {
		return days.stream()
			.filter(d -> d.day().equals(LocalDate.parse(date)))
			.findFirst()
			.orElseThrow();
	}

	@Test
	@DisplayName("a day with no slots is written as no capacity, not left out")
	void emptyDaysAreWrittenExplicitly() {
		List<DayAvailability> days =
			AvailabilityReconciler.summarise(List.of(), at("2026-08-17T09"), 3);

		assertThat(days).hasSize(3);
		assertThat(days).allSatisfy(day -> {
			assertThat(day.hasCapacity()).isFalse();
			assertThat(day.freeSlots()).isZero();
			assertThat(day.firstFreeAt()).isNull();
		});
	}

	@Test
	@DisplayName("slots land in the right part of the day, in Stockholm time")
	void bucketsByPartOfDay() {
		List<DayAvailability> days = AvailabilityReconciler.summarise(
			List.of(slot("2026-08-17T09"), slot("2026-08-17T14"), slot("2026-08-17T18")),
			at("2026-08-17T00"), 1);

		DayAvailability day = dayOf(days, "2026-08-17");

		assertThat(day.hasCapacity()).isTrue();
		assertThat(day.freeSlots()).isEqualTo(3);
		assertThat(day.freeMorning()).isTrue();
		assertThat(day.freeAfternoon()).isTrue();
		assertThat(day.freeEvening()).isTrue();
	}

	@Test
	@DisplayName("an afternoon-only day does not claim a free morning")
	void doesNotOverstateAvailability() {
		List<DayAvailability> days = AvailabilityReconciler.summarise(
			List.of(slot("2026-08-17T14"), slot("2026-08-17T15")),
			at("2026-08-17T00"), 1);

		DayAvailability day = dayOf(days, "2026-08-17");

		assertThat(day.freeAfternoon()).isTrue();
		assertThat(day.freeMorning()).isFalse();
		assertThat(day.freeEvening()).isFalse();
	}

	@Test
	@DisplayName("first free is the earliest slot, not the first one Cal happened to return")
	void firstFreeIsEarliest() {
		List<DayAvailability> days = AvailabilityReconciler.summarise(
			List.of(slot("2026-08-17T16"), slot("2026-08-17T10"), slot("2026-08-17T13")),
			at("2026-08-17T00"), 1);

		assertThat(dayOf(days, "2026-08-17").firstFreeAt()).isEqualTo(at("2026-08-17T10"));
	}

	@Test
	@DisplayName("a late evening slot stays on its own local day")
	void doesNotSpillAcrossMidnightInLocalTime() {
		// 23:00 Stockholm is 21:00 UTC. Bucketing on the UTC date would be
		// correct here and wrong in winter; bucketing on the local date is
		// right in both, which is why ZONE exists.
		List<DayAvailability> days = AvailabilityReconciler.summarise(
			List.of(slot("2026-08-17T23")), at("2026-08-17T00"), 2);

		assertThat(dayOf(days, "2026-08-17").hasCapacity()).isTrue();
		assertThat(dayOf(days, "2026-08-18").hasCapacity()).isFalse();
	}

}
