package se.marketplace.vehicles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import se.marketplace.vehicles.VehicleRegistryPort.RegistryUnavailable;

/**
 * The adapter against a recorded TIC, which is the only TIC a test may talk
 * to: the real one costs a monthly quota per call.
 *
 * <p>What is being checked is the mapping of HTTP onto the port's contract —
 * 404 is empty, everything else that is not a car is unavailable — and that
 * the key travels in a header and never in the URL.
 */
class TicVehicleRegistryTest {

	private static final RegistrationNumber ABC123 = RegistrationNumber.parse("abc 123").orElseThrow();

	private MockRestServiceServer tic;
	private TicVehicleRegistry registry;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder().baseUrl("https://api.tic.io");
		tic = MockRestServiceServer.bindTo(builder).build();
		registry = new TicVehicleRegistry("test-key", builder);
	}

	@Test
	@DisplayName("a known plate becomes a vehicle with its tyre sizes")
	void mapsTheDocument() {
		tic.expect(requestTo("https://api.tic.io/datasets/vehicles/se/licence-plate/ABC123"))
			.andExpect(method(HttpMethod.GET))
			.andExpect(header("x-api-key", "test-key"))
			.andRespond(withSuccess("""
				{"licencePlate":"ABC123","manufacturer":"VOLVO","tradeName":"V70 D4",
				 "modelYear":2016,"vehicleYear":2016,
				 "tireDimensionFront":"215/55R16","tireDimensionRear":"215/55R16",
				 "color":"SVART","mileageKm":142000,"somethingElse":true}
				""", MediaType.APPLICATION_JSON));

		Optional<Vehicle> found = registry.lookup(ABC123);

		assertThat(found).contains(new Vehicle("VOLVO", "V70 D4", 2016, "215/55R16", "215/55R16"));
		assertThat(found.get().describe()).isEqualTo("VOLVO V70 D4 (2016)");
		assertThat(found.get().tyres()).isEqualTo("215/55R16");
		tic.verify();
	}

	@Test
	@DisplayName("the Swedish field names are read too")
	void readsTheTransportstyrelsenSpelling() {
		tic.expect(requestTo("https://api.tic.io/datasets/vehicles/se/licence-plate/ABC123"))
			.andRespond(withSuccess("""
				{"tillverkare":"TESLA","handelsbeteckning":"MODEL 3","arsmodell":2021,
				 "dackdimensionFram":"235/45R18","dackdimensionBak":"235/45R18"}
				""", MediaType.APPLICATION_JSON));

		assertThat(registry.lookup(ABC123))
			.contains(new Vehicle("TESLA", "MODEL 3", 2021, "235/45R18", "235/45R18"));
	}

	@Test
	@DisplayName("404 is a plate the register does not know, and is not retried")
	void notFoundIsEmpty() {
		tic.expect(requestTo("https://api.tic.io/datasets/vehicles/se/licence-plate/ABC123"))
			.andRespond(withStatus(HttpStatus.NOT_FOUND));

		assertThat(registry.lookup(ABC123)).isEmpty();
	}

	@Test
	@DisplayName("a wrong key, a quota, or a dead server are all 'could not ask'")
	void everythingElseIsUnavailable() {
		for (HttpStatus status : new HttpStatus[] {
			HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN, HttpStatus.TOO_MANY_REQUESTS,
			HttpStatus.INTERNAL_SERVER_ERROR }) {

			tic.reset();
			tic.expect(requestTo("https://api.tic.io/datasets/vehicles/se/licence-plate/ABC123"))
				.andRespond(withStatus(status));

			assertThatThrownBy(() -> registry.lookup(ABC123))
				.as("status " + status.value())
				.isInstanceOf(RegistryUnavailable.class);
		}
	}

	@Test
	@DisplayName("a document without a make is not a car")
	void emptyDocumentIsEmpty() {
		tic.expect(requestTo("https://api.tic.io/datasets/vehicles/se/licence-plate/ABC123"))
			.andRespond(withSuccess("{\"licencePlate\":\"ABC123\"}", MediaType.APPLICATION_JSON));

		assertThat(registry.lookup(ABC123)).isEmpty();
	}

	@Test
	@DisplayName("a foreign plate is never sent — the endpoint is Swedish and the quota is monthly")
	void foreignPlatesAreNotAsked() {
		// No expectation set: any request would fail the test.
		assertThat(registry.lookup(RegistrationNumber.parse("AB12345").orElseThrow())).isEmpty();
		tic.verify();
	}

	@Test
	@DisplayName("the key is refused as configuration, not discovered as a 401")
	void missingKeyFailsAtStartup() {
		assertThatThrownBy(() -> new TicVehicleRegistry(" ", RestClient.builder()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("api-key");
	}

	@Test
	@DisplayName("differing front and rear sizes are both shown")
	void staggeredTyres() {
		assertThat(new Vehicle("BMW", "M3", 2020, "275/35R19", "285/35R20").tyres())
			.isEqualTo("275/35R19 / 285/35R20");
		assertThat(new Vehicle("BMW", "M3", 2020, null, null).tyres()).isNull();
	}

}
