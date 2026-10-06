import { randomUUID } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';
import puppeteer from 'puppeteer';

import { BASE_URL, REALM } from './keycloak.mjs';
import { totp } from './totp.mjs';

const CLIENT_ID = 'e2e-app';
// Registered as the client's redirect URI in both realms. Nothing listens on that port: the browser
// answers it itself, see answerCallback.
const REDIRECT_URI = 'http://localhost:3000/callback';
const NAVIGATION = { waitUntil: 'domcontentloaded', timeout: 15000 };

/**
 * The application the user is signing in to, reduced to what the tests need: a page that only
 * exists once Keycloak has redirected back with an authorization code.
 *
 * The redirect is caught inside Chrome through the DevTools Fetch domain and answered there, so the
 * request never reaches the network. A real server on that port would depend on the port being free:
 * when another process holds it, `localhost` may resolve to that process (on ::1, for instance) and
 * the tests land on a page that is not theirs.
 */
async function answerCallback(cdp) {
  const body = Buffer.from('<!doctype html><html><body><h1 id="signed-in">signed in</h1></body></html>')
    .toString('base64');

  cdp.on('Fetch.requestPaused', ({ requestId }) => {
    cdp.send('Fetch.fulfillRequest', {
      requestId,
      responseCode: 200,
      responseHeaders: [{ name: 'content-type', value: 'text/html; charset=utf-8' }],
      body,
    }).catch(() => {});
  });

  await cdp.send('Fetch.enable', { patterns: [{ urlPattern: `${REDIRECT_URI}*`, requestStage: 'Request' }] });
}

/**
 * A browser signing in to that application through Keycloak.
 *
 * @param locale language the browser asks for. Keycloak prefers this hint over the realm default,
 *               so a test wanting to see what a French speaking user sees has to ask for French.
 */
export async function newSession({ locale = 'en' } = {}) {
  const browser = await puppeteer.launch({
    headless: process.env.E2E_HEADFUL !== 'true',
    args: ['--no-sandbox', '--disable-dev-shm-usage'],
  });

  const page = await browser.newPage();
  await page.setViewport({ width: 1024, height: 900 });

  // The WebAuthn registration script asks for a label through window.prompt(). The dialog can be
  // gone already if the page moved on, which is fine.
  page.on('dialog', (dialog) => {
    dialog.accept('end to end authenticator').catch(() => {});
  });

  // A single, long lived DevTools session: detaching one tears down the virtual authenticators of
  // the whole page, so security keys would vanish half way through a scenario.
  const cdp = await page.createCDPSession();

  // Pins the language whatever the machine running the tests is set to, as the browser's own
  // Accept-Language rather than as an extra request header. Extra headers set through DevTools are
  // not carried over a redirect, and Keycloak reaches the OTP setup screen and the account console
  // through one: those pages came in the language of the machine, English on a GitHub runner
  // (reproduced with Chrome 148 set to en-US, 2026-10-06: the account console listed "Signing in"
  // where the scenario expects "Connexion").
  await cdp.send('Network.setUserAgentOverride', { userAgent: await browser.userAgent(), acceptLanguage: locale });
  await answerCallback(cdp);

  return {
    page,
    close: () => browser.close(),
    /**
     * Drops the SSO cookies so the next visit goes through the flow again, while keeping the
     * browser and therefore any security key plugged into it.
     */
    async forgetSession() {
      await cdp.send('Network.clearBrowserCookies');
    },
    /** Plugs a virtual FIDO2 authenticator into the browser, see the Chrome DevTools WebAuthn domain. */
    async useVirtualAuthenticator() {
      await cdp.send('WebAuthn.enable');
      const { authenticatorId } = await cdp.send('WebAuthn.addVirtualAuthenticator', {
        options: {
          protocol: 'ctap2',
          ctap2Version: 'ctap2_1',
          transport: 'internal',
          hasResidentKey: true,
          hasUserVerification: true,
          isUserVerified: true,
          automaticPresenceSimulation: true,
        },
      });

      return {
        authenticatorId,
        credentials: async () => (await cdp.send('WebAuthn.getCredentials', { authenticatorId })).credentials,
      };
    },
  };
}

