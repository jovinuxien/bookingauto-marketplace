import { expect, type Page } from '@playwright/test';

/**
 * Steps forward a day at a time until times appear.
 *
 * Written after the first run failed on a Sunday. The seeded salons are closed
 * at weekends, so a test that assumes availability "today" passes on weekdays
 * and fails every Saturday — and a suite that fails two days in seven gets
 * dismissed as flaky, which is worse than having no suite at all.
 *
 * Clicking forward is also what a customer does, so this exercises the day
 * navigation rather than working around it.
 *
 * A week with no availability anywhere is a real failure, not a fixture
 * problem, so the search is bounded and gives up loudly.
 */
export async function findFirstOpenDay(page: Page, maxDays = 8) {
  const slot = page.getByRole('button', { name: /^\d{2}:\d{2}$/ }).first();

  for (let day = 0; day < maxDays; day++) {
    if (await slot.isVisible().catch(() => false)) {
      return slot;
    }
    await page.getByRole('button', { name: '›' }).click();
    // The list is cleared while loading, on purpose: a stale time must not be
    // clickable. So wait for the request to settle before looking again.
    await page.waitForLoadState('networkidle');
  }

  expect(await slot.isVisible(), `no availability within ${maxDays} days`).toBe(true);
  return slot;
}
