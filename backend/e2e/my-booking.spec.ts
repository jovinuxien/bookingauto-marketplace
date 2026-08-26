import { expect, test } from '@playwright/test';

/**
 * The page a confirmation email points at.
 *
 * A new SPA route is exactly the shape of bug this suite exists for: the server
 * can permit `/bokning`, return a perfectly good 200, and the browser can still
 * show "Sidan finns inte" because the router was never told about it. Nothing
 * else in this project runs JavaScript, so nothing else could tell the
 * difference.
 *
 * These stop short of cancelling a real booking, the same way the signup tests
 * stop short of clicking a verification link. Cancelling calls Cal and Stripe
 * and cannot be undone — a browser suite that releases a slot and refunds a
 * charge on every run is one people stop running. What is covered is the half
 * that is safe and is still the half that broke: that the route resolves, and
 * that every way of arriving without a usable token is answered by this page
 * rather than by the not-found screen.
 */
test.describe('a customer reaching their booking', () => {

  test('a link with no token explains itself rather than 404ing', async ({ page }) => {
    await page.goto('/bokning');

    await expect(page.getByRole('heading', { name: 'Länken är ofullständig' })).toBeVisible();
    await expect(page.getByText('Sidan finns inte')).toHaveCount(0);
  });

  test('a token that signs nothing is told so, and offers a way out', async ({ page }) => {
    await page.goto('/bokning?token=42.notarealsignature');

    await expect(page.getByRole('heading', { name: 'Vi hittar ingen bokning' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Till startsidan' })).toBeVisible();

    // The one thing the page must never do on a bad token: nothing on screen
    // may reveal whether booking 42 exists.
    await expect(page.getByText('Sidan finns inte')).toHaveCount(0);
  });

  test('a token for a booking that does not exist looks the same', async ({ page }) => {
    // Told apart from the forgery above nowhere the visitor can see, which is
    // the property that stops the page being a way to enumerate sales.
    await page.goto('/bokning?token=999999.notarealsignature');

    await expect(page.getByRole('heading', { name: 'Vi hittar ingen bokning' })).toBeVisible();
  });

  test('nothing is cancelled by arriving', async ({ page }) => {
    const cancels: string[] = [];
    page.on('request', request => {
      if (request.url().includes('/api/bookings/cancel')) {
        cancels.push(request.url());
      }
    });

    await page.goto('/bokning?token=42.notarealsignature');
    await page.waitForLoadState('networkidle');

    // The verification page acts on arrival because the click already happened
    // in the mail client. This one must not: the click meant "show me my
    // booking", and cancelling is a separate, confirmed decision.
    expect(cancels).toHaveLength(0);
  });

});
