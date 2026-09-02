import assert from 'node:assert/strict';
import { after, before, describe, it } from 'node:test';

import {
  chooseFactor,
  completeWebAuthnEnrolment,
  currentScreen,
  newSession,
  signInWithPassword,
} from '../lib/browser.mjs';
import { credentialTypes, resetToPasswordOnly, waitUntilReady } from '../lib/keycloak.mjs';

describe('enrolling a passkey', () => {
  const username = 'bob';
  let session;

  before(async () => {
    await waitUntilReady();
    await resetToPasswordOnly(username);
    session = await newSession();
    await session.useVirtualAuthenticator();
  });

  after(async () => {
    await session?.close();
    await resetToPasswordOnly(username);
  });

  it('hands over to the WebAuthn required action', async () => {
    await signInWithPassword(session.page, username);
    assert.equal(await currentScreen(session.page), 'select-second-factor');

    await chooseFactor(session.page, 'webauthn-passwordless');
    assert.equal(await currentScreen(session.page), 'register-webauthn');
  });

  it('registers the passkey and completes the login', async () => {
    await completeWebAuthnEnrolment(session.page);

    assert.equal(await currentScreen(session.page), 'signed-in');
    assert.deepEqual(await credentialTypes(username), ['password', 'webauthn-passwordless']);
  });

  it('does not ask for a second factor on the next login', async () => {
    const next = await newSession();

    try {
      await next.useVirtualAuthenticator();
      await signInWithPassword(next.page, username);

      assert.equal(await currentScreen(next.page), 'signed-in');
    } finally {
      await next.close();
    }
  });
});
