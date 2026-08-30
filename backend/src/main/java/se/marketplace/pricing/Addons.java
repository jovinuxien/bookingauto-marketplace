package se.marketplace.pricing;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;

/**
 * The extras a service can be booked with.
 *
 * <p>Two readers. The provider page lists what is on offer; the funnel asks
 * {@link #priced} to turn the ids a customer ticked into names and prices
 * it can freeze — and refuses anything that is not this service's, or is
 * no longer offered, because a price that came from the client is not a
 * price.
 */
@Service
public class Addons {

	private final AddonRepository repository;

	Addons(AddonRepository repository) {
		this.repository = repository;
	}

	public List<Addon> forService(long serviceId) {
		return repository.activeFor(serviceId);
	}

	/**
	 * @throws IllegalArgumentException when an id is not an active add-on of
	 *         this service — a stale page, or a client making things up
	 */
	public List<Addon> priced(long serviceId, List<Long> addonIds) {
		if (addonIds == null || addonIds.isEmpty()) {
			return List.of();
		}
		Set<Long> wanted = new HashSet<>(addonIds);
		List<Addon> found = repository.activeByIds(serviceId, List.copyOf(wanted));
		if (found.size() != wanted.size()) {
			throw new IllegalArgumentException("Ett tillval finns inte längre. Ladda om sidan.");
		}
		return found;
	}

	public static int total(List<Addon> addons) {
		return addons.stream().mapToInt(Addon::priceMinor).sum();
	}

	// ----------------------------------------------------------- the console --

	public Optional<Addon> add(long providerId, long serviceId, String name, int priceMinor) {
		if (!repository.serviceOwnedBy(serviceId, providerId)) {
			return Optional.empty();
		}
		String trimmed = name == null ? "" : name.trim();
		if (trimmed.isEmpty() || trimmed.length() > 80) {
			throw new IllegalArgumentException("Ge tillvalet ett namn, högst 80 tecken.");
		}
		if (priceMinor < 0) {
			throw new IllegalArgumentException("Priset kan inte vara negativt.");
		}
		return Optional.of(repository.insert(serviceId, trimmed, priceMinor));
	}

	public boolean retire(long providerId, long addonId) {
		return repository.retire(addonId, providerId) > 0;
	}

}
