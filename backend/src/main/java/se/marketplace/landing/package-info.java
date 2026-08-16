/**
 * The pages a search engine has to be able to read.
 *
 * <p>The booking journey is a SPA, and a crawler handed an empty
 * {@code <div id="root">} has nothing to rank. City and service pages are how a
 * marketplace like this is found at all — someone searches "frisör stockholm"
 * long before they know a salon's name — so those pages are rendered as HTML on
 * the server.
 *
 * <p>Deliberately narrow. Only the entry points are server-rendered; everything
 * behind them stays a SPA. See the amendment to ADR 0004.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Landing")
package se.marketplace.landing;
