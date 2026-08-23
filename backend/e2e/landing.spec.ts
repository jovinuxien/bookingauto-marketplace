import { expect, test } from '@playwright/test';

import { findFirstOpenDay } from './support';

/**
 * The pages a crawler and a first-time visitor land on.
 *
 * Every assertion here is about what is on the screen after the page has
 * finished doing whatever it does. The bug these were written for produced
 * perfect HTML and a broken page.
 */
test.describe('landing pages', () => {

  test('the city index shows cities, not the not-found screen', async ({ page }) => {
    await page.goto('/orter');

    await expect(page.getByRole('heading', { name: 'Orter' })).toBeVisible();
    await expect(page.getByRole('link', { name: /stockholm/i })).toBeVisible();

    // The failure mode, asserted explicitly. It is worth naming because the
    // page looked entirely correct in curl while showing this in a browser.
    await expect(page.getByText('Sidan finns inte')).toHaveCount(0);
  });

  test('a city and category page lists real salons', async ({ page }) => {
    await page.goto('/frisor/stockholm');

    await expect(page.getByRole('heading', { name: /Frisörer i Stockholm/ })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Salong Östermalm' })).toBeVisible();
    await expect(page.getByText('Sidan finns inte')).toHaveCount(0);
  });

  test('a second category is a second page, not the same one', async ({ page }) => {
    // Until ADR 0013 every service in the system was categorised 'har', so this
    // page could not exist and its absence was invisible: cityCategory 404s when
    // a category has no salons, and the sitemap correctly omits what 404s. Two
    // of the three page types this site was built to rank for were unreachable
    // and every signal said everything was fine.
    await page.goto('/massage/stockholm');

    await expect(page.getByRole('heading', { name: /Massage i Stockholm/ })).toBeVisible();
    await expect(page.getByText('Sidan finns inte')).toHaveCount(0);

    // And the listing is genuinely scoped to the category rather than the hair
    // page with a new heading. Klinik Vasastan sells both, so it appears on
    // both pages -- what differs is the price, which comes from the services
    // the category actually matched: 850 kr for the massage, 600 for the
    // colour. A providersIn that ignored its category argument would show 600
    // here and pass every other assertion on the page.
    await expect(page.getByText('Från 850 SEK')).toBeVisible();
    await expect(page.getByText('Från 600 SEK')).toHaveCount(0);

    // Only the salon that sells it. The other two Stockholm salons are on the
    // hair page and have no business being on this one.
    await expect(page.getByRole('link', { name: 'Klinik Vasastan' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Salong Östermalm' })).toHaveCount(0);
  });

  test('a category nobody sells is still a 404', async ({ page }) => {
    // The route exists and the category is seeded; what is missing is a salon.
    // A thin page here would be worse than no page — a site full of empty
    // category pages ranks below one without them, which is the whole reason
    // cityCategory 404s on an empty result.
    const response = await page.goto('/hudvard/stockholm');
    expect(response?.status()).toBe(404);
  });

  test('a closed day says so rather than showing nothing', async ({ page }) => {
    // The empty state is a real answer, not a failure, and it is what the whole
    // suite tripped over first: the salons are shut at weekends.
    await page.goto('/salong/salong-ostermalm?day=2026-08-16');
    await expect(page.getByText('Inga lediga tider den här dagen.')).toBeVisible();
  });

  test('the content survives without JavaScript', async ({ browser }) => {
    // The entire justification for rendering these on the server. If the page
    // is empty with JavaScript disabled, a crawler sees an empty page too.
    const context = await browser.newContext({ javaScriptEnabled: false });
    const page = await context.newPage();

    await page.goto('/frisor/stockholm');
    await expect(page.getByRole('heading', { name: /Frisörer i Stockholm/ })).toBeVisible();
    await expect(page.getByText('Salong Östermalm')).toBeVisible();

    await context.close();
  });

  test('a salon page hands over to the app and shows live times', async ({ page }) => {
    await page.goto('/salong/salong-ostermalm');

    // Rendered by the server first...
    await expect(page.getByRole('heading', { name: 'Salong Östermalm' })).toBeVisible();

    // ...then replaced by the SPA, which is what makes the slot picker work.
    // Times come from Cal, so this also proves the whole read path end to end.
    const slot = await findFirstOpenDay(page);
    await expect(slot).toBeVisible();
  });

});
