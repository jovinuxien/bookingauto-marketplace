import { expect, test } from '@playwright/test';

/**
 * The free-text search box, in a browser, with the feature switched off.
 *
 * That combination is the point. `/api/search/ask` is opt-in per deployment and
 * off by default, so the state this suite runs in — no API key, no model — is
 * also the state most environments are in most of the time. ADR 0012 says an
 * optional feature must not be able to break the page it sits on; this is the
 * only test that can actually check that, because the failure it guards against
 * is a rendered one.
 *
 * It caught the shape of a real class of bug already: with a bad key the
 * endpoint returned HTTP 500, and nothing on the server side noticed, because
 * every unit test stubbed the exception that Kotlin actually throws.
 */
test.describe('search by sentence', () => {

  test('the box is there and takes a sentence', async ({ page }) => {
    await page.goto('/sok?lat=59.3293&lon=18.0686');

    const box = page.getByRole('searchbox', { name: 'Sök med egna ord' });
    await expect(box).toBeVisible();

    // Capped in the DOM as well as on the server. The server refuses anything
    // longer by answering with the plain query; the browser should not have
    // sent it in the first place.
    await expect(box).toHaveAttribute('maxlength', '200');
  });

  test('asking never costs the customer their results', async ({ page }) => {
    await page.goto('/sok?lat=59.3293&lon=18.0686');

    await page.getByRole('searchbox', { name: 'Sök med egna ord' })
      .fill('balayage på lördag eftermiddag');
    await page.getByRole('button', { name: 'Sök' }).click();
    await page.waitForLoadState('networkidle');

    // Nothing broke. With the gate off the sentence is not read at all, so
    // there is no interpretation to show — and crucially no error either. The
    // customer is looking at the ordinary search they would have got anyway.
    await expect(page.locator('.alert-warning')).toHaveCount(0);
    await expect(page.getByRole('button', { name: '›' })).toBeVisible();

    // And it still works afterwards. The seeded salons are closed at weekends,
    // so step forward the way a customer does rather than assuming today is a
    // day anyone is open.
    const result = page.getByRole('link', { name: /Salong|Klinik/ }).first();
    for (let day = 0; day < 8 && !(await result.isVisible().catch(() => false)); day++) {
      await page.getByRole('button', { name: '›' }).click();
      await page.waitForLoadState('networkidle');
    }

    await expect(result).toBeVisible();
  });

  test('an empty box cannot be submitted', async ({ page }) => {
    await page.goto('/sok?lat=59.3293&lon=18.0686');

    // Blank is not a question, and the server would answer it with the plain
    // query anyway — but a request that can only produce the page already on
    // screen should not leave the browser.
    await expect(page.getByRole('button', { name: 'Sök' })).toBeDisabled();
  });

});
