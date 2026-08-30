package se.marketplace.vehicles;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * TIC — The Intelligence Company — as the registry.
 *
 * <p>Chosen over Biluppgifter and car.info for one field: the tyre dimension.
 * TIC mirrors Transportstyrelsen's register and exposes {@code dackdimension}
 * front and rear, which is the thing a däckverkstad actually wants to know
 * before the car arrives; the other two advertise make, model and year and
 * stop there. One endpoint is used —
 * {@code GET /datasets/vehicles/se/licence-plate/{plate}} — with the key in
 * an {@code x-api-key} header, never in the query string, where TIC's own
 * documentation says it ends up in every log between here and there.
 *
 * <p>The port's two-outcome contract is mapped as follows. 404 is
 * {@code empty}: the register does not know the plate, and asking again will
 * not change that. Everything else that is not a 200 — 401 and 403 (the key
 * is wrong or the plan does not cover this), 429 (over the plan's 60 a
 * minute / 3 000 a month), 5xx, and a connection that never completed — is
 * {@link RegistryUnavailable}, which stops the sweep's pass without counting
 * against any booking. A misconfigured key looks like an outage, and that
 * is the right way for it to look: loud in the log, harmless to bookings.
 *
 * <p>Only Swedish-looking plates are sent. The path says {@code /se/}, and a
 * Danish plate would be a guaranteed 404 spent from a monthly quota.
 */
@Component
@ConditionalOnProperty(name = "marketplace.vehicles.registry", havingValue = "tic")
class TicVehicleRegistry implements VehicleRegistryPort {

	private static final Logger log = LoggerFactory.getLogger(TicVehicleRegistry.class);

	private final RestClient http;

	TicVehicleRegistry(
		@Value("${marketplace.vehicles.tic.base-url}") String baseUrl,
		@Value("${marketplace.vehicles.tic.api-key}") String apiKey,
		@Value("${marketplace.vehicles.tic.timeout-seconds:10}") int timeoutSeconds) {
		this(apiKey, RestClient.builder()
			.baseUrl(baseUrl)
			.requestFactory(new JdkClientHttpRequestFactory(
				HttpClient.newBuilder()
					.version(HttpClient.Version.HTTP_1_1)
					.connectTimeout(Duration.ofSeconds(timeoutSeconds))
					.build())));
	}

	/** For tests, which bind a mock server to the builder. */
	TicVehicleRegistry(String apiKey, RestClient.Builder builder) {
		if (apiKey == null || apiKey.isBlank()) {
			throw new IllegalStateException(
				"marketplace.vehicles.registry=tic needs marketplace.vehicles.tic.api-key");
		}
		this.http = builder
			.defaultHeader("x-api-key", apiKey)
			.defaultHeader("Accept", "application/json")
			.build();
		log.info("vehicle registry: TIC");
	}

	@Override
	public Optional<Vehicle> lookup(RegistrationNumber plate) {
		if (!plate.looksSwedish()) {
			return Optional.empty();
		}

		Document document;

		try {
			document = http.get()
				.uri("/datasets/vehicles/se/licence-plate/{plate}", plate.value())
				.retrieve()
				.body(Document.class);
		}
		catch (HttpClientErrorException e) {
			if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
				return Optional.empty();
			}
			// 401, 403, 429 and the rest: we could not ask, as distinct from
			// asked and told no. 429 carries Retry-After; the sweep's next pass
			// is minutes away, which is longer than any TIC asks for.
			throw new RegistryUnavailable(
				"tic answered " + e.getStatusCode().value() + " for a lookup", e);
		}
		catch (RestClientException e) {
			throw new RegistryUnavailable("tic unreachable: " + e.getMessage(), e);
		}

		if (document == null || blank(document.make())) {
			// A 200 with nothing in it is not a car. Treated as unknown rather
			// than as a Vehicle with a null make, which the console would
			// render as " (2016)".
			return Optional.empty();
		}

		return Optional.of(new Vehicle(
			document.make().trim(),
			document.model(),
			document.year() != null ? document.year() : document.vehicleYear(),
			document.tyreFront(),
			document.tyreRear()));
	}

	private static boolean blank(String value) {
		return value == null || value.isBlank();
	}

	/**
	 * The fields this adapter reads, out of the two hundred the endpoint
	 * returns. Both spellings TIC uses are accepted — the English ones from
	 * the LENS schema and the Swedish ones from the Transportstyrelsen
	 * details document — because the licence-plate endpoint's documentation
	 * lists neither in full and a field name is cheap to accept and expensive
	 * to be wrong about in production.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	record Document(
		String manufacturer,
		String tillverkare,
		String tradeName,
		String vehicleName,
		String handelsbeteckning,
		String fordonsbenamning,
		Integer modelYear,
		Integer vehicleYear,
		Integer arsmodell,
		String tireDimensionFront,
		String tireDimensionRear,
		String dackdimensionFram,
		String dackdimensionBak
	) {

		String make() {
			return manufacturer != null ? manufacturer : tillverkare;
		}

		String model() {
			for (String candidate : new String[] { tradeName, handelsbeteckning, vehicleName, fordonsbenamning }) {
				if (candidate != null && !candidate.isBlank()) {
					return candidate.trim();
				}
			}
			return null;
		}

		Integer year() {
			return modelYear != null ? modelYear : arsmodell;
		}

		String tyreFront() {
			return tireDimensionFront != null ? tireDimensionFront : dackdimensionFram;
		}

		String tyreRear() {
			return tireDimensionRear != null ? tireDimensionRear : dackdimensionBak;
		}

	}

}
