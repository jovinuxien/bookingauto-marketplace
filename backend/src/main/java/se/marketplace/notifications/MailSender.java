package se.marketplace.notifications;

/**
 * Actually delivering a message.
 *
 * <p>Two failure modes, and they are not the same. A rejected address will be
 * rejected identically forever and retrying wastes attempts on a message that
 * will never arrive; an unreachable server is temporary and retrying is the
 * whole point. Conflating them either gives up on recoverable failures or
 * retries permanent ones for hours.
 */
interface MailSender {

	void send(String to, String subject, String bodyText, String bodyHtml);

	/** The address is wrong. Retrying cannot help. */
	class Rejected extends RuntimeException {
		Rejected(String message) {
			super(message);
		}
	}

	/** The server could not be reached, or refused temporarily. Retry. */
	class Unavailable extends RuntimeException {
		Unavailable(String message, Throwable cause) {
			super(message, cause);
		}
	}

}