function authorizationUrl({ locale, realm = REALM, action } = {}) {
  const parameters = new URLSearchParams({
    client_id: CLIENT_ID,
    redirect_uri: REDIRECT_URI,
    response_type: 'code',
    scope: 'openid',
    state: randomUUID(),
    nonce: randomUUID(),
    ...(locale ? { ui_locales: locale } : {}),
    ...(action ? { kc_action: action } : {}),
  });

  return `${BASE_URL}/realms/${realm}/protocol/openid-connect/auth?${parameters}`;
}

/**
 * Types into a field and makes sure the value stuck: the login screens finish initialising after
 * DOMContentLoaded and occasionally reset an input that was filled in too early.
 */
async function fill(page, selector, value) {
  await page.waitForSelector(selector, { visible: true, timeout: NAVIGATION.timeout });
  await page.click(selector, { clickCount: 3 });
  await page.type(selector, value);

  if ((await page.$eval(selector, (input) => input.value)) !== value) {
    await page.$eval(selector, (input, expected) => {
      input.value = expected;
      input.dispatchEvent(new Event('input', { bubbles: true }));
      input.dispatchEvent(new Event('change', { bubbles: true }));
    }, value);
  }
}

async function submit(page, selector, { timeout = NAVIGATION.timeout } = {}) {
  const element = await page.waitForSelector(selector, { visible: true, timeout });
  await submitElement(page, element, selector, { timeout });
}

async function submitElement(page, element, description, { timeout = NAVIGATION.timeout } = {}) {
  // The login screens finish laying out after DOMContentLoaded; clicking through the DOM rather
  // than with the pointer means a late shift cannot move the button out from under the cursor.
  await page.waitForNetworkIdle({ idleTime: 250, timeout: 5000 }).catch(() => {});

  try {
    await Promise.all([
      page.waitForNavigation({ ...NAVIGATION, timeout }),
      element.evaluate((node) => node.click()),
    ]);
  } catch (error) {
    throw new Error(`Submitting '${description}' did not navigate: ${error.message}`
      + `\n  url: ${page.url()}`
      + `\n  screen: ${await currentScreen(page)}`
      + `\n  messages: ${JSON.stringify(await allMessages(page))}`, { cause: error });
  }
}

async function allMessages(page) {
  return page.$$eval('[id^="input-error"], .pf-v5-c-alert__title, .kc-feedback-text, .pf-v5-c-helper-text__item-text',
    (elements) => elements.map((element) => element.textContent.trim()).filter(Boolean));
}

export async function signInWithPassword(page, username, { password = 'password', locale, realm } = {}) {
  await page.goto(authorizationUrl({ locale, realm }), NAVIGATION);
  await submitPassword(page, username, password);
}

/** Fills in the login screen already on display. */
export async function submitPassword(page, username, password = 'password') {
  await fill(page, '#username', username);
  await fill(page, '#password', password);
  await submit(page, '#kc-login');
}

/**
 * Starts an application initiated action, the mechanism the account console uses to let a signed in
 * user add another credential.
 */
export async function startAction(page, action, { realm } = {}) {
  await page.goto(authorizationUrl({ realm, action }), NAVIGATION);
}

/** The screen currently displayed, named after what the user is being asked to do. */
export async function currentScreen(page) {
  const screens = [
    ['#signed-in', 'signed-in'],
    ['#kc-select-second-factor-form', 'select-second-factor'],
    ['#kc-select-credential-form', 'choose-among-my-factors'],
    ['#kc-totp-settings-form', 'configure-otp'],
    ['#register', 'register-webauthn'],
    ['#kc-form-webauthn', 'webauthn-challenge'],
    ['#kc-recovery-codes-settings-form', 'configure-recovery-codes'],
    ['#kc-otp-login-form', 'otp-challenge'],
    ['#kc-form-login', 'login'],
  ];

  for (const [selector, name] of screens) {
    if (await page.$(selector)) {
      return name;
    }
  }

  // The account console is a single page application, recognised by where it lives rather than by
  // a marker that may not have rendered yet.
  return /\/account\/?$/.test(new URL(page.url()).pathname) ? 'account-console' : 'unknown';
}

/** Opens the account console, which is where the demonstration is given. */
export async function openAccountConsole(page, { realm = REALM } = {}) {
  await page.goto(`${BASE_URL}/realms/${realm}/account`, NAVIGATION);
  await page.waitForSelector('#username', { visible: true, timeout: NAVIGATION.timeout }).catch(() => {});
}

/** Sections the account console offers once it has rendered. */
export async function accountConsoleSections(page) {
  await page.waitForSelector('nav a', { visible: true, timeout: 20000 });
  return page.$$eval('nav a', (links) => links.map((link) => link.textContent.trim()));
}

