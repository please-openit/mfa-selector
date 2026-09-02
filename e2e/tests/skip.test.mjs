import assert from 'node:assert/strict';
import { after, before, describe, it } from 'node:test';

import {
  canSkip,
  currentScreen,
  newSession,
  signInWithPassword,
  skipEnrolment,
} from '../lib/browser.mjs';
import {
  credentialTypes,
  patchSelectorConfig,
  resetToPasswordOnly,
  waitUntilReady,
} from '../lib/keycloak.mjs';

describe('postponing the enrolment', () => {
  const username = 'carol';
  let restoreConfig;

  before(async () => {
    await waitUntilReady();
    await resetToPasswordOnly(username);
  });

  after(async () => {
    await restoreConfig?.();
    await resetToPasswordOnly(username);
  });

  it('is refused by default', async () => {
    const session = await newSession();

    try {
      await signInWithPassword(session.page, username);

      assert.equal(await currentScreen(session.page), 'select-second-factor');
      assert.equal(await canSkip(session.page), false);
    } finally {
      await session.close();
    }
  });

  it('is allowed once the authenticator is configured for it', async () => {
    restoreConfig = await patchSelectorConfig({ 'allow.skip': 'true' });
    const session = await newSession();

    try {
      await signInWithPassword(session.page, username);
      assert.equal(await canSkip(session.page), true);

      await skipEnrolment(session.page);
      assert.equal(await currentScreen(session.page), 'signed-in');
      assert.deepEqual(await credentialTypes(username), ['password']);
    } finally {
      await session.close();
    }
  });

  it('leaves the screen in place for the next login', async () => {
    const session = await newSession();

    try {
      await signInWithPassword(session.page, username);

      assert.equal(await currentScreen(session.page), 'select-second-factor');
    } finally {
      await session.close();
    }
  });
});
