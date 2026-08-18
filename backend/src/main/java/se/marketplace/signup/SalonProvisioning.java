package se.marketplace.signup;

/**
 * Everything signup needs from the rest of the system to turn a form into a
 * salon.
 *
 * <p>A port for the same reason {@code CalProvisioningPort} and
 * {@code StripeConnectPort} are ports: on the other side of it are two
 * companies' APIs, an HTTP round trip each, and the one part of registration
 * that cannot be exercised without them.
 *
 * <p>It is drawn around the whole job rather than each collaborator, because
 * what the module actually depends on is "a salon can be made to exist", and
 * that turns out to mean a provider, a Cal account, a Stripe account and a
 * login. Splitting it into four would name our current internal structure in a
 * place that does not care about it.
 */
interface SalonProvisioning {

	/**
	 * Whether this address can already sign in.
	 *
	 * <p>For choosing what to send, never for choosing what to answer.
	 */
	boolean loginExists(String email);

	String hashPassword(String rawPassword);

	/** Whether a name is still free, across live salons. */
	boolean slugAvailable(String slug);

	/**
	 * Creates the provider, its Cal account and its Stripe account.
	 *
	 * <p>Resumable: called again after a partial failure it continues rather
	 * than building a second half-finished salon.
	 *
	 * @throws NameTaken if the Cal username is gone. Distinguished from a
	 *         general failure because the person has to change something,
	 *         whereas everything else here is worth simply retrying
	 */
	Provisioned provision(NewSalon salon);

	void createLogin(long providerId, String email, String passwordHash, String displayName);

	record NewSalon(
		String slug,
		String salonName,
		String city,
		String addressLine,
		String postalCode,
		String email,
		String calPassword
	) {}

	/**
	 * @param calAccountCreated false when the Cal account already existed, which
	 *        is what a resumed registration looks like. The caller needs it: it
	 *        decides whether there is a new password to pass on, and showing
	 *        someone a password they have never seen is worse than showing none
	 */
	record Provisioned(
		long providerId,
		String calUsername,
		boolean calAccountCreated,
		String kycUrl
	) {}

	class NameTaken extends RuntimeException {
		NameTaken(String message) {
			super(message);
		}
	}

}