/** Labels shown for each offered factor, in display order. */
export async function offeredLabels(page) {
  return page.$$eval('[id^="kc-second-factor-"] .select-auth-box-headline',
    (elements) => elements.map((element) => element.textContent.trim()));
}

export async function instruction(page) {
  return page.$eval('#kc-select-second-factor-instruction', (element) => element.textContent.trim());
}

export async function offeredFactors(page) {
  return page.$$eval('[id^="kc-second-factor-"]', (elements) => elements
    .filter((element) => element.id !== 'kc-second-factor-skip')
    .map((element) => element.id.replace('kc-second-factor-', '')));
}

export async function chooseFactor(page, credentialType) {
  await submit(page, `#kc-second-factor-${credentialType}`);
}

export async function skipEnrolment(page) {
  await submit(page, '#kc-second-factor-skip');
}

export async function canSkip(page) {
  return (await page.$('#kc-second-factor-skip')) !== null;
}

/** Whether Keycloak is offering a choice between the factors the user holds. */
export async function canChooseAnotherFactor(page) {
  return (await page.$('#try-another-way')) !== null;
}

export async function chooseAnotherFactor(page) {
  await submit(page, '#try-another-way');
}

// Keycloak's own credential selection screen repeats the same form id for every entry, so the
// entries are located through the list item wrapping each of them rather than by id.
const FACTOR_ENTRY = 'form[id="kc-select-credential-form"]';

/** Labels of the factors Keycloak offers on its own credential selection screen. */
export async function myFactors(page) {
  return page.$$eval(FACTOR_ENTRY,
    (forms) => forms.map((form) => form.closest('li')?.querySelector('h2')?.textContent.trim() ?? ''));
}

/** Picks one of them by its label. */
export async function useFactor(page, label) {
  const handle = await page.evaluateHandle((selector, wanted) => [...document.querySelectorAll(selector)]
    .map((form) => form.closest('li'))
    .find((item) => item?.querySelector('h2')?.textContent.trim() === wanted)
    ?.querySelector('[role="button"]') ?? null, FACTOR_ENTRY, label);

  const element = handle.asElement();

  if (!element) {
    throw new Error(`'${label}' is not offered; available: ${JSON.stringify(await myFactors(page))}`);
  }

  await submitElement(page, element, `factor '${label}'`);
}

export async function errorMessage(page) {
  const element = await page.$('.pf-v5-c-alert__title, #input-error, .kc-feedback-text');
  return element ? (await element.evaluate((node) => node.textContent)).trim() : null;
}

const submittedCodes = new Map();

/**
 * A code Keycloak has not seen yet: it accepts a given TOTP code once only, so a scenario enrolling
 * and then signing in within the same 30 second window has to wait for the next one.
 */
async function freshCode(secret, period = 30) {
  if (!submittedCodes.has(secret)) {
    submittedCodes.set(secret, new Set());
  }

  const alreadySubmitted = submittedCodes.get(secret);
  let code = totp(secret, { period });

  if (alreadySubmitted.has(code)) {
    await delay(period * 1000 - (Date.now() % (period * 1000)) + 1000);
    code = totp(secret, { period });
  }

  alreadySubmitted.add(code);
  return code;
}

/** Fills in the OTP setup screen with a valid code and returns the secret that was registered. */
export async function completeOtpEnrolment(page, label = 'end to end authenticator') {
  const secret = await page.$eval('#totpSecret', (input) => input.value);

  await fill(page, '#totp', await freshCode(secret));
  await fill(page, '#userLabel', label);
  await submit(page, '#saveTOTPBtn');

  return secret;
}

export async function answerOtpChallenge(page, secret) {
  await page.waitForSelector('#kc-otp-login-form', { visible: true });
  await fill(page, '#otp', await freshCode(secret));
  await submit(page, '#kc-otp-login-form [type="submit"]');
}

export async function completeWebAuthnEnrolment(page) {
  // The button hands over to the browser's credential creation, which then submits the form.
  await submit(page, '#registerWebAuthn', { timeout: 30000 });
}

/** Answers the WebAuthn challenge; the virtual authenticator signs without any user gesture. */
export async function answerWebAuthnChallenge(page) {
  await submit(page, '#authenticateWebAuthnButton', { timeout: 30000 });
}
