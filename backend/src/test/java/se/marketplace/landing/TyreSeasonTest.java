package se.marketplace.landing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The four seasons, and the day each one turns.
 *
 * <p>The boundaries are the point: a page that says "hurry, 1 December" on
 * 1 December is wrong in the way that makes people stop trusting it.
 */
class TyreSeasonTest {

	@Test
	@DisplayName("October and November are the autumn window, against 1 December")
	void autumn() {
		var notice = TyreSeason.on(LocalDate.of(2026, 10, 1));
		assertThat(notice.heading()).isEqualTo("Dags för vinterdäck");
		assertThat(notice.deadline()).isEqualTo(LocalDate.of(2026, 12, 1));
		assertThat(notice.deadlineText()).isEqualTo("1 december");

		assertThat(TyreSeason.on(LocalDate.of(2026, 11, 30)).heading()).isEqualTo("Dags för vinterdäck");
	}

	@Test
	@DisplayName("December to March is winter, against 15 April the following spring")
	void winter() {
		var december = TyreSeason.on(LocalDate.of(2026, 12, 1));
		assertThat(december.heading()).isEqualTo("Vinterdäck gäller");
		assertThat(december.deadline()).isEqualTo(LocalDate.of(2027, 4, 15));

		var march = TyreSeason.on(LocalDate.of(2027, 3, 31));
		assertThat(march.heading()).isEqualTo("Vinterdäck gäller");
		assertThat(march.deadline()).isEqualTo(LocalDate.of(2027, 4, 15));
	}

	@Test
	@DisplayName("the first half of April is the spring window, against 15 April")
	void spring() {
		var notice = TyreSeason.on(LocalDate.of(2027, 4, 1));
		assertThat(notice.heading()).isEqualTo("Dags för sommardäck");
		assertThat(notice.deadline()).isEqualTo(LocalDate.of(2027, 4, 15));

		assertThat(TyreSeason.on(LocalDate.of(2027, 4, 15)).heading()).isEqualTo("Dags för sommardäck");
	}

	@Test
	@DisplayName("the rest of the year points at the coming autumn")
	void summer() {
		var notice = TyreSeason.on(LocalDate.of(2027, 4, 16));
		assertThat(notice.heading()).isEqualTo("Nästa däckskifte");
		assertThat(notice.deadline()).isEqualTo(LocalDate.of(2027, 12, 1));

		assertThat(TyreSeason.on(LocalDate.of(2027, 9, 30)).heading()).isEqualTo("Nästa däckskifte");
	}

}
