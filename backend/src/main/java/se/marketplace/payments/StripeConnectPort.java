package se.marketplace.payments;

/**
 * Onboarding a provider onto Stripe.
 *
 * <p>Separate from {@link PaymentPort} because it answers a different question.
 * That one asks "can we take this money"; this one asks "may we pay this
 * business at all" — which is KYC, is slow, involves a human uploading
 * documents, and can be revoked long after it was granted.
 *
 * <p>The last part is why {@link #status} exists rather than a boolean written
 * once at signup. Stripe can restrict an account at any time — a document
 * expires, a check fails on review — and the first sign is usually a failed
 * transfer. Payability is something to re-read, not to remember.
 */
public interface StripeConnectPort {

	/**
	 * Creates the connected account. No money can be sent anywhere until this
	 * exists and KYC has completed.
	 */
	ConnectedAccount createAccount(NewAccount request);

	/**
	 * A single-use URL where the salon completes KYC.
	 *
	 * <p>Short-lived by design on Stripe's side, so it is generated when the
	 * salon asks rather than stored. Anything that stores one will serve an
	 * expired link to someone who came back tomorrow.
	 */
	String onboardingLink(String accountId, String returnUrl, String refreshUrl);

	/** What Stripe currently thinks of the account. */
	AccountStatus status(String accountId);

	record NewAccount(
		long providerId,
		String businessName,
		String email,
		String country
	) {}

	record ConnectedAccount(String accountId) {}

	/**
	 * @param chargesEnabled  whether Stripe will accept charges destined here
	 * @param payoutsEnabled  whether Stripe will actually pay the money out
	 * @param detailsSubmitted whether the salon finished the form — true well
	 *        before the other two, which is why it is not a proxy for either
	 */
	record AccountStatus(
		boolean chargesEnabled,
		boolean payoutsEnabled,
		boolean detailsSubmitted,
		String disabledReason
	) {

		/**
		 * Both, not either. Charges without payouts means taking a customer's
		 * money with no way to forward it, which is worse than refusing the sale.
		 */
		public boolean sellable() {
			return chargesEnabled && payoutsEnabled;
		}
	}

	class ConnectUnavailable extends RuntimeException {
		public ConnectUnavailable(String message) {
			super(message);
		}

		public ConnectUnavailable(String message, Throwable cause) {
			super(message, cause);
		}
	}

}
