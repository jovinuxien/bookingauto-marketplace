import { expect, test } from '@playwright/test';

/**
 * A salon registering itself.
 *
 * These two routes are the exact shape of the bug this suite was written for:
 * new paths added to the server's permit list, served the SPA's index.html,
 * with the router the only thing that decides whether a person sees a form or
 * "Sidan finns inte". curl gets 200 either way.
 *
 * The suite deliberately stops before clicking the verification link. That half
 * creates a Cal account and a Stripe connected account, which are real and are
 * not cleaned up, and a browser test that leaves a user behind in another system
 * on every run is one people start skipping. What it does cover is the property
 * that makes the endpoint safe to expose at all: registering creates nothing.
 */

// Unique per run: a pending registration holds its address and its slug, so a
// fixed one would pass once and then collide with itself.
const RUN = Date.now();
const EMAIL = `e2e-${RUN}@example.se`;
const SALON = `E2E Salong ${RUN}`;

test.describe('self-serve signup', () => {

  test('the registration page is a form, not the SPA fallback', async ({ page }) => {
    await page.goto('/registrera');

    await expect(page.getByRole('heading', { name: 'Registrera din salong' })).toBeVisible();
    await expect(page.getByLabel('Salongens namn')).toBeVisible();
    await expect(page.getByText('Sidan finns inte')).toHaveCount(0);
  });

  test('the header offers registration to a signed-out visitor', async ({ page }) => {
    await page.goto('/');

    await page.getByRole('link', { name: 'Anslut din salong' }).click();
    await expect(page).toHaveURL(/\/registrera/);
  });

  test('a bad form comes back field by field', async ({ page }) => {
    await page.goto('/registrera');

    await page.getByLabel('Salongens namn').fill('A');
    await page.getByLabel('Gatuadress').fill('Storgatan 1');
    await page.getByLabel('Postnummer').fill('1');
    await page.getByLabel('Ort').fill('Stockholm');
    await page.getByLabel('E-post').fill('inte-en-adress');
    await page.getByLabel('Lösenord').fill('kort');
    await page.getByRole('button', { name: 'Registrera' }).click();

    // Against the field, not collected into a summary. Someone fixing a postal
    // code should not have to work out which of six inputs a sentence is about.
    await expect(page.getByText('Skriv postnumret som fem siffror.')).toBeVisible();
    await expect(page.getByText('Skriv en giltig e-postadress.')).toBeVisible();
    await expect(page.getByText('Lösenordet måste vara minst 10 tecken.')).toBeVisible();
  });

  test('a complete form is accepted and asks the visitor to check their email', async ({ page }) => {
    await page.goto('/registrera');

    await page.getByLabel('Salongens namn').fill(SALON);
    await page.getByLabel('Vad erbjuder ni?').selectOption('har');
    await page.getByLabel('Gatuadress').fill('Storgatan 1');
    await page.getByLabel('Postnummer').fill('112 34');
    await page.getByLabel('Ort').fill('Stockholm');
    await page.getByLabel('E-post').fill(EMAIL);
    await page.getByLabel('Lösenord').fill('ett-riktigt-langt-losenord');
    await page.getByRole('button', { name: 'Registrera' }).click();

    await expect(page.getByRole('heading', { name: 'Kolla din e-post' })).toBeVisible();

    // The claim on this screen is carefully weak, and the wording is the test:
    // it says a link is on its way, never that the address was new. The server
    // does not tell the page which, because a form that reacted differently to
    // a known address would be a way to ask which salons are on the platform.
    await expect(page.getByText('Ingenting skapas förrän du klickat på den.')).toBeVisible();
  });

  test('a link that does not work says what to do next', async ({ page }) => {
    await page.goto('/verifiera?token=inte-en-riktig-token');

    await expect(page.getByRole('heading', { name: 'Det gick inte' })).toBeVisible();
    // Not a dead end: a person holding a link that failed needs somewhere to go.
    await expect(page.getByRole('link', { name: 'Registrera dig igen' })).toBeVisible();
  });

  test('a link with no token at all is handled too', async ({ page }) => {
    // Mail clients truncate. This arrives more often than it should.
    await page.goto('/verifiera');

    await expect(page.getByRole('heading', { name: 'Länken är ofullständig' })).toBeVisible();
  });

});
