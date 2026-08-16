package se.marketplace.booking;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import se.marketplace.booking.AttemptState.Compensation;

/**
 * The state machine on its own.
 *
 * <p>Worth testing separately from the saga because these rules are what make
 * certain mistakes unrepresentable, and a rule that has quietly stopped holding
 * looks exactly like one that still does.
 */
class AttemptStateTest {

	@Test
	@DisplayName("an appointment cannot be confirmed without being paid for")
	void cannotConfirmWithoutCharging() {
		assertThat(AttemptState.RESERVED.canMoveTo(AttemptState.CONFIRMED)).isFalse();
		assertThat(AttemptState.VERIFIED.canMoveTo(AttemptState.CONFIRMED)).isFalse();
		assertThat(AttemptState.CHARGED.canMoveTo(AttemptState.CONFIRMED)).isTrue();
	}

	@Test
	@DisplayName("money cannot move before the slot is verified as held")
	void cannotChargeBeforeVerifying() {
		assertThat(AttemptState.STARTED.canMoveTo(AttemptState.CHARGED)).isFalse();
		assertThat(AttemptState.RESERVED.canMoveTo(AttemptState.CHARGED)).isFalse();
		assertThat(AttemptState.VERIFIED.canMoveTo(AttemptState.CHARGED)).isTrue();
	}

	@Test
	@DisplayName("terminal states are actually terminal")
	void terminalStatesGoNowhere() {
		for (AttemptState state : AttemptState.values()) {
			if (state.isTerminal()) {
				assertThat(state.allowedNext())
					.as("%s is terminal but has moves", state)
					.isEmpty();
			}
		}
	}

	@Test
	@DisplayName("every in-flight state can escalate to NEEDS_ATTENTION")
	void everyInFlightStateCanEscalate() {
		for (AttemptState state : AttemptState.values()) {
			if (!state.isTerminal()) {
				assertThat(state.canMoveTo(AttemptState.NEEDS_ATTENTION))
					.as("%s cannot escalate, so a failed compensation would have nowhere to go", state)
					.isTrue();
			}
		}
	}

	@Test
	@DisplayName("compensation matches how far the attempt got")
	void compensationTable() {
		assertThat(AttemptState.STARTED.compensationOnFailure()).isEqualTo(Compensation.NONE);
		assertThat(AttemptState.RESERVED.compensationOnFailure()).isEqualTo(Compensation.CANCEL_RESERVATION);
		assertThat(AttemptState.VERIFIED.compensationOnFailure()).isEqualTo(Compensation.CANCEL_RESERVATION);
		// Not a void: Swish has no manual capture, so the only undo is a refund.
		assertThat(AttemptState.CHARGED.compensationOnFailure()).isEqualTo(Compensation.REFUND);
	}

	@Test
	@DisplayName("NEEDS_ATTENTION is the only failure that is not clean")
	void needsAttentionIsNotACleanFailure() {
		assertThat(AttemptState.NEEDS_ATTENTION.isCleanFailure()).isFalse();
		assertThat(AttemptState.CONFIRM_FAILED.isCleanFailure()).isFalse();

		assertThat(AttemptState.REFUSED.isCleanFailure()).isTrue();
		assertThat(AttemptState.CHARGE_FAILED.isCleanFailure()).isTrue();
		assertThat(AttemptState.VERIFY_FAILED.isCleanFailure()).isTrue();
		assertThat(AttemptState.ABANDONED.isCleanFailure()).isTrue();
	}

}
