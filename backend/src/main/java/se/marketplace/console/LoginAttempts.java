package se.marketplace.console;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import se.marketplace.ratelimit.RateLimiter;

/**
 * How often one source may try to sign in.
 *
 * <p>The third caller of the limiter, and the first defending something that is
 * ours rather than a third party's. {@code signup} protects accounts at Cal and
 * Stripe (ADR 0011); {@code search} protects an invoice (ADR 0012). This
 * protects the door to a salon's money, and one thing more: the password check
 * itself. Verifying a password is deliberately slow, which makes an unlimited
 * login endpoint a way to spend our CPU at the cost of one HTTP request.
 *
 * <p>So the attempt is counted <strong>before</strong> the password is checked.
 * A caller over the limit never gets us to compute a hash, which would be the
 * whole point of being over it.
 *
 * <p><strong>Every attempt counts, including the ones that succeed.</strong>
 * Before the password is checked there is nothing to tell them apart, and the
 * allowance is set for a busy salon rather than for one person: several staff,
 * several devices, and a shared address behind one NAT.
 *
 * <h2>Why there is no per-account limit</h2>
 *
 * <p>The obvious second bucket is the email address, and it is a trap. A limit
 * that refuses before the password is checked cannot distinguish the account's
 * owner from someone guessing at it, so a per-account bucket is a lockout
 * anybody can trigger against any address they know — ten wrong passwords an
 * hour would keep a real salon out of its own earnings indefinitely, and
 * clearing the bucket on a successful sign-in does not help, because the owner
 * is refused before they can succeed.
 *
 * <p>What such a bucket would add over this one is coverage of a guess spread
 * across many addresses, and the source limit already bounds how much password
 * checking any one of them can buy. A distributed guess against a single
 * account is therefore bounded by password strength rather than by anything
 * here. That is a real gap, written down rather than closed, because closing it
 * this way costs more than it buys.
 */
@Component
class LoginAttempts {

	private static final Duration HOUR = Duration.ofHours(1);

	private final RateLimiter limiter;

	/**
	 * Sign-in attempts per hour from one address.
	 *
	 * <p>Generous for a person and useless for a script, which is the same
	 * shape as every other limit here but a different number. A guess worth
	 * making needs thousands of attempts; a salon having a bad morning needs
	 * perhaps a dozen, and a front desk where four people share one connection
	 * needs a few dozen.
	 */
	@Value("${marketplace.console.login-per-ip-per-hour:30}")
	private int perIpPerHour;

	LoginAttempts(RateLimiter limiter) {
		this.limiter = limiter;
	}

	/**
	 * Counts this attempt and says whether it may proceed to the password.
	 *
	 * @param clientIp the socket address, never {@code X-Forwarded-For}. A limit
	 *                 keyed on a header the caller writes is a limit the caller
	 *                 sets for themselves — see ADR 0011.
	 */
	boolean permit(String clientIp) {
		return limiter.allow("login:ip:" + clientIp, perIpPerHour, HOUR);
	}

}
