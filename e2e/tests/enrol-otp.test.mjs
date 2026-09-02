import assert from 'node:assert/strict';
import { after, before, describe, it } from 'node:test';

import {
  answerOtpChallenge,
  chooseFactor,
  completeOtpEnrolment,
  currentScreen,
  newSession,
  offeredFactors,
  signInWithPassword,
} from '../lib/browser.mjs';
import { credentialTypes, resetToPasswordOnly, waitUntilReady } from '../lib/keycloak.mjs';

describe('a user with only a password', () => {
  const username = 'alice';
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

  it('is offered every second factor the realm can register, ordered by category', async () => {
    await signInWithPassword(session.page, username);

    assert.equal(await currentScreen(session.page), 'select-second-factor');
    assert.deepEqual(await offeredFactors(session.page), [
      'otp',
      'recovery-authn-codes',
      'webauthn',
      'webauthn-passwordless',
    ]);
  });

  it('is handed over to the OTP required action once a factor is picked', async () => {
    await chooseFactor(session.page, 'otp');

    assert.equal(await currentScreen(session.page), 'configure-otp');
  });

  it('finishes logging in with the credential registered', async () => {
    secret = await completeOtpEnrolment(session.page);

    assert.equal(await currentScreen(session.page), 'signed-in');
    assert.deepEqual(await credentialTypes(username), ['otp', 'password']);
  });

  it('is not asked again on the next login, and is challenged for the code instead', async () => {
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
