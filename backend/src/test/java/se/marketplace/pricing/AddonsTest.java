package se.marketplace.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AddonsTest {

	private final AddonRepository repository = mock(AddonRepository.class);
	private final Addons addons = new Addons(repository);

	@Test
	@DisplayName("ids from the client become names and prices from us")
	void priced() {
		when(repository.activeByIds(eq(1L), org.mockito.ArgumentMatchers.anyList()))
			.thenReturn(List.of(new Addon(7, 1, "Spolarvätska", 4900)));

		assertThat(addons.priced(1L, List.of(7L))).extracting(Addon::priceMinor).containsExactly(4900);
		assertThat(addons.priced(1L, null)).isEmpty();
		assertThat(addons.priced(1L, List.of())).isEmpty();
		assertThat(Addons.total(List.of(new Addon(1, 1, "a", 100), new Addon(2, 1, "b", 250)))).isEqualTo(350);
	}

	@Test
	@DisplayName("an id that is not this service's, or is retired, refuses the whole list")
	void unknown() {
		when(repository.activeByIds(eq(1L), org.mockito.ArgumentMatchers.anyList()))
			.thenReturn(List.of(new Addon(7, 1, "Spolarvätska", 4900)));

		assertThatThrownBy(() -> addons.priced(1L, List.of(7L, 99L)))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("the console gets Swedish reasons and a 404-shaped empty for foreign services")
	void console() {
		when(repository.serviceOwnedBy(5L, 1L)).thenReturn(true);
		when(repository.serviceOwnedBy(6L, 1L)).thenReturn(false);
		when(repository.insert(anyLong(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt()))
			.thenReturn(new Addon(1, 5, "Spolarvätska", 4900));

		assertThat(addons.add(1L, 6L, "x", 100)).isEmpty();
		assertThatThrownBy(() -> addons.add(1L, 5L, "  ", 100)).hasMessageContaining("namn");
		assertThatThrownBy(() -> addons.add(1L, 5L, "x", -1)).hasMessageContaining("negativt");
		assertThat(addons.add(1L, 5L, " Spolarvätska ", 4900)).isPresent();
		verify(repository).insert(5L, "Spolarvätska", 4900);
		verify(repository, never()).insert(eq(6L), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt());
	}

}
