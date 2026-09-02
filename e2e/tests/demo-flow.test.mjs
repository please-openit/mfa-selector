import assert from 'node:assert/strict';
import { after, before, describe, it } from 'node:test';

import {
  accountConsoleSections,
  answerOtpChallenge,
  answerWebAuthnChallenge,
  canChooseAnotherFactor,
  chooseAnotherFactor,
  chooseFactor,
  completeOtpEnrolment,
  completeWebAuthnEnrolment,
  currentScreen,
  instruction,
  myFactors,
  newSession,
  offeredFactors,
  openAccountConsole,
  offeredLabels,
  signInWithPassword,
  startAction,
  submitPassword,
  useFactor,
} from '../lib/browser.mjs';
import {
  credentialTypes,
  FLOW_REALM as realm,
  isFederated,
  resetToPasswordOnly,
  waitUntilReady,
} from '../lib/keycloak.mjs';

/**
 * The flow shown during the live demonstration, shaped the way it would be deployed: password,
 * then either enrol a second factor or verify the one the account already carries, with a choice
 * between them when several are registered.
 */
describe('the demonstration flow, for an account with only a password', () => {
  const username = 'paul';
  let session;
  let secret;

  before(async () => {
    await waitUntilReady({ realm });
    await resetToPasswordOnly(username, realm);
    session = await newSession({ locale: 'fr' });
    await session.useVirtualAuthenticator();
  });

  after(async () => {
    await session?.close();
    await resetToPasswordOnly(username, realm);
  });

  it('offers the three factors the flow can verify, in French, without recovery codes', async () => {
    await signInWithPassword(session.page, username, { realm });

    assert.equal(await currentScreen(session.page), 'select-second-factor');
    assert.deepEqual(await offeredFactors(session.page), ['otp', 'webauthn', 'webauthn-passwordless']);
    assert.equal(
      await instruction(session.page),
      'Ajoutez un second facteur à votre compte pour mieux le protéger.',
    );
    assert.deepEqual(await offeredLabels(session.page), [
      "Application d'authentification",
      'Clé de sécurité',
      'Passkey',
    ]);
  });

  it('enrols the chosen factor and completes the login', async () => {
    await chooseFactor(session.page, 'otp');
    assert.equal(await currentScreen(session.page), 'configure-otp');

    secret = await completeOtpEnrolment(session.page);

    assert.equal(await currentScreen(session.page), 'signed-in');
    assert.deepEqual(await credentialTypes(username, realm), ['otp', 'password']);
  });

  it('asks for that factor on the next login, with no other way to try', async () => {
    const next = await newSession({ locale: 'fr' });

    try {
      await signInWithPassword(next.page, username, { realm });

      assert.equal(await currentScreen(next.page), 'otp-challenge');
      assert.equal(await canChooseAnotherFactor(next.page), false);

      await answerOtpChallenge(next.page, secret);
      assert.equal(await currentScreen(next.page), 'signed-in');
    } finally {
      await next.close();
    }
  });

  it('accepts a second factor added afterwards, the way the account console does', async () => {
    await startAction(session.page, 'webauthn-register', { realm });
    assert.equal(await currentScreen(session.page), 'register-webauthn');

    await completeWebAuthnEnrolment(session.page);

    assert.equal(await currentScreen(session.page), 'signed-in');
    assert.deepEqual(await credentialTypes(username, realm), ['otp', 'password', 'webauthn']);
  });

  it('then lets the user pick which factor to use', async () => {
    // Same browser, so the security key registered above is still plugged in.
    await session.forgetSession();
    await signInWithPassword(session.page, username, { realm });

    assert.equal(await currentScreen(session.page), 'otp-challenge');
    assert.equal(await canChooseAnotherFactor(session.page), true);

    await chooseAnotherFactor(session.page);
    assert.equal(await currentScreen(session.page), 'choose-among-my-factors');
    assert.deepEqual(await myFactors(session.page), ["Application d'authentification", 'Clé de sécurité']);

    await useFactor(session.page, 'Clé de sécurité');
    assert.equal(await currentScreen(session.page), 'webauthn-challenge');

    await answerWebAuthnChallenge(session.page);
    assert.equal(await currentScreen(session.page), 'signed-in');
  });
});

describe('the demonstration flow, for a directory account', () => {
  const username = 'olivier';
  let session;

  before(async () => {
    await waitUntilReady({ realm });
    await resetToPasswordOnly(username, realm);
    session = await newSession({ locale: 'fr' });
  });

  after(async () => {
    await session?.close();
    await resetToPasswordOnly(username, realm);
  });

  it('behaves the same with the password held by the directory', async () => {
    assert.equal(await isFederated(username, realm), true);
    assert.deepEqual(await credentialTypes(username, realm), []);

    await signInWithPassword(session.page, username, { realm });
    assert.equal(await currentScreen(session.page), 'select-second-factor');

    await chooseFactor(session.page, 'otp');
    const secret = await completeOtpEnrolment(session.page);

    assert.equal(await currentScreen(session.page), 'signed-in');
    assert.deepEqual(await credentialTypes(username, realm), ['otp']);

    const next = await newSession({ locale: 'fr' });
    try {
      await signInWithPassword(next.page, username, { realm });
      assert.equal(await currentScreen(next.page), 'otp-challenge');

      await answerOtpChallenge(next.page, secret);
      assert.equal(await currentScreen(next.page), 'signed-in');
    } finally {
      await next.close();
    }
  });
});


/**
 * The demonstration is given by signing in to the account console, which is also where the
 * presenter adds a second credential to show the choice between factors.
 */
describe('the account console the demonstration is given on', () => {
  const username = 'nadia';
  let session;

  before(async () => {
    await waitUntilReady({ realm });
    await resetToPasswordOnly(username, realm);
    session = await newSession({ locale: 'fr' });
  });

  after(async () => {
    await session?.close();
    await resetToPasswordOnly(username, realm);
  });

  it('puts the enrolment in front of a user who has no second factor', async () => {
    await openAccountConsole(session.page, { realm });
    assert.equal(await currentScreen(session.page), 'login');

    await submitPassword(session.page, username);
    assert.equal(await currentScreen(session.page), 'select-second-factor');
  });

  it('opens once the factor is enrolled, ready to add another one', async () => {
    await chooseFactor(session.page, 'otp');
    await completeOtpEnrolment(session.page);

    assert.equal(await currentScreen(session.page), 'account-console');
    assert.ok((await accountConsoleSections(session.page)).includes('Connexion'));
    assert.deepEqual(await credentialTypes(username, realm), ['otp', 'password']);
  });
});
