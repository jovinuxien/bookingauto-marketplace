import { expect, test } from '@playwright/test';
import { findFirstOpenDay } from './support';

/**
 * The embedded storefront (ADR 0018).
 *
 * What matters: the page renders bare (no site header -- the host page is
 * the chrome), shows live times, and a chosen time opens the marketplace
 * checkout in a NEW tab carrying kanal=widget -- because payments do not
 * happen in iframes on other people's origins.
 */

const WORKSHOP = 'dackcenter-hammarby';

test.describe('workshop widget', () => {

  test('renders bare, with services and live times', async ({ page }) => {
    const response = await page.goto(`/widget/${WORKSHOP}`);
    test.skip(response?.status() !== 200, 'seeded workshop not present');

    await expect(page.getByText('Däckcenter Hammarby')).toBeVisible();
    // No marketplace chrome inside the frame.
    await expect(page.getByRole('link', { name: 'Anslut din salong' })).toHaveCount(0);
    await expect(page.getByLabel('Tjänst')).toBeVisible();
  });

  test('a chosen time opens the checkout in a new tab with kanal=widget', async ({ page, context }) => {
    const response = await page.goto(`/widget/${WORKSHOP}`);
    test.skip(response?.status() !== 200, 'seeded workshop not present');

    const slot = await findFirstOpenDay(page);
    const opened = context.waitForEvent('page');
    await slot.click();

    const checkout = await opened;
    await checkout.waitForLoadState();
    expect(checkout.url()).toMatch(/\/boka\/\d+\?.*kanal=widget/);
    await expect(checkout.getByRole('heading', { name: 'Bekräfta bokning' })).toBeVisible();
  });

});
