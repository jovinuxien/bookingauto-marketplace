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
