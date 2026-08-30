package se.marketplace.reviews;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReviewsTest {

	@Test
	@DisplayName("a stranger sees a first name and an initial, never more")
	void author() {
		assertThat(Reviews.author("Anna Andersson")).isEqualTo("Anna A.");
		assertThat(Reviews.author("Bo Erik von Berg")).isEqualTo("Bo B.");
		assertThat(Reviews.author("Anna")).isEqualTo("Anna");
		assertThat(Reviews.author("  ")).isEqualTo("Kund");
		assertThat(Reviews.author(null)).isEqualTo("Kund");
	}

	@Test
	@DisplayName("the rating is 1–5 and the comment is short, or nothing is stored")
	void validation() {
		ReviewRepository repository = mock(ReviewRepository.class);
		when(repository.insert(anyLong(), anyLong(), anyInt(), isNull())).thenReturn(true);
		Reviews reviews = new Reviews(repository);

		assertThatThrownBy(() -> reviews.submit(1, 1, 0, null)).hasMessageContaining("1 till 5");
		assertThatThrownBy(() -> reviews.submit(1, 1, 6, null)).hasMessageContaining("1 till 5");
		assertThatThrownBy(() -> reviews.submit(1, 1, 3, "x".repeat(1001))).hasMessageContaining("1 000");

		// Blank comments are stored as nothing, not as "".
		assertThat(reviews.submit(1, 1, 3, "   ")).isTrue();
		verify(repository).insert(eq(1L), eq(1L), eq(3), isNull());
	}

	@Test
	@DisplayName("no reviews is no average — a new provider is not a bad one")
	void none() {
		assertThat(RatingSummary.NONE.average()).isNull();
		assertThat(RatingSummary.NONE.count()).isZero();
	}

}
