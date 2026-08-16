package se.marketplace.booking;

import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import se.marketplace.sync.CalPort;

/**
 * Stage 2 of the funnel: the first true statement.
 *
 * <p>Search answers at day granularity — "Saturday afternoon" — because the
 * index it reads is denormalised and allowed to be stale. A customer books a
 * <em>time</em>. This is where one becomes the other, and it is the only place
 * in the consumer journey that asks Cal directly.
 *
 * <p>Deliberately not served from {@code availability_day}. Reusing the index
 * here would make the whole system self-consistent and occasionally wrong
 * together: the customer would be offered a specific time the index invented,
 * and discover at stage 6 that Cal disagrees. Being slower and correct at
 * exactly one point is the trade ADR 0002 was written to make.
 *
 * <p>Affordable because it runs for one service that someone is actually looking
 * at, never across the catalogue.
 */
@RestController
@RequestMapping("/api/services")
class SlotsController {

	private final CalPort cal;
	private final NamedParameterJdbcTemplate jdbc;

	@Value("${marketplace.cal.timezone:Europe/Stockholm}")
	private String timeZone;

	SlotsController(CalPort cal, NamedParameterJdbcTemplate jdbc) {
		this.cal = cal;
		this.jdbc = jdbc;
	}

	@GetMapping("/{serviceId}/slots")
	ResponseEntity<DaySlots> slots(
		@PathVariable long serviceId,
		@RequestParam(required = false) String day) {

		List<Long> eventTypeIds = jdbc.query("""
			SELECT s.cal_event_type_id
			  FROM service s JOIN provider p ON p.id = s.provider_id
			 WHERE s.id = :id AND s.active AND p.status = 'active'
			""",
			new MapSqlParameterSource("id", serviceId),
			(ResultSet rs, int n) -> rs.getLong("cal_event_type_id"));

		if (eventTypeIds.isEmpty()) {
			return ResponseEntity.notFound().build();
		}

		ZoneId zone = ZoneId.of(timeZone);
		LocalDate date = day == null ? LocalDate.now(zone) : LocalDate.parse(day);

		// A whole local day, in instants. Asking Cal for a UTC day would drop
		// the evening in summer and pick up the previous one in winter.
		Instant from = date.atStartOfDay(zone).toInstant();
		Instant to = from.plus(Duration.ofDays(1));

		List<String> times = cal.slots(eventTypeIds.get(0), from, to).stream()
			.map(slot -> slot.start().toString())
			.toList();

		return ResponseEntity.ok(new DaySlots(serviceId, date.toString(), times));
	}

	/**
	 * @param starts ISO instants. Sent as instants rather than local times so the
	 *        browser formats them in the viewer's zone and nothing has to agree
	 *        about whose midnight it is.
	 */
	record DaySlots(long serviceId, String day, List<String> starts) {}

}
