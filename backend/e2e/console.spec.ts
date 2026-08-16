import { expect, test } from '@playwright/test';

/**
 * The salon's side.
 *
 * Requires the seeded login from db/005 and seed. The credentials are test
 * fixtures for a local stack, not secrets.
 */
const EMAIL = process.env.E2E_SALON_EMAIL ?? 'anna@salong-ostermalm.se';
const PASSWORD = process.env.E2E_SALON_PASSWORD ?? 'Salong-Passw0rd!42';

test.describe('salon console', () => {

  test('the console redirects to login when signed out', async ({ page }) => {
    await page.goto('/konsol');
    await expect(page).toHaveURL(/\/logga-in/);
  });

  test('a salon signs in and sees its own bookings and earnings', async ({ page }) => {
    await page.goto('/logga-in');

    await page.getByLabel('E-post').fill(EMAIL);
    await page.getByLabel('Lösenord').fill(PASSWORD);
    await page.getByRole('button', { name: 'Logga in' }).click();

    await expect(page).toHaveURL(/\/konsol/);
    await expect(page.getByRole('heading', { name: 'Salong Östermalm' })).toBeVisible();

    // Commission is shown rather than netted away, and that is a product
    // decision worth protecting from a well-meaning tidy-up.
    await expect(page.getByText('Plattformsavgift')).toBeVisible();
    await expect(page.getByText('Kommande bokningar')).toBeVisible();
  });

  test('a wrong password says so without saying which half was wrong', async ({ page }) => {
    await page.goto('/logga-in');

    await page.getByLabel('E-post').fill(EMAIL);
    await page.getByLabel('Lösenord').fill('definitely-wrong');
    await page.getByRole('button', { name: 'Logga in' }).click();

    // One message for both failures: distinguishing them turns the login form
    // into a way to enumerate which salons are on the platform.
    await expect(page.getByText('Fel e-post eller lösenord.')).toBeVisible();
  });

});
