import assert from 'node:assert/strict';
import { after, before, describe, it } from 'node:test';

import { currentScreen, errorMessage, newSession, signInWithPassword } from '../lib/browser.mjs';
import { credentialTypes, resetToPasswordOnly, waitUntilReady } from '../lib/keycloak.mjs';

describe('a tampered selection', () => {
  const username = 'erin';
  let session;

  before(async () => {
    await waitUntilReady();
    await resetToPasswordOnly(username);
    session = await newSession();
  });

  after(async () => {
    await session?.close();
    await resetToPasswordOnly(username);
  });

  it('is refused and the screen is shown again', async () => {
    await signInWithPassword(session.page, username);
    assert.equal(await currentScreen(session.page), 'select-second-factor');

    // A required action that is not on offer: only the ones the screen proposed are accepted.
    await session.page.$eval('#kc-second-factor-otp', (button) => {
      button.value = 'UPDATE_PASSWORD';
    });

    await Promise.all([
      session.page.waitForNavigation({ waitUntil: 'domcontentloaded' }),
      session.page.click('#kc-second-factor-otp'),
    ]);

    assert.equal(await currentScreen(session.page), 'select-second-factor');
    assert.match(await errorMessage(session.page), /Choose one of the methods below/);
    assert.deepEqual(await credentialTypes(username), ['password']);
  });
});
