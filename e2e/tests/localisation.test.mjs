import assert from 'node:assert/strict';
import { after, before, describe, it } from 'node:test';

import {
  currentScreen,
  instruction,
  newSession,
  offeredLabels,
  signInWithPassword,
} from '../lib/browser.mjs';
import { resetToPasswordOnly, waitUntilReady } from '../lib/keycloak.mjs';

describe('the selection screen', () => {
  const username = 'frank';

  before(async () => {
    await waitUntilReady();
    await resetToPasswordOnly(username);
  });

  after(async () => {
    await resetToPasswordOnly(username);
  });

  it('speaks the language asked for by the client', async () => {
    const session = await newSession();

    try {
      await signInWithPassword(session.page, username, { locale: 'fr' });

      assert.equal(await currentScreen(session.page), 'select-second-factor');
      assert.equal(
        await instruction(session.page),
        'Ajoutez un second facteur à votre compte pour mieux le protéger.',
      );
      assert.deepEqual(await offeredLabels(session.page), [
        "Application d'authentification",
        'Codes de récupération',
        'Clé de sécurité',
        'Passkey',
      ]);
    } finally {
      await session.close();
    }
  });
});
