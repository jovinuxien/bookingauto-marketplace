package se.marketplace.payments;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.stripe.StripeClient;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.CardException;
import com.stripe.exception.IdempotencyException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;

/**
 * Stripe Connect.
 *
 * <h2>Destination charges, because we are the merchant</h2>
 *
 * <p>ADR 0003: the consumer pays the platform, and the platform pays the salon.
 * In Stripe's vocabulary that is a <em>destination charge</em> — the
 * PaymentIntent is created on our account with {@code transfer_data.destination}
 * pointing at the salon's connected account and {@code application_fee_amount}
 * as our commission. The alternative, a direct charge on the salon's account,
 * would make the salon the merchant of record and undo the decision that makes
 * this a marketplace rather than a directory.
 *
 * <h2>Nothing here is synchronous</h2>
 *
 * <p>Swish is a push payment. Creating the intent does not take money; it
 * returns {@code requires_action} and a client secret, the customer approves in
 * their bank app, and Stripe tells us over a webhook. Cards with 3-D Secure are
 * the same. So {@link #charge} usually returns {@link Status#REQUIRES_ACTION},
 * and the funnel waits.
 *
 * <p>Capture is automatic and cannot be otherwise: Swish has no manual capture,
 * so there is no authorise-then-capture to reach for and the only way to undo a
 * completed payment is a refund. That constraint is what forced the funnel's
 * ordering — see ADR 0005.
 *
 * <h2>Idempotency</h2>
 *
 * <p>Every mutating call carries an idempotency key derived from the booking
 * attempt. Stripe then collapses a retried request into the original rather than
 * charging twice, which matters because the most likely retry is a customer
 * double-clicking checkout.
 */
@Component
@ConditionalOnProperty(name = "marketplace.payments.gateway", havingValue = "stripe")
class StripePaymentGateway implements PaymentPort {

	private static final Logger log = LoggerFactory.getLogger(StripePaymentGateway.class);

	private final StripeClient stripe;
	private final List<String> paymentMethods;

	StripePaymentGateway(
		@Value("${marketplace.payments.stripe.api-key:}") String apiKey,
		@Value("${marketplace.payments.stripe.base-url:}") String baseUrl,
		@Value("${marketplace.payments.stripe.methods:swish,card}") String methods) {

		if (apiKey == null || apiKey.isBlank()) {
			throw new IllegalStateException(
				"marketplace.payments.stripe.api-key is required when the stripe gateway is selected");
		}

		StripeClient.StripeClientBuilder builder = StripeClient.builder().setApiKey(apiKey);

		// Pointing at stripe-mock lets the request shapes be exercised without
		// credentials. It answers with fixtures and validates nothing about the
		// business, so a green run here means "the calls are well formed", not
		// "payments work".
		if (baseUrl != null && !baseUrl.isBlank()) {
			builder.setApiBase(baseUrl);
			log.warn("stripe base url overridden to {} — not talking to Stripe", baseUrl);
		}

		this.stripe = builder.build();
		this.paymentMethods = List.of(methods.split("\\s*,\\s*"));
	}

	@Override
	public Charge charge(ChargeRequest request) {
		if (request.connectedAccountId() == null || request.connectedAccountId().isBlank()) {
			// Refused rather than attempted. A charge with no destination would
			// succeed and leave the whole amount sitting on the platform account
			// with nothing recording who it belongs to.
			throw new PaymentUnavailable(
				"provider " + request.providerId() + " has no connected stripe account");
		}

		PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
			.setAmount((long) request.amountMinor())
			.setCurrency(request.currency().toLowerCase())
			.addAllPaymentMethodType(paymentMethods)
			.setDescription(request.description())
			.setReceiptEmail(request.customerEmail())
			.setApplicationFeeAmount((long) request.commissionMinor())
			.setTransferData(PaymentIntentCreateParams.TransferData.builder()
				.setDestination(request.connectedAccountId())
				.build())
			// Ties the money back to the attempt from Stripe's side, so the
			// dashboard is usable during an incident without a join.
			.putMetadata("idempotency_key", request.idempotencyKey())
			.putMetadata("provider_id", String.valueOf(request.providerId()))
			.build();

		PaymentIntent intent;
		try {
			intent = stripe.paymentIntents().create(params,
				com.stripe.net.RequestOptions.builder()
					.setIdempotencyKey("charge:" + request.idempotencyKey())
					.build());
		}
		catch (CardException e) {
			// The card was declined. Nothing moved, the reservation can be
			// released cleanly, and this is a normal outcome.
			throw new PaymentRefused("declined: " + e.getDeclineCode());
		}
		catch (IdempotencyException e) {
			// The same key was reused with different parameters. That is a bug
			// on our side, and it must not be retried into a second charge.
			throw new PaymentUnavailable("idempotency key reused with different parameters", e);
		}
		catch (InvalidRequestException e) {
			throw new PaymentUnavailable("stripe rejected the request: " + e.getMessage(), e);
		}
		catch (ApiConnectionException e) {
			// The dangerous one: the request may have been received. Never treat
			// this as "no charge happened".
			throw new PaymentUnavailable("could not reach stripe; charge state unknown", e);
		}
		catch (StripeException e) {
			throw new PaymentUnavailable("stripe error: " + e.getMessage(), e);
		}

		return switch (intent.getStatus()) {
			case "succeeded" -> Charge.settled(
				intent.getId(), request.amountMinor(), request.currency());

			// Everything short of succeeded means the customer still has work to
			// do. Grouped deliberately: the distinctions between them are
			// Stripe's business, and to us they are all "wait for the webhook".
			case "requires_action", "requires_confirmation",
			     "requires_payment_method", "processing" ->
				new Charge(intent.getId(), request.amountMinor(), request.currency(),
					Status.REQUIRES_ACTION, intent.getClientSecret());

			case "canceled" -> throw new PaymentRefused("intent was cancelled");

			default -> throw new PaymentUnavailable(
				"unexpected payment intent status: " + intent.getStatus());
		};
	}

	@Override
	public Refund refund(String paymentRef, String reason) {
		try {
			RefundCreateParams params = RefundCreateParams.builder()
				.setPaymentIntent(paymentRef)
				// The application fee comes back too. Keeping commission on a
				// sale that did not happen is not a rounding detail; it is
				// revenue we are not entitled to.
				.setRefundApplicationFee(true)
				.setReverseTransfer(true)
				.build();

			com.stripe.model.Refund refund = stripe.refunds().create(params,
				com.stripe.net.RequestOptions.builder()
					.setIdempotencyKey("refund:" + paymentRef)
					.build());

			return new Refund(refund.getId(),
				refund.getAmount() == null ? 0 : refund.getAmount().intValue());
		}
		catch (StripeException e) {
			// A failed refund leaves the customer out of pocket. It must reach a
			// person, which is what PaymentUnavailable causes upstream.
			throw new PaymentUnavailable("could not refund " + paymentRef, e);
		}
	}

}
