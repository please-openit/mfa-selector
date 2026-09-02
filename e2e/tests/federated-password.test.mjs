import assert from 'node:assert/strict';
import { after, before, describe, it } from 'node:test';

import {
  answerOtpChallenge,
  chooseFactor,
  completeOtpEnrolment,
  completeWebAuthnEnrolment,
  currentScreen,
  newSession,
  offeredFactors,
  signInWithPassword,
} from '../lib/browser.mjs';
import {
  credentialTypes,
  federatedCredentialTypes,
  isFederated,
  resetToPasswordOnly,
  waitUntilReady,
} from '../lib/keycloak.mjs';

/**
 * The deployment this project targets delegates passwords, and only passwords, to the customer's
 * directory. Second factors are always registered by Keycloak itself, so the authenticator must see
 * a directory user as having no second factor, and must not mistake the delegated password for one.
 */
describe('a user whose password lives in the directory', () => {
  const username = 'grace';
  let session;
  let secret;

  before(async () => {
    await waitUntilReady();
    await resetToPasswordOnly(username);
    session = await newSession();
  });

  after(async () => {
    await session?.close();
    await resetToPasswordOnly(username);
  });

  it('signs in with the directory password and nothing stored in Keycloak', async () => {
    assert.equal(await isFederated(username), true);
    assert.deepEqual(await credentialTypes(username), []);
    assert.deepEqual(await federatedCredentialTypes(username), ['password']);
  });

  it('is asked to enrol a second factor all the same', async () => {
    await signInWithPassword(session.page, username);

    assert.equal(await currentScreen(session.page), 'select-second-factor');
    assert.deepEqual(await offeredFactors(session.page), [
      'otp',
      'recovery-authn-codes',
      'webauthn',
      'webauthn-passwordless',
    ]);
  });

  it('gets the second factor registered by Keycloak, leaving the directory untouched', async () => {
    await chooseFactor(session.page, 'otp');
    assert.equal(await currentScreen(session.page), 'configure-otp');

    secret = await completeOtpEnrolment(session.page);

    assert.equal(await currentScreen(session.page), 'signed-in');
    assert.deepEqual(await credentialTypes(username), ['otp']);
    assert.deepEqual(await federatedCredentialTypes(username), ['password']);
  });

  it('is not asked again, and signs in with the directory password plus the code', async () => {
    const next = await newSession();

    try {
      await signInWithPassword(next.page, username);
      assert.equal(await currentScreen(next.page), 'otp-challenge');

      await answerOtpChallenge(next.page, secret);
      assert.equal(await currentScreen(next.page), 'signed-in');
    } finally {
      await next.close();
    }
  });
});

describe('a directory user enrolling a passkey', () => {
  const username = 'heidi';
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

  it('has the passkey stored by Keycloak, not by the directory', async () => {
    await signInWithPassword(session.page, username);
    assert.equal(await currentScreen(session.page), 'select-second-factor');

    await chooseFactor(session.page, 'webauthn-passwordless');
    await completeWebAuthnEnrolment(session.page);

    assert.equal(await currentScreen(session.page), 'signed-in');
    assert.deepEqual(await credentialTypes(username), ['webauthn-passwordless']);
    assert.deepEqual(await federatedCredentialTypes(username), ['password']);
  });

  it('is not asked for a second factor again', async () => {
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
