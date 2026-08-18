package se.marketplace.sync;

import java.net.http.HttpClient;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Creates Cal accounts, and reads back what the salon built.
 *
 * <p>Two very different mechanisms, together because they are two halves of one
 * job. Creating the user goes over Cal's public signup endpoint. Reading the
 * event types goes to Cal's database, because the API that would answer it is
 * licence-gated.
 */
@Component
class CalProvisioning implements CalProvisioningPort {

	private static final Logger log = LoggerFactory.getLogger(CalProvisioning.class);

	private final RestClient http;
	private final String baseUrl;
	private final NamedParameterJdbcTemplate calJdbc;

	CalProvisioning(
		@Value("${marketplace.cal.base-url:http://localhost:3000}") String baseUrl,
		@Qualifier("calReadModel") NamedParameterJdbcTemplate calJdbc) {

		this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
		this.calJdbc = calJdbc;
		this.http = RestClient.builder()
			.requestFactory(new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()))
			.build();
	}

	@Override
	public CalUser createUser(NewCalUser request) {
		try {
			// Serialised by Jackson rather than formatted into a string. The
			// values used to come from an operator typing them; they now come
			// from a public signup form, and a quotation mark in an address
			// would otherwise rewrite the request body. Validating the input
			// upstream is worth doing and is not what makes this safe.
			http.post()
				.uri(baseUrl + "/api/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.body(Map.of(
					"username", request.username(),
					"email", request.email(),
					"password", request.password()))
				.retrieve()
				.toBodilessEntity();
		}
		catch (HttpClientErrorException e) {
			if (e.getStatusCode() == HttpStatus.CONFLICT) {
				throw new CalUserExists("cal user " + request.username() + " already exists");
			}
			throw new CalProvisioningFailed("cal refused signup: " + e.getMessage(), e);
		}
		catch (RestClientException e) {
			throw new CalProvisioningFailed("could not reach cal to sign up", e);
		}

		// Signup returns no identifiers, so the row is read back. Doing it here
		// rather than making the caller do it keeps "a Cal user has an id" from
		// leaking out of this module.
		return findUser(request.username())
			.orElseThrow(() -> new CalProvisioningFailed(
				"cal reported signup succeeded but no user " + request.username() + " exists", null));
	}

	private java.util.Optional<CalUser> findUser(String username) {
		return calJdbc.query(
			"SELECT id, username FROM users WHERE username = :u",
			new MapSqlParameterSource("u", username),
			(ResultSet rs, int n) -> new CalUser(rs.getLong("id"), rs.getString("username")))
			.stream().findFirst();
	}

	/**
	 * Bookable event types belonging to a Cal user.
	 *
	 * <p>The joins are the filter. An event type is only sellable if it is not
	 * hidden, has a schedule to compute availability from, and appears in
	 * {@code _user_eventtype} — Cal resolves bookable hosts through that table,
	 * and one absent from it returns an empty slot map with HTTP 200, which is
	 * indistinguishable from a fully booked salon.
	 */
	@Override
	public List<CalEventType> eventTypesOf(long calUserId) {
		return calJdbc.query("""
			SELECT e.id, e.title, e.slug, e.length, e.price, e.currency,
			       e."requiresConfirmation"              AS requires_confirmation,
			       e."requiresConfirmationWillBlockSlot" AS confirmation_blocks
			  FROM "EventType" e
			  JOIN "_user_eventtype" j ON j."A" = e.id AND j."B" = :userId
			 WHERE e."userId" = :userId
			   AND e.hidden = false
			   AND (e."scheduleId" IS NOT NULL
			        OR EXISTS (SELECT 1 FROM "Schedule" s WHERE s."userId" = :userId))
			 ORDER BY e.position, e.id
			""",
			new MapSqlParameterSource("userId", calUserId),
			(ResultSet rs, int n) -> new CalEventType(
				rs.getLong("id"),
				rs.getString("title"),
				rs.getString("slug"),
				rs.getInt("length"),
				rs.getInt("price"),
				rs.getString("currency"),
				rs.getBoolean("requires_confirmation"),
				rs.getBoolean("confirmation_blocks")));
	}

}
