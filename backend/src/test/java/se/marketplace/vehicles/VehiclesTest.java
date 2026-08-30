package se.marketplace.vehicles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import se.marketplace.vehicles.VehicleCacheRepository.Cached;
import se.marketplace.vehicles.VehicleRegistryPort.RegistryUnavailable;

/**
 * The cache in front of the registry, which is what turns a quota per
 * booking into a quota per car.
 */
class VehiclesTest {

	private static final RegistrationNumber ABC123 = RegistrationNumber.parse("ABC123").orElseThrow();
	private static final Vehicle V70 = new Vehicle("VOLVO", "V70", 2016, "215/55R16", "215/55R16");

	private VehicleCacheRepository cache;
	private VehicleRegistryPort registry;
	private Vehicles vehicles;

	@BeforeEach
	void setUp() {
		cache = mock(VehicleCacheRepository.class);
		registry = mock(VehicleRegistryPort.class);
		vehicles = new Vehicles(cache, registry);
		ReflectionTestUtils.setField(vehicles, "knownDays", 365);
		ReflectionTestUtils.setField(vehicles, "unknownDays", 30);
		ReflectionTestUtils.setField(vehicles, "source", "tic");
	}

	@Test
	@DisplayName("a fresh cached car is served without asking the registry")
	void cacheHit() {
		when(cache.find(ABC123)).thenReturn(Optional.of(new Cached(V70, "tic", Instant.now())));

		assertThat(vehicles.lookup(ABC123)).contains(V70);
		verify(registry, never()).lookup(any());
	}

	@Test
	@DisplayName("a miss asks once and is written back")
	void cacheMiss() {
		when(cache.find(ABC123)).thenReturn(Optional.empty());
		when(registry.lookup(ABC123)).thenReturn(Optional.of(V70));

		assertThat(vehicles.lookup(ABC123)).contains(V70);
		verify(cache).put(ABC123, Optional.of(V70), "tic");
	}

	@Test
	@DisplayName("'not known' is remembered too, so a typo is not asked about on every keystroke")
	void negativeIsCached() {
		when(cache.find(ABC123)).thenReturn(Optional.empty());
		when(registry.lookup(ABC123)).thenReturn(Optional.empty());

		assertThat(vehicles.lookup(ABC123)).isEmpty();
		verify(cache).put(ABC123, Optional.empty(), "tic");

		// And served from the cache next time.
		when(cache.find(ABC123)).thenReturn(Optional.of(new Cached(null, "tic", Instant.now())));
		assertThat(vehicles.lookup(ABC123)).isEmpty();
		verify(registry).lookup(ABC123); // still exactly once
	}

	@Test
	@DisplayName("a stale row is re-asked; 'not known' goes stale sooner than a car")
	void staleIsReasked() {
		Instant fortyDaysAgo = Instant.now().minus(Duration.ofDays(40));
		when(cache.find(ABC123)).thenReturn(Optional.of(new Cached(null, "tic", fortyDaysAgo)));
		when(registry.lookup(ABC123)).thenReturn(Optional.of(V70));

		assertThat(vehicles.lookup(ABC123)).contains(V70);
		verify(cache).put(eq(ABC123), eq(Optional.of(V70)), anyString());

		// The same age on a found car is still fresh.
		when(cache.find(ABC123)).thenReturn(Optional.of(new Cached(V70, "tic", fortyDaysAgo)));
		assertThat(vehicles.lookup(ABC123)).contains(V70);
		verify(registry).lookup(ABC123); // not asked again
	}

	@Test
	@DisplayName("a registry that cannot be asked serves whatever is cached, however old")
	void staleBeatsAnOutage() {
		Instant twoYearsAgo = Instant.now().minus(Duration.ofDays(730));
		when(cache.find(ABC123)).thenReturn(Optional.of(new Cached(V70, "tic", twoYearsAgo)));
		when(registry.lookup(ABC123)).thenThrow(new RegistryUnavailable("down", null));

		assertThat(vehicles.lookup(ABC123)).contains(V70);
		verify(cache, never()).put(any(), any(), anyString());
	}

	@Test
	@DisplayName("a registry that cannot be asked with nothing cached is an outage the caller sees")
	void emptyCacheAndOutage() {
		when(cache.find(ABC123)).thenReturn(Optional.empty());
		when(registry.lookup(ABC123)).thenThrow(new RegistryUnavailable("down", null));

		assertThatThrownBy(() -> vehicles.lookup(ABC123)).isInstanceOf(RegistryUnavailable.class);
	}

}
