package se.marketplace.booking;

import java.util.EnumSet;
import java.util.Set;

/**
 * Where a booking attempt has got to.
 *
 * <p>The saga crosses three authorities that cannot be updated atomically — Cal
 * owns time, Stripe owns money, we own the commercial record. There is no
 * transaction available across them, so what replaces one is this: an explicit
 * state, a legal set of moves out of it, and exactly one compensating action for
 * each way of failing.
 *
 * <p>The transitions are declared here rather than left implicit in the
 * orchestrator so that "can this happen?" is a question with an answer. An
 * illegal move is a bug, and it should be refused loudly at the point it is
 * attempted rather than written to the database and puzzled over later.
 */
public enum AttemptState {

	/** Quote frozen. Nothing outside this system has been touched. */
	STARTED,

	/** Cal is holding the slot, pending. The first step that changed the world. */
	RESERVED,

	/** The reservation was read back and agreed with what we asked for. */
	VERIFIED,

	/**
	 * A PaymentIntent exists and the customer is somewhere in their bank app.
	 *
	 * <p>The state that real money forces into the design. Swish is a push
	 * payment — Stripe returns "requires action", the customer approves in Swish,
	 * and we hear about it later over a webhook. Cards with 3-D Secure behave the
	 * same way. So stage 7 cannot be a call that returns success or failure.
	 *
	 * <p>Nothing has been charged here, and a slot is being held. Most attempts
	 * that end up stuck do so in this state, because people abandon checkouts —
	 * which is why the sweeper exists rather than being a nicety.
	 */
	AWAITING_PAYMENT,

	/** Money has moved. */
	CHARGED,

	/** Sold. */
	CONFIRMED,

	/** Gave up before reserving. Nothing to undo. */
	ABANDONED,

	/** Cal would not hold the slot. Recorded as an availability miss. */
	REFUSED,

	/** The read-back disagreed with what we asked for; the reservation was cancelled. */
	VERIFY_FAILED,

	/** Payment failed; the reservation was cancelled. */
	CHARGE_FAILED,

	/** Confirmation failed after charging; the customer was refunded. */
	CONFIRM_FAILED,

	/**
	 * A compensating action itself failed.
	 *
	 * <p>The only state that requires a person. It means the customer may be owed
	 * money, or holding a reservation nobody will honour, and the system has run
	 * out of ways to fix it on its own. Everything else here is a normal outcome;
	 * this one is an alert.
	 */
	NEEDS_ATTENTION;

	private static final Set<AttemptState> TERMINAL = EnumSet.of(
		CONFIRMED, ABANDONED, REFUSED, VERIFY_FAILED, CHARGE_FAILED, CONFIRM_FAILED, NEEDS_ATTENTION);

	/**
	 * Legal moves. Read down the left column for the happy path.
	 *
	 * <p>Note what is deliberately absent: there is no edge from {@link #RESERVED}
	 * or {@link #VERIFIED} straight to {@link #CONFIRMED}. Confirming without
	 * charging would give away an appointment, and making that unrepresentable is
	 * cheaper than remembering not to do it.
	 */
	public Set<AttemptState> allowedNext() {
		return switch (this) {
			case STARTED   -> EnumSet.of(RESERVED, REFUSED, ABANDONED, NEEDS_ATTENTION);
			case RESERVED  -> EnumSet.of(VERIFIED, VERIFY_FAILED, NEEDS_ATTENTION);
			// VERIFIED can reach CHARGED directly (a gateway that settles inline)
			// or wait first. Both are legitimate; which one happens depends on
			// the payment method, not on us.
			case VERIFIED  -> EnumSet.of(CHARGED, AWAITING_PAYMENT, CHARGE_FAILED, NEEDS_ATTENTION);
			case AWAITING_PAYMENT -> EnumSet.of(CHARGED, CHARGE_FAILED, NEEDS_ATTENTION);
			case CHARGED   -> EnumSet.of(CONFIRMED, CONFIRM_FAILED, NEEDS_ATTENTION);
			default        -> EnumSet.noneOf(AttemptState.class);
		};
	}

	public boolean canMoveTo(AttemptState next) {
		return allowedNext().contains(next);
	}

	public boolean isTerminal() {
		return TERMINAL.contains(this);
	}

	/**
	 * Whether reaching this state left something owed to the customer or held on
	 * their behalf.
	 *
	 * <p>Used to decide what a stuck attempt costs, and to keep the operational
	 * question — "is anyone out of pocket?" — separate from the merely
	 * unsuccessful.
	 */
	public boolean isCleanFailure() {
		return this == ABANDONED || this == REFUSED
			|| this == VERIFY_FAILED || this == CHARGE_FAILED;
	}

	/**
	 * What must be undone if the attempt stops here.
	 *
	 * <p>This is the compensation table from the design note, expressed once so
	 * that the orchestrator cannot quietly disagree with it.
	 */
	public Compensation compensationOnFailure() {
		return switch (this) {
			// Nothing has happened outside this system yet.
			case STARTED -> Compensation.NONE;
			// Cal is holding a slot for a sale that will not complete. Waiting
			// for payment counts: no money has moved, so releasing the slot is
			// the whole of the cleanup.
			case RESERVED, VERIFIED, AWAITING_PAYMENT -> Compensation.CANCEL_RESERVATION;
			// Money has moved and there is no manual capture to void; see ADR 0005.
			case CHARGED -> Compensation.REFUND;
			default -> Compensation.NONE;
		};
	}

	public enum Compensation {
		NONE,
		CANCEL_RESERVATION,
		REFUND
	}

}
