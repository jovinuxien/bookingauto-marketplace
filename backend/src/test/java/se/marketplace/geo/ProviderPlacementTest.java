package se.marketplace.geo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

import se.marketplace.geo.GeocoderPort.Address;
import se.marketplace.geo.GeocoderPort.GeocoderUnavailable;
import se.marketplace.geo.GeocoderPort.Placement;
import se.marketplace.geo.GeocoderPort.Precision;
import se.marketplace.geo.PlacementRepository.Unplaced;

/**
 * What the sweep does with each kind of answer.
 *
 * <p>The three cases that matter are not "it worked": they are a refusal, an
 * outage, and a race with an operator. Each has a different correct response and
 * getting any of them wrong is invisible in normal running — a salon that is
 * merely still unplaced looks exactly like a salon nobody has got to yet.
 */
class ProviderPlacementTest {

	private PlacementRepository repository;
	private StubGeocoder geocoder;
	private ProviderPlacement placement;

	private static final Unplaced SALON =
		new Unplaced(8L, "Bondegatan 12", "116 33", "Stockholm", "SE");

	@BeforeEach
	void setUp() {
		repository = mock(PlacementRepository.class);
		geocoder = new StubGeocoder();
		placement = new ProviderPlacement(repository, geocoder);

		ReflectionTestUtils.setField(placement, "maxAttempts", 3);
		ReflectionTestUtils.setField(placement, "batchSize", 5);
		// No waiting in tests. The pause is a courtesy to Nominatim, not part of
		// the behaviour being checked here.
		ReflectionTestUtils.setField(placement, "pauseMs", 0L);
	}

	@Test
	@DisplayName("a located salon is written with its coordinates")
	void placesWhatItFinds() {
		when(repository.needingLocation(3, 5)).thenReturn(List.of(SALON));
		when(repository.place(anyLong(), anyDouble(), anyDouble(), anyString())).thenReturn(1);
		geocoder.answer(Optional.of(
			new Placement(59.3127058, 18.0780236, Precision.ROOFTOP, "12-14, Bondegatan")));

		placement.placeWaiting();

		verify(repository).place(8L, 59.3127058, 18.0780236, "geocoded");
		verify(repository, never()).recordFailure(anyLong(), anyString());
		assertThat(geocoder.asked).singleElement()
			.extracting(Address::country)
			.isEqualTo("Sweden");
	}

	@Test
	@DisplayName("a refusal is counted, so the address stops being asked about")
	void countsARefusal() {
		when(repository.needingLocation(3, 5)).thenReturn(List.of(SALON));
		geocoder.answer(Optional.empty());

		placement.placeWaiting();

		verify(repository, never()).place(anyLong(), anyDouble(), anyDouble(), anyString());
		verify(repository).recordFailure(eq(8L), anyString());
	}

	@Test
	@DisplayName("an outage counts against nothing and stops the pass")
	void doesNotBlameTheAddressForAnOutage() {
		// The distinction this whole test file exists for. Counting an outage
		// against the address would burn all three attempts during one bad
		// afternoon and strand every salon in the backlog permanently, with a
		// failure message blaming addresses that are perfectly fine.
		Unplaced second = new Unplaced(9L, "Odengatan 5", "113 22", "Stockholm", "SE");
		when(repository.needingLocation(3, 5)).thenReturn(List.of(SALON, second));
		geocoder.fail(new GeocoderUnavailable("connection refused"));

		placement.placeWaiting();

		verify(repository, never()).recordFailure(anyLong(), anyString());
		verify(repository, never()).place(anyLong(), anyDouble(), anyDouble(), anyString());
		// Stopped after the first: if it is down for one it is down for the next.
		assertThat(geocoder.asked).hasSize(1);
	}

	@Test
	@DisplayName("an address with nothing to match is never sent")
	void doesNotSpendARequestOnAnUnaskableAddress() {
		when(repository.needingLocation(3, 5))
			.thenReturn(List.of(new Unplaced(11L, "lgh 1101", null, "Stockholm", "SE")));

		placement.placeWaiting();

		assertThat(geocoder.asked).isEmpty();
		verify(repository).recordFailure(11L, "no usable street address");
	}

	@Test
	@DisplayName("an operator placing it mid-sweep wins")
	void operatorPlacementWins() {
		// place() returns 0 because the repository guard found a location already
		// there. The sweep must treat that as settled, not retry and not record a
		// failure against an address that is now correctly placed.
		when(repository.needingLocation(3, 5)).thenReturn(List.of(SALON));
		when(repository.place(anyLong(), anyDouble(), anyDouble(), anyString())).thenReturn(0);
		geocoder.answer(Optional.of(
			new Placement(59.31, 18.07, Precision.STREET, "Bondegatan")));

		placement.placeWaiting();

		verify(repository, never()).recordFailure(anyLong(), anyString());
	}

	@Test
	@DisplayName("an empty backlog asks nothing")
	void quietWhenNothingWaits() {
		when(repository.needingLocation(3, 5)).thenReturn(List.of());

		placement.placeWaiting();

		assertThat(geocoder.asked).isEmpty();
		verify(repository, times(1)).needingLocation(3, 5);
	}

	/** Hand written rather than mocked: the queue of answers is the fixture. */
	private static final class StubGeocoder implements GeocoderPort {

		private final Deque<Address> asked = new ArrayDeque<>();
		private Optional<Placement> answer = Optional.empty();
		private RuntimeException failure;

		void answer(Optional<Placement> answer) {
			this.answer = answer;
			this.failure = null;
		}

		void fail(RuntimeException failure) {
			this.failure = failure;
		}

		@Override
		public Optional<Placement> locate(Address address) {
			asked.add(address);
			if (failure != null) {
				throw failure;
			}
			return answer;
		}

	}

}
