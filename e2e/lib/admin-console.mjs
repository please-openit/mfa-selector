import { admin, BASE_URL } from './keycloak.mjs';
import { submitPassword } from './browser.mjs';

const CONSOLE_REALM = 'master';
const NAVIGATION = { waitUntil: 'domcontentloaded', timeout: 20000 };

/**
 * Puts the admin console into French.
 *
 * The console follows the locale of the administrator's account, in the realm they sign in to, so
 * this is what a French speaking team configures once. The development stack keeps master in
 * memory, which is why a test cannot assume it was done.
 */
export async function useFrenchAdminConsole(username = 'admin') {
  const realm = CONSOLE_REALM;
  const settings = await admin('', { realm });

  await admin('', {
    method: 'PUT',
    realm,
    body: { ...settings, internationalizationEnabled: true, supportedLocales: ['en', 'fr'], defaultLocale: 'fr' },
  });

  const [administrator] = await admin(`/users?username=${encodeURIComponent(username)}&exact=true`, { realm });

  await admin(`/users/${administrator.id}`, {
    method: 'PUT',
    realm,
    body: { ...administrator, attributes: { ...(administrator.attributes ?? {}), locale: ['fr'] } },
  });
}

/** Signs in to the admin console and opens the editor of one authentication flow. */
export async function openFlow(page, { realm, flowAlias, username = 'admin', password = 'admin' }) {
  const flows = await admin('/authentication/flows', { realm });
  const flow = flows.find((candidate) => candidate.alias === flowAlias);

  if (!flow) {
    throw new Error(`Realm '${realm}' has no flow called '${flowAlias}'`);
  }

  await page.goto(
    `${BASE_URL}/admin/${CONSOLE_REALM}/console/#/${realm}/authentication/${flow.id}/${flow.providerId}`,
    NAVIGATION,
  );

  await page.waitForSelector('#username', { visible: true, timeout: NAVIGATION.timeout });
  await submitPassword(page, username, password);
}

/** Opens the configuration dialog of one step, found by the name the console shows for it. */
export async function openStepConfiguration(page, stepName) {
  await page.waitForSelector(`[data-testid="${stepName}"]`, { timeout: 30000 });

  const opened = await page.evaluate((name) => {
    const row = document.querySelector(`[data-testid="${name}"]`)?.closest('li, tr');
    const gear = row?.querySelector('button[aria-label="Settings"], button[aria-label="Paramètres"]');
    gear?.click();
    return Boolean(gear);
  }, stepName);

  if (!opened) {
    throw new Error(`No configuration button on the '${stepName}' step`);
  }

  await page.waitForSelector('.pf-v5-c-modal-box', { visible: true, timeout: 10000 });
}

/** Everything the configuration dialog reads, as one string. */
export async function configurationDialogText(page) {
  return page.$eval('.pf-v5-c-modal-box', (dialog) => dialog.innerText.replace(/\s+/g, ' ').trim());
}
