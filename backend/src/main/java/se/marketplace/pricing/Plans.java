package se.marketplace.pricing;

import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The price list (ADR 0020): three plans, and the one thing they change.
 *
 * <p>Configuration, not a table — a price list changes without migrations —
 * and read through this one component so nothing else holds a copy. An
 * unknown or null plan is {@code bas}: a config mistake must degrade to the
 * default terms, not to an exception on the checkout path.
 */
@Service
public class Plans {

	@Value("${marketplace.plans.bas.commission-bps:1500}")
	private int basBps;

	@Value("${marketplace.plans.plus.commission-bps:1200}")
	private int plusBps;

	@Value("${marketplace.plans.plus.monthly-minor:24900}")
	private int plusMonthly;

	@Value("${marketplace.plans.pro.commission-bps:900}")
	private int proBps;

	@Value("${marketplace.plans.pro.monthly-minor:49900}")
	private int proMonthly;

	public Plan of(String name) {
		return switch (name == null ? "bas" : name.toLowerCase(Locale.ROOT)) {
			case "plus" -> new Plan("plus", "Plus", plusMonthly, plusBps);
			case "pro" -> new Plan("pro", "Pro", proMonthly, proBps);
			default -> new Plan("bas", "Bas", 0, basBps);
		};
	}

	public List<Plan> all() {
		return List.of(of("bas"), of("plus"), of("pro"));
	}

	public boolean knows(String name) {
		return name != null && List.of("bas", "plus", "pro").contains(name.toLowerCase(Locale.ROOT));
	}

	/** @param monthlyMinor what the plan costs per month; zero for bas */
	public record Plan(String name, String label, int monthlyMinor, int commissionBps) {}

}
