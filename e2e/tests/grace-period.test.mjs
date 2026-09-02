import assert from 'node:assert/strict';
import { after, before, beforeEach, describe, it } from 'node:test';

import {
  canSkip,
  chooseFactor,
  currentScreen,
  instruction,
  newSession,
  signInWithPassword,
  skipEnrolment,
} from '../lib/browser.mjs';
import {
  FLOW_REALM as realm,
  patchSelectorConfig,
  requiredActionsOf,
  resetToPasswordOnly,
  waitUntilReady,
} from '../lib/keycloak.mjs';

const hoursFromNow = (hours) => new Date(Date.now() + hours * 3_600_000).toISOString();

/** The deadline paragraph, when the screen shows one. */
async function deadlineNotice(page) {
  const element = await page.$('#kc-select-second-factor-deadline');
  return element ? (await element.evaluate((node) => node.textContent.trim())) : null;
}

describe('postponing within a time window', () => {
  const username = 'quentin';
  let restore;
  let session;

  before(async () => {
    await waitUntilReady({ realm });
  });

  beforeEach(async () => {
    await resetToPasswordOnly(username, realm);
  });

  after(async () => {
    await restore?.();
    await session?.close();
    await resetToPasswordOnly(username, realm);
  });

  async function signIn(config) {
    await restore?.();
    restore = await patchSelectorConfig(config, realm);
    await session?.close();
    session = await newSession({ locale: 'fr' });
    await signInWithPassword(session.page, username, { realm });
    assert.equal(await currentScreen(session.page), 'select-second-factor');
  }

  it('announces the deadline and lets the user postpone before it', async () => {
    await signIn({
      'allow.skip': 'true',
      'announced.deadline': hoursFromNow(48),
      'grace.period.end': hoursFromNow(96),
    });

    assert.equal(await canSkip(session.page), true);
    assert.match(await deadlineNotice(session.page), /^À configurer avant le /);
  });

  it('says the deadline has passed while the grace period still runs', async () => {
    await signIn({
      'allow.skip': 'true',
      'announced.deadline': hoursFromNow(-48),
      'grace.period.end': hoursFromNow(96),
    });

    assert.equal(await canSkip(session.page), true);
    assert.match(await deadlineNotice(session.page), /est dépassé/);
  });

  it('forces the enrolment once the grace period is over', async () => {
    await signIn({
      'allow.skip': 'true',
      'announced.deadline': hoursFromNow(-96),
      'grace.period.end': hoursFromNow(-48),
    });

    assert.equal(await canSkip(session.page), false);
  });

  it('announces the end of the grace period when it comes first', async () => {
    await signIn({
      'allow.skip': 'true',
      'announced.deadline': hoursFromNow(96),
      'grace.period.end': hoursFromNow(48),
    });

    // Announcing the later deadline would promise time the server will not give.
    const notice = await deadlineNotice(session.page);
    const announced = new Date(Date.now() + 48 * 3_600_000);
    assert.match(notice, new RegExp(String(announced.getFullYear())));
    assert.equal(await canSkip(session.page), true);
  });

  it('keeps postponing unlimited when no date is configured', async () => {
    await signIn({ 'allow.skip': 'true', 'announced.deadline': '', 'grace.period.end': '' });

    assert.equal(await canSkip(session.page), true);
    assert.equal(await deadlineNotice(session.page), null);
    assert.equal(
      await instruction(session.page),
      'Ajoutez un second facteur à votre compte pour mieux le protéger.',
    );
  });

  it('refuses a postponement asked for after the grace period, however it is submitted', async () => {
    await signIn({ 'allow.skip': 'true', 'grace.period.end': hoursFromNow(-1) });

    // No button, so the request can only be forged.
    await session.page.evaluate(() => {
      const form = document.querySelector('#kc-select-second-factor-form');
      const forged = document.createElement('button');
      forged.type = 'submit';
      forged.name = 'skip-second-factor';
      forged.value = 'true';
      forged.id = 'forged-skip';
      form.appendChild(forged);
    });

    await Promise.all([
      session.page.waitForNavigation({ waitUntil: 'domcontentloaded' }),
      session.page.evaluate(() => document.querySelector('#forged-skip').click()),
    ]);

    assert.equal(await currentScreen(session.page), 'select-second-factor');
    assert.deepEqual(await requiredActionsOf(username, realm), []);
  });
});

describe('a factor picked in a hurry', () => {
  const username = 'quentin';
  let restore;

  before(async () => {
    await waitUntilReady({ realm });
    await resetToPasswordOnly(username, realm);
    restore = await patchSelectorConfig(
      { 'allow.skip': 'true', 'announced.deadline': hoursFromNow(48), 'grace.period.end': hoursFromNow(96) },
      realm,
    );
  });

  after(async () => {
    await restore?.();
    await resetToPasswordOnly(username, realm);
  });

  it('stays pending on the account after the enrolment is abandoned', async () => {
    const session = await newSession({ locale: 'fr' });

    try {
      await signInWithPassword(session.page, username, { realm });
      await chooseFactor(session.page, 'otp');

      assert.equal(await currentScreen(session.page), 'configure-otp');
      assert.deepEqual(await requiredActionsOf(username, realm), ['CONFIGURE_TOTP']);
    } finally {
      await session.close();
    }
  });

  it('is taken back when the user postpones on the next login', async () => {
    const session = await newSession({ locale: 'fr' });

    try {
      await signInWithPassword(session.page, username, { realm });
      assert.equal(await currentScreen(session.page), 'select-second-factor');

      await skipEnrolment(session.page);

      assert.equal(await currentScreen(session.page), 'signed-in');
      assert.deepEqual(await requiredActionsOf(username, realm), []);
    } finally {
      await session.close();
    }
  });
});

describe('a directory account, whose attributes Keycloak may not write', () => {
  const username = 'olivier';
  let restore;
  let session;

  before(async () => {
    await waitUntilReady({ realm });
    await resetToPasswordOnly(username, realm);
    restore = await patchSelectorConfig(
      { 'allow.skip': 'true', 'grace.period.end': hoursFromNow(96) },
      realm,
    );
    session = await newSession({ locale: 'fr' });
  });

  after(async () => {
    await restore?.();
    await session?.close();
    await resetToPasswordOnly(username, realm);
  });

  it('reaches the enrolment without the login breaking on a read-only account', async () => {
    await signInWithPassword(session.page, username, { realm });
    assert.equal(await currentScreen(session.page), 'select-second-factor');
    assert.equal(await canSkip(session.page), true);

    await chooseFactor(session.page, 'otp');

    assert.equal(await currentScreen(session.page), 'configure-otp');
  });

  it('is still offered the choice again after abandoning', async () => {
    const next = await newSession({ locale: 'fr' });

    try {
      await signInWithPassword(next.page, username, { realm });

      assert.equal(await currentScreen(next.page), 'select-second-factor');
      await skipEnrolment(next.page);
      assert.equal(await currentScreen(next.page), 'signed-in');
    } finally {
      await next.close();
    }
  });
});
