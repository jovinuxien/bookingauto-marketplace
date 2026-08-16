package se.marketplace.payments;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.Account;
import com.stripe.model.AccountLink;
import com.stripe.param.AccountCreateParams;
import com.stripe.param.AccountLinkCreateParams;

/**
 * Stripe Connect onboarding, for real.
 *
 * <p>Express accounts: Stripe hosts the KYC flow and the salon's payout
 * dashboard. The alternative, Custom, means building and maintaining identity
 * verification and a dashboard ourselves, and carrying the compliance
 * obligations that come with them. For a marketplace of small salons that is a
 * large amount of work bought for very little.
 */
@Component
@ConditionalOnProperty(name = "marketplace.payments.gateway", havingValue = "stripe")
class StripeConnect implements StripeConnectPort {

	private static final Logger log = LoggerFactory.getLogger(StripeConnect.class);

	private final StripeClient stripe;

	StripeConnect(
		@Value("${marketplace.payments.stripe.api-key:}") String apiKey,
		@Value("${marketplace.payments.stripe.base-url:}") String baseUrl) {

		StripeClient.StripeClientBuilder builder = StripeClient.builder().setApiKey(apiKey);
		if (baseUrl != null && !baseUrl.isBlank()) {
			builder.setApiBase(baseUrl);
		}
		this.stripe = builder.build();
	}

	@Override
	public ConnectedAccount createAccount(NewAccount request) {
		try {
			Account account = stripe.accounts().create(
				AccountCreateParams.builder()
					.setType(AccountCreateParams.Type.EXPRESS)
					.setCountry(request.country())
					.setEmail(request.email())
					.setBusinessType(AccountCreateParams.BusinessType.COMPANY)
					.setCapabilities(AccountCreateParams.Capabilities.builder()
						.setTransfers(AccountCreateParams.Capabilities.Transfers.builder()
							.setRequested(true).build())
						// Requested explicitly: without it a Swedish account can
						// be created and then quietly refuse the one payment
						// method most customers will reach for.
						.setSwishPayments(AccountCreateParams.Capabilities.SwishPayments.builder()
							.setRequested(true).build())
						.build())
					.putMetadata("provider_id", String.valueOf(request.providerId()))
					.build(),
				com.stripe.net.RequestOptions.builder()
					.setIdempotencyKey("connect:" + request.providerId())
					.build());

			return new ConnectedAccount(account.getId());
		}
		catch (StripeException e) {
			throw new ConnectUnavailable("could not create connected account", e);
		}
	}

	@Override
	public String onboardingLink(String accountId, String returnUrl, String refreshUrl) {
		try {
			AccountLink link = stripe.accountLinks().create(
				AccountLinkCreateParams.builder()
					.setAccount(accountId)
					.setType(AccountLinkCreateParams.Type.ACCOUNT_ONBOARDING)
					.setReturnUrl(returnUrl)
					// Where Stripe sends someone whose link has expired. Without
					// it they land on an error page with no way forward.
					.setRefreshUrl(refreshUrl)
					.build());

			return link.getUrl();
		}
		catch (StripeException e) {
			throw new ConnectUnavailable("could not create onboarding link", e);
		}
	}

	@Override
	public AccountStatus status(String accountId) {
		try {
			Account account = stripe.accounts().retrieve(accountId);

			String disabled = account.getRequirements() == null
				? null
				: account.getRequirements().getDisabledReason();

			return new AccountStatus(
				Boolean.TRUE.equals(account.getChargesEnabled()),
				Boolean.TRUE.equals(account.getPayoutsEnabled()),
				Boolean.TRUE.equals(account.getDetailsSubmitted()),
				disabled);
		}
		catch (StripeException e) {
			throw new ConnectUnavailable("could not read account " + accountId, e);
		}
	}

}
