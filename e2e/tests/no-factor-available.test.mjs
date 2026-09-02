import assert from 'node:assert/strict';
import { after, before, describe, it } from 'node:test';

import { currentScreen, newSession, signInWithPassword } from '../lib/browser.mjs';
import { setRequiredActionEnabled, waitUntilReady } from '../lib/keycloak.mjs';

const REGISTRATION_ACTIONS = [
  'CONFIGURE_TOTP',
  'webauthn-register',
  'webauthn-register-passwordless',
  'CONFIGURE_RECOVERY_AUTHN_CODES',
];

/**
 * A realm where every enrolment action is disabled must not lock its users out: there would be
 * nothing for them to pick, so the authenticator lets them through and logs a warning.
 */
describe('a realm with no second factor to offer', () => {
  const username = 'dave';

  before(async () => {
    await waitUntilReady();
    for (const alias of REGISTRATION_ACTIONS) {
      await setRequiredActionEnabled(alias, false);
    }
  });

  after(async () => {
    for (const alias of REGISTRATION_ACTIONS) {
      await setRequiredActionEnabled(alias, true);
    }
  });

  it('lets the user sign in instead of showing an empty screen', async () => {
    const session = await newSession();

    try {
      await signInWithPassword(session.page, username);

      assert.equal(await currentScreen(session.page), 'signed-in');
    } finally {
      await session.close();
    }
  });
});
