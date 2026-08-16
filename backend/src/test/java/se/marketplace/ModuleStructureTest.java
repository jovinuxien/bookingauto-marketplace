package se.marketplace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Checks the module boundaries the design depends on.
 *
 * <p>Not ceremony. The whole containment argument for this system is that
 * exactly one module knows each external system's wire shape — {@code sync}
 * knows Cal's, {@code payments} knows Stripe's — and that property is what makes
 * an internal API or a licence change a bounded problem instead of a rewrite. It
 * is also invisible: a second caller compiles perfectly.
 *
 * <p>This test earned its place immediately. Wiring Stripe's {@code
 * account.updated} into the booking module compiled and worked, and quietly made
 * {@code booking} depend on {@code onboarding} for a reason that had nothing to
 * do with booking. The fix was to publish an event instead.
 */
class ModuleStructureTest {

	@Test
	@DisplayName("modules only depend on what they declare")
	void moduleBoundariesHold() {
		ApplicationModules.of(BackendApplication.class).verify();
	}

}
