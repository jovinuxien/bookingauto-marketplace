package se.marketplace.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
class AvailabilityRefresh implements AvailabilityRefreshPort {

	private static final Logger log = LoggerFactory.getLogger(AvailabilityRefresh.class);

	private final AvailabilityIndexRepository repository;

	AvailabilityRefresh(AvailabilityIndexRepository repository) {
		this.repository = repository;
	}

	@Override
	public void markStale(long serviceId) {
		repository.markStale(serviceId);
		log.debug("service {} marked stale", serviceId);
	}

}
