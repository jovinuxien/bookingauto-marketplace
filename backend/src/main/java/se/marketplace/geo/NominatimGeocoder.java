package se.marketplace.geo;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;

/**
 * Nominatim, the OpenStreetMap geocoder.
 *
 * <p>Chosen because it can be self-hosted. The alternative — a commercial API —
 * means every salon address this platform has ever seen is sent to a third party
 * under their terms, and the licence usually forbids storing the coordinates you
 * paid for. OSM data is ODbL and a local instance sends nothing anywhere.
 *
 * <p><strong>The public instance is not a deployment.</strong> Its usage policy
 * caps you at one request per second absolutely, forbids bulk geocoding, and
 * will block an application that ignores either. It is fine for development and
 * for a handful of salons a day; {@code base-url} points elsewhere the moment
 * that stops being true.
 */
@Component
@ConditionalOnProperty(name = "marketplace.geocoding.provider", havingValue = "nominatim")
class NominatimGeocoder implements GeocoderPort {

	private static final Logger log = LoggerFactory.getLogger(NominatimGeocoder.class);

	/**
	 * What counts as precise enough to store, keyed on Nominatim's own
	 * {@code category/type}.
	 *
	 * <p>An allow-list rather than a deny-list, and that direction is the whole
	 * safety property. Nominatim answers a failed address lookup with the
	 * containing city, suburb or county rather than with nothing, and every one
	 * of those is a plausible-looking point kilometres from the salon. A
	 * deny-list would need to anticipate every such type; this list only needs
	 * to name the two that are actually good enough.
	 */
	private static Optional<Precision> precisionOf(String category, String type) {
		if ("place".equals(category) && ("house".equals(type) || "building".equals(type))) {
			return Optional.of(Precision.ROOFTOP);
		}
		if ("building".equals(category)) {
			return Optional.of(Precision.ROOFTOP);
		}
		if ("highway".equals(category)) {
			// A street centreline. The house number was not in the data, but the
			// street was, and the error that introduces is tens of metres against
			// a filter measured in kilometres.
			return Optional.of(Precision.STREET);
		}
		return Optional.empty();
	}

	private final RestClient http;
	private final String userAgent;

	NominatimGeocoder(
		@Value("${marketplace.geocoding.nominatim.base-url}") String baseUrl,
		@Value("${marketplace.geocoding.nominatim.user-agent}") String userAgent,
		@Value("${marketplace.geocoding.nominatim.timeout-seconds:10}") int timeoutSeconds) {

		this.userAgent = userAgent;
		this.http = RestClient.builder()
			.baseUrl(baseUrl)
			.requestFactory(new JdkClientHttpRequestFactory(
				HttpClient.newBuilder()
					.version(HttpClient.Version.HTTP_1_1)
					.connectTimeout(Duration.ofSeconds(timeoutSeconds))
					.build()))
			.build();
	}

	@Override
	public Optional<Placement> locate(Address address) {
		String street = SwedishAddress.street(address.line());

		if (street == null) {
			return Optional.empty();
		}

		List<Result> results;

		try {
			results = http.get()
				.uri(uri -> structured(uri, street, address))
				// Required by the usage policy, and required to identify the
				// application rather than the library. A default Java user agent
				// is what gets an IP blocked.
				.header("User-Agent", userAgent)
				.retrieve()
				.body(RESULT_LIST);
		}
		catch (RestClientException e) {
			// Could not ask, as distinct from asked and got nothing. The sweep
			// treats these differently and it matters: this one is worth
			// retrying shortly, an empty answer is not.
			throw new GeocoderUnavailable("nominatim unreachable: " + e.getMessage(), e);
		}

		if (results == null || results.isEmpty()) {
			return Optional.empty();
		}

		return results.stream()
			.flatMap(result -> precisionOf(result.category(), result.type())
				.map(precision -> new Placement(
					Double.parseDouble(result.lat()),
					Double.parseDouble(result.lon()),
					precision,
					result.display_name()))
				.stream())
			.findFirst();
	}

	/**
	 * The structured query, not the free-text one.
	 *
	 * <p>Free text is more forgiving and much worse here: given a street it
	 * cannot find it falls back to matching the city, and the result is
	 * indistinguishable in shape from a real hit. The structured form keeps the
	 * components apart, so a failure to match the street stays a failure.
	 */
	private static java.net.URI structured(UriBuilder uri, String street, Address address) {
		uri.path("/search")
			.queryParam("street", street)
			.queryParam("city", address.city())
			.queryParam("country", address.country())
			.queryParam("format", "jsonv2")
			.queryParam("addressdetails", 1)
			// Three, because the first result is sometimes the street when the
			// building is also present. Not more: past a handful they are
			// different places, not better matches for this one.
			.queryParam("limit", 3);

		if (address.postalCode() != null && !address.postalCode().isBlank()) {
			uri.queryParam("postalcode", address.postalCode());
		}

		return uri.build();
	}

	private static final org.springframework.core.ParameterizedTypeReference<List<Result>> RESULT_LIST =
		new org.springframework.core.ParameterizedTypeReference<>() {};

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Result(String lat, String lon, String category, String type, String display_name) {}

}
