package se.marketplace.sync;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import se.marketplace.sync.CalPort.DayAvailability;
import se.marketplace.sync.CalPort.Slot;

/**
 * Keeps the availability index honest.
 *
 * <p>This is the mechanism, not a safety net. Webhooks are missed — on
 * redeploys, on network blips, whenever the receiver is briefly slower than the
 * sender's timeout — and a system that only reacts to them drifts quietly and
 * without complaint. So the index is rebuilt on a timer regardless of what
 * arrived, and webhooks only bring a rebuild forward.
 *
 * <p>Work is bounded per pass. Refreshing everything found stale would, on a bad
 * morning, mean refreshing the whole catalogue at once and taking Cal down with
 * it.
 */
@Component
class AvailabilityReconciler {

	private static final Logger log = LoggerFactory.getLogger(AvailabilityReconciler.class);

	private static final ZoneId ZONE = ZoneId.of("Europe/Stockholm");

	private final CalPort cal;
	private final AvailabilityIndexRepository repository;

	@Value("${marketplace.reconcile.freshness-seconds:600}")
	private int freshnessSeconds;

	@Value("${marketplace.reconcile.batch-size:50}")
	private int batchSize;

	@Value("${marketplace.reconcile.horizon-days:14}")
	private int horizonDays;

	AvailabilityReconciler(CalPort cal, AvailabilityIndexRepository repository) {
		this.cal = cal;
		this.repository = repository;
	}

	@Scheduled(fixedDelayString = "${marketplace.reconcile.interval-ms:60000}")
	void run() {
		List<AvailabilityIndexRepository.StaleService> stale =
			repository.findStale(freshnessSeconds, batchSize);

		if (stale.isEmpty()) {
			return;
		}

		int refreshed = 0;
		int failed = 0;

		for (var service : stale) {
			try {
				refresh(service, "reconcile");
				refreshed++;
			}
			catch (Exception e) {
				// One unreachable service must not stop the pass. It stays
				// stale and is picked up again next time, which is the correct
				// behaviour and also why the query orders by age.
				failed++;
				log.warn("could not refresh service {}: {}", service.serviceId(), e.toString());
			}
		}

		log.info("reconciled {} service(s), {} failed, {} were stale", refreshed, failed, stale.size());
	}

	void refresh(AvailabilityIndexRepository.StaleService service, String source) {
		Long providerId = repository.providerIdOfService(service.serviceId());

		if (providerId == null) {
			return;
		}

		Instant from = Instant.now();
		Instant to = from.plus(Duration.ofDays(horizonDays));

		List<Slot> slots = cal.slots(service.calEventTypeId(), from, to);

		for (DayAvailability day : summarise(slots, from, horizonDays)) {
			repository.upsert(providerId, service.serviceId(), day, source);
		}
	}

	/**
	 * Turns a list of slots into one row per day.
	 *
	 * <p>Day-level on purpose. Slot rows would be enormous and stale within
	 * seconds; a day answers what search actually asks — is this provider worth
	 * showing at all — and the exact times are fetched from Cal for the
	 * shortlist, where the cost is affordable.
	 *
	 * <p>Days with no slots are written as {@code has_capacity = false} rather
	 * than left absent. An absent row is indistinguishable from one that was
	 * never computed, and "we do not know" must not read as "fully booked".
	 */
	static List<DayAvailability> summarise(List<Slot> slots, Instant from, int horizonDays) {
		Map<LocalDate, List<Slot>> byDay = new TreeMap<>();

		LocalDate first = from.atZone(ZONE).toLocalDate();
		for (int i = 0; i < horizonDays; i++) {
			byDay.put(first.plusDays(i), new ArrayList<>());
		}

		for (Slot slot : slots) {
			LocalDate day = slot.start().atZone(ZONE).toLocalDate();
			byDay.computeIfAbsent(day, d -> new ArrayList<>()).add(slot);
		}

		List<DayAvailability> out = new ArrayList<>(byDay.size());

		byDay.forEach((day, daySlots) -> {
			if (daySlots.isEmpty()) {
				out.add(new DayAvailability(day, false, null, 0, false, false, false));
				return;
			}

			Instant firstFree = daySlots.stream()
				.map(Slot::start)
				.min(Instant::compareTo)
				.orElse(null);

			boolean morning = false;
			boolean afternoon = false;
			boolean evening = false;

			for (Slot slot : daySlots) {
				int hour = slot.start().atZone(ZONE).getHour();
				if (hour < 12) {
					morning = true;
				}
				else if (hour < 17) {
					afternoon = true;
				}
				else {
					evening = true;
				}
			}

			out.add(new DayAvailability(
				day, true, firstFree, daySlots.size(), morning, afternoon, evening));
		});

		return out;
	}

}
