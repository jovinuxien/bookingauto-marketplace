package se.marketplace.pricing;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Service;

import se.marketplace.vehicles.Vehicle;

/**
 * The matcher, and the console's way in.
 *
 * <p>{@link #quote} is the whole product: given what the registry said about
 * the car, which rule applies and what it costs. Most specific wins; ties go
 * to the cheaper, which is the tie a customer would break; no match is the
 * list price. It is called twice per sale — on the page, and again in the
 * funnel when the attempt is frozen — and must give the same answer both
 * times, which is why it has no clock and no randomness.
 */
@Service
public class PriceRules {

	private final PriceRuleRepository repository;

	PriceRules(PriceRuleRepository repository) {
		this.repository = repository;
	}

	public Quote quote(long serviceId, int listPriceMinor, Optional<Vehicle> vehicle) {
		if (vehicle.isEmpty()) {
			return Quote.list(listPriceMinor);
		}
		return quote(repository.forService(serviceId), listPriceMinor, vehicle.get());
	}

	/** The matcher itself, on a given rule list; what the tests exercise. */
	static Quote quote(List<PriceRule> rules, int listPriceMinor, Vehicle vehicle) {
		return rules.stream()
			.filter(rule -> rule.matches(vehicle))
			.min(Comparator.comparingInt(PriceRule::specificity).reversed()
				.thenComparingInt(PriceRule::priceMinor))
			.map(rule -> new Quote(rule.priceMinor(), rule.label(), true))
			.orElse(Quote.list(listPriceMinor));
	}

	// ----------------------------------------------------------- the console --

	/** Empty when the service is not the provider's — indistinguishable from "no rules", on purpose. */
	public Optional<List<PriceRule>> rulesFor(long providerId, long serviceId) {
		if (!repository.serviceOwnedBy(serviceId, providerId)) {
			return Optional.empty();
		}
		return Optional.of(repository.forService(serviceId));
	}

	/**
	 * @throws IllegalArgumentException for a rule that could never be right:
	 *         no constraint at all, a non-positive price, a reversed range
	 */
	public Optional<PriceRule> add(long providerId, long serviceId, NewRule rule) {
		if (!repository.serviceOwnedBy(serviceId, providerId)) {
			return Optional.empty();
		}
		return Optional.of(repository.insert(serviceId, rule.validated()));
	}

	public boolean delete(long providerId, long ruleId) {
		return repository.delete(ruleId, providerId) > 0;
	}

	/** A rule as the console sends it. Strings blank-to-null, case-folded where matching is. */
	public record NewRule(
		String make,
		String modelPrefix,
		Integer yearFrom,
		Integer yearTo,
		Integer rimFrom,
		Integer rimTo,
		int priceMinor,
		String label
	) {

		NewRule validated() {
			String make = blankToNull(this.make);
			String model = blankToNull(this.modelPrefix);
			String label = blankToNull(this.label);

			if (make == null && model == null && yearFrom == null && yearTo == null
				&& rimFrom == null && rimTo == null) {
				throw new IllegalArgumentException("En regel behöver minst ett villkor — annars är det listpriset.");
			}
			if (priceMinor <= 0) {
				throw new IllegalArgumentException("Priset måste vara större än noll.");
			}
			if (yearFrom != null && yearTo != null && yearFrom > yearTo) {
				throw new IllegalArgumentException("Årsmodell från kan inte vara efter till.");
			}
			if (rimFrom != null && rimTo != null && rimFrom > rimTo) {
				throw new IllegalArgumentException("Tum från kan inte vara större än till.");
			}
			if (label == null) {
				label = describe(make, model, yearFrom, yearTo, rimFrom, rimTo);
			}
			return new NewRule(
				make == null ? null : make.toUpperCase(Locale.ROOT),
				model == null ? null : model.toUpperCase(Locale.ROOT),
				yearFrom, yearTo, rimFrom, rimTo, priceMinor, label);
		}

		private static String describe(String make, String model, Integer yf, Integer yt, Integer rf, Integer rt) {
			StringBuilder out = new StringBuilder();
			if (make != null) out.append(capitalise(make));
			if (model != null) out.append(out.isEmpty() ? "" : " ").append(model.toUpperCase(Locale.ROOT));
			if (yf != null || yt != null) {
				out.append(out.isEmpty() ? "" : " ").append(yf == null ? "–" + yt : yt == null ? yf + "–" : yf.equals(yt) ? yf : yf + "–" + yt);
			}
			if (rf != null || rt != null) {
				out.append(out.isEmpty() ? "" : ", ").append(rf == null ? "–" + rt : rt == null ? rf + "–" : rf.equals(rt) ? rf : rf + "–" + rt).append(" tum");
			}
			return out.toString();
		}

		private static String capitalise(String s) {
			return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1).toLowerCase(Locale.ROOT);
		}

		private static String blankToNull(String s) {
			return s == null || s.isBlank() ? null : s.trim();
		}

	}

}
