import assert from 'node:assert/strict';
import { after, before, describe, it } from 'node:test';

import {
  configurationDialogText,
  openFlow,
  openStepConfiguration,
  useFrenchAdminConsole,
} from '../lib/admin-console.mjs';
import { newSession } from '../lib/browser.mjs';
import { BASE_URL, FLOW_REALM as realm, waitUntilReady } from '../lib/keycloak.mjs';

/** The bundle the admin console fetches for a realm, as a plain object. */
async function adminBundle(locale) {
  const response = await fetch(`${BASE_URL}/resources/${realm}/admin/${locale}`);
  const entries = await response.json();
  return Object.fromEntries(entries.map(({ key, value }) => [key, value]));
}

describe('the configuration screen of the authenticator', () => {
  let session;

  before(async () => {
    await waitUntilReady({ realm });
    await useFrenchAdminConsole();
    session = await newSession({ locale: 'fr' });
  });

  after(async () => {
    await session?.close();
  });

  it('has its labels shipped in both languages', async () => {
    const french = await adminBundle('fr');
    const english = await adminBundle('en');

    assert.equal(french['secondFactorSelector.offeredActions.label'], 'Seconds facteurs proposés');
    assert.equal(french['secondFactorSelector.allowSkip.label'], 'Autoriser le report');
    assert.equal(english['secondFactorSelector.offeredActions.label'], 'Offered second factors');
    assert.equal(english['secondFactorSelector.allowSkip.label'], 'Allow skipping');

    for (const key of Object.keys(french).filter((k) => k.startsWith('secondFactorSelector'))) {
      assert.ok(english[key], `${key} is missing from the English bundle`);
      // i18next has no MessageFormat behind it, so a doubled quote would be shown as written.
      assert.doesNotMatch(french[key], /''/, `${key} must not double its apostrophes`);
    }
  });

  it('is shown in French in the admin console', async () => {
    await openFlow(session.page, { realm, flowAlias: 'browser with second factor' });
    await openStepConfiguration(session.page, 'Second Factor Selector');

    const dialog = await configurationDialogText(session.page);

    assert.match(dialog, /Seconds facteurs proposés/);
    assert.match(dialog, /Autoriser le report/);
    assert.doesNotMatch(dialog, /secondFactorSelector\./, 'a message key leaked to the screen');
  });
});
