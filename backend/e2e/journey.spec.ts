import { expect, test } from '@playwright/test';

import { findFirstOpenDay } from './support';

/**
 * The consumer journey, as a person walks it.
 *
 * Search reads a stale-tolerant index and the salon page asks Cal directly, so
 * this is also the only test that proves those two agree well enough for
 * someone to get from one to the other.
 */
test.describe('booking journey', () => {

  test('the home page offers a search', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByRole('heading', { name: 'Hitta en tid' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Sök i Stockholm' })).toBeVisible();
  });

  test('searching returns salons with prices and distances', async ({ page }) => {
    await page.goto('/');
    await page.getByRole('button', { name: 'Sök i Stockholm' }).click();

    await expect(page).toHaveURL(/\/sok\?/);

    // Step to an open day for the same reason as the slot picker: the seeded
    // salons are closed at weekends and an empty Sunday is a correct answer.
    const result = page.getByRole('link', { name: /Salong|Klinik/ }).first();
    for (let day = 0; day < 8 && !(await result.isVisible().catch(() => false)); day++) {
      await page.getByRole('button', { name: '›' }).click();
      await page.waitForLoadState('networkidle');
    }

    await expect(result).toBeVisible();

    // Scoped to the result card. An unscoped match found the radius <select>'s
    // "Inom 2 km" option, which is hidden -- a locator loose enough to match
    // the filter controls is not testing the results at all.
    // No trailing \b: adjacent elements concatenate into "1.1 kmKlippning",
    // so the boundary sits between two word characters and never matches.
    // Asserting the separator instead is both looser and closer to what a
    // person reads.
    const card = page.locator('.card').first();
    await expect(card).toContainText(/·\s*\d+(\.\d+)?\s*(m|km)/);
    await expect(card).toContainText(/\d+\s*kr/);
  });

  test('a slot leads to a checkout that names the time', async ({ page }) => {
    await page.goto('/salong/salong-ostermalm');

    const slot = await findFirstOpenDay(page);
    const time = await slot.textContent();

    await slot.click();

    await expect(page).toHaveURL(/\/boka\//);
    await expect(page.getByRole('heading', { name: 'Bekräfta bokning' })).toBeVisible();
    // The time the customer clicked has to be the time they are asked to
    // confirm. A funnel that quietly books a different minute is the worst
    // possible bug and the easiest to not notice.
    await expect(page.getByText(new RegExp(time!.trim()))).toBeVisible();
  });

});
