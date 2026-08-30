package se.marketplace.vehicles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import se.marketplace.vehicles.VehicleLookupRepository.Pending;
import se.marketplace.vehicles.VehicleRegistryPort.RegistryUnavailable;

/**
 * The sweep, against a stub registry.
 *
 * <p>What is being checked is the two decisions the port's contract leaves to
 * this side: a plate the registry does not know is counted and left alone,
 * and a registry that cannot be asked stops the pass without counting
 * anything against anyone.
 */
class BookingVehiclesTest {

	private static final Vehicle V70 = new Vehicle("Volvo", "V70", 2016, "205/55R16", "205/55R16");

	private VehicleLookupRepository repository;
	private StubRegistry registry;
	private BookingVehicles vehicles;

	@BeforeEach
	void setUp() {
		repository = mock(VehicleLookupRepository.class);
		registry = new StubRegistry();
		// The cache is empty and stays empty (a mock), so every lookup reaches
		// the stub registry -- the cache has its own test.
		Vehicles through = new Vehicles(mock(VehicleCacheRepository.class), registry);
		ReflectionTestUtils.setField(through, "source", "stub");
		vehicles = new BookingVehicles(repository, through);

		ReflectionTestUtils.setField(vehicles, "maxAttempts", 3);
		ReflectionTestUtils.setField(vehicles, "batchSize", 20);
	}

	@Test
	@DisplayName("what the registry knows is written onto the booking")
	void recordsWhatItFinds() {
		when(repository.needingLookup(3, 20)).thenReturn(List.of(new Pending(7L, "ABC123")));
		when(repository.record(anyLong(), any())).thenReturn(1);
		registry.answer(Optional.of(V70));

		vehicles.lookUpWaiting();

		verify(repository).record(7L, V70);
		verify(repository, never()).recordFailure(anyLong(), anyString());
		assertThat(registry.asked).containsExactly("ABC123");
	}

	@Test
	@DisplayName("a plate the registry does not know is counted and left as typed")
	void unknownPlateIsCounted() {
		when(repository.needingLookup(3, 20)).thenReturn(List.of(new Pending(7L, "ABC123")));
		registry.answer(Optional.empty());

		vehicles.lookUpWaiting();

		verify(repository).recordFailure(eq(7L), anyString());
		verify(repository, never()).record(anyLong(), any());
	}

	@Test
	@DisplayName("an unreachable registry stops the pass and counts nothing")
	void unavailableRegistryStopsThePass() {
		when(repository.needingLookup(3, 20)).thenReturn(List.of(
			new Pending(7L, "ABC123"), new Pending(8L, "DEF456")));
		registry.fail();

		vehicles.lookUpWaiting();

		assertThat(registry.asked).containsExactly("ABC123");
		verify(repository, never()).recordFailure(anyLong(), anyString());
		verify(repository, never()).record(anyLong(), any());
	}

	@Test
	@DisplayName("text that is not a plate is never sent to the registry")
	void garbageIsNotAsked() {
		when(repository.needingLookup(3, 20)).thenReturn(List.of(new Pending(7L, "den blå volvon")));

		vehicles.lookUpWaiting();

		assertThat(registry.asked).isEmpty();
		verify(repository).recordFailure(eq(7L), anyString());
	}

	private static final class StubRegistry implements VehicleRegistryPort {

		private final Deque<Optional<Vehicle>> answers = new ArrayDeque<>();
		private final List<String> asked = new java.util.ArrayList<>();
		private boolean failing;

		void answer(Optional<Vehicle> vehicle) {
			answers.add(vehicle);
		}

		void fail() {
			failing = true;
		}

		@Override
		public Optional<Vehicle> lookup(RegistrationNumber plate) {
			asked.add(plate.value());
			if (failing) {
				throw new RegistryUnavailable("connection refused", null);
			}
			return answers.isEmpty() ? Optional.empty() : answers.poll();
		}

	}

}
