import { expect, test } from '@playwright/test';
import { findFirstOpenDay } from './support';

/**
 * The plate, asked first (ADR 0016 phase 2).
 *
 * Runs against the dev stack with no registry configured, which is the
 * honest default: every lookup is a 404, and the page must still let the
 * customer through. What is being checked is the carrying -- typed once on
 * the search page, present on the salon page, pre-filled at checkout -- and
 * that a salon search never shows the box at all.
 *
 * Needs the seeded workshop (provider slug dackcenter-hammarby, category
 * dack) from the end-to-end run; skips itself cleanly if it is not there.
 */

const WORKSHOP = 'dackcenter-hammarby';

test.describe('regnr-first search', () => {

  test('a salon search has no plate box', async ({ page }) => {
    await page.goto('/sok?category=har');
    await expect(page.getByRole('heading', { level: 1 }).or(page.getByLabel('Sök med egna ord'))).toBeVisible();
    await expect(page.getByLabel('Registreringsnummer')).toHaveCount(0);
  });

  test('a tyre search asks for the plate and carries it to the salon page', async ({ page }) => {
    await page.goto('/sok?category=dack');
    const box = page.getByLabel('Registreringsnummer');
    await expect(box).toBeVisible();

    await box.fill('abc 123');
    await page.getByRole('button', { name: 'Hämta bil' }).click();

    // No registry in dev: the register "does not know" every plate, and the
    // page says so without blocking anything.
    await expect(page.getByText('Vi hittar ingen bil med numret ABC123')).toBeVisible();
    await expect(page).toHaveURL(/regnr=ABC123/);

    // Every result link now carries the plate.
    const links = page.getByRole('link', { name: 'Visa tider' });
    if (await links.count() > 0) {
      await expect(links.first()).toHaveAttribute('href', /regnr=ABC123/);
    }
  });

  test('the salon page shows the plate and checkout is pre-filled', async ({ page }) => {
    const response = await page.goto(`/salong/${WORKSHOP}?regnr=ABC123`);
    test.skip(response?.status() !== 200, 'seeded workshop not present');

    // Greeted with the plate already looked up.
    await expect(page.getByLabel('Registreringsnummer')).toHaveValue('ABC123');
    await expect(page.getByText(/ABC123/).first()).toBeVisible();

    // Pick the first free time on any day in the next week.
    const slot = await findFirstOpenDay(page);
    await slot.click();

    await expect(page).toHaveURL(/\/boka\/\d+\?.*fordon=1.*regnr=ABC123/);
    await expect(page.getByLabel('Registreringsnummer')).toHaveValue('ABC123');
  });

});
