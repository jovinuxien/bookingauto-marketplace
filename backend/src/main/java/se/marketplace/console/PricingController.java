package se.marketplace.console;

import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import se.marketplace.pricing.Addon;
import se.marketplace.pricing.Addons;
import se.marketplace.pricing.PriceRule;
import se.marketplace.pricing.PriceRules;
import se.marketplace.pricing.Quote;
import se.marketplace.vehicles.RegistrationNumber;
import se.marketplace.vehicles.Vehicle;
import se.marketplace.vehicles.VehicleRegistryPort.RegistryUnavailable;
import se.marketplace.vehicles.Vehicles;

/**
 * The provider's prices, per car (ADR 0016 phase 3).
 *
 * <p>Every write is scoped to the signed-in provider by the pricing module
 * itself — a service that is not theirs is answered 404, indistinguishable
 * from one that does not exist. The quote box runs the real matcher against
 * a real plate, so a workshop can see what a customer will see before the
 * customer does.
 */
@RestController
@RequestMapping("/api/console/pricing")
class PricingController {

	private final ConsoleRepository repository;
	private final PriceRules rules;
	private final Vehicles vehicles;
	private final Addons addons;

	PricingController(ConsoleRepository repository, PriceRules rules, Vehicles vehicles, Addons addons) {
		this.repository = repository;
		this.rules = rules;
		this.vehicles = vehicles;
		this.addons = addons;
	}

	@GetMapping
	List<ServicePricing> all(@AuthenticationPrincipal ConsolePrincipal principal) {
		return repository.services(principal.providerId()).stream()
			.map(service -> new ServicePricing(service,
				rules.rulesFor(principal.providerId(), service.id()).orElse(List.of()),
				addons.forService(service.id())))
			.toList();
	}

	@PostMapping("/{serviceId}/rules")
	ResponseEntity<?> add(@AuthenticationPrincipal ConsolePrincipal principal,
		@PathVariable long serviceId, @RequestBody PriceRules.NewRule rule) {
		try {
			return rules.add(principal.providerId(), serviceId, rule)
				.<ResponseEntity<?>>map(created -> ResponseEntity.status(HttpStatus.CREATED).body(created))
				.orElseGet(() -> ResponseEntity.notFound().build());
		}
		catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(e.getMessage());
		}
	}

	@PostMapping("/{serviceId}/addons")
	ResponseEntity<?> addAddon(@AuthenticationPrincipal ConsolePrincipal principal,
		@PathVariable long serviceId, @RequestBody NewAddon addon) {
		try {
			return addons.add(principal.providerId(), serviceId, addon.name(), addon.priceMinor())
				.<ResponseEntity<?>>map(created -> ResponseEntity.status(HttpStatus.CREATED).body(created))
				.orElseGet(() -> ResponseEntity.notFound().build());
		}
		catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(e.getMessage());
		}
	}

	@DeleteMapping("/addons/{addonId}")
	ResponseEntity<Void> retireAddon(@AuthenticationPrincipal ConsolePrincipal principal,
		@PathVariable long addonId) {
		return addons.retire(principal.providerId(), addonId)
			? ResponseEntity.noContent().build()
			: ResponseEntity.notFound().build();
	}

	@DeleteMapping("/rules/{ruleId}")
	ResponseEntity<Void> delete(@AuthenticationPrincipal ConsolePrincipal principal,
		@PathVariable long ruleId) {
		return rules.delete(principal.providerId(), ruleId)
			? ResponseEntity.noContent().build()
			: ResponseEntity.notFound().build();
	}

	/** "Vad kostar det för ABC 123?" — the same matcher the customer meets. */
	@GetMapping("/{serviceId}/quote")
	ResponseEntity<?> quote(@AuthenticationPrincipal ConsolePrincipal principal,
		@PathVariable long serviceId, @RequestParam String regnr) {

		Optional<RegistrationNumber> plate = RegistrationNumber.parse(regnr);
		if (plate.isEmpty()) {
			return ResponseEntity.badRequest().body("not a registration number");
		}

		ConsoleRepository.ServiceRow service = repository.services(principal.providerId()).stream()
			.filter(s -> s.id() == serviceId).findFirst().orElse(null);
		if (service == null) {
			return ResponseEntity.notFound().build();
		}

		Optional<Vehicle> vehicle;
		boolean registryDown = false;
		try {
			vehicle = vehicles.lookup(plate.get());
		}
		catch (RegistryUnavailable e) {
			vehicle = Optional.empty();
			registryDown = true;
		}

		Quote quote = rules.quote(serviceId, service.priceMinor(), vehicle);
		return ResponseEntity.ok(new QuoteView(plate.get().display(),
			vehicle.map(Vehicle::describe).orElse(null),
			vehicle.map(Vehicle::tyres).orElse(null),
			registryDown, quote));
	}

	record ServicePricing(ConsoleRepository.ServiceRow service, List<PriceRule> rules, List<Addon> addons) {}

	record NewAddon(String name, int priceMinor) {}

	record QuoteView(String plate, String vehicle, String tyres, boolean registryUnavailable, Quote quote) {}

}
