/** Thin wrapper over the Keycloak admin REST API, used to arrange and reset test state. */

export const BASE_URL = process.env.KEYCLOAK_URL ?? 'http://localhost:8080';

/** Realm exercised by default; every helper takes another one as its last argument. */
export const REALM = process.env.KEYCLOAK_REALM ?? 'mfa-demo';

/** Realm carrying the production shaped flow used for the live demonstration. */
export const FLOW_REALM = 'mfa-flow-demo';

const ADMIN_USER = process.env.KEYCLOAK_ADMIN ?? 'admin';
const ADMIN_PASSWORD = process.env.KEYCLOAK_ADMIN_PASSWORD ?? 'admin';

const SELECTOR_PROVIDER_ID = 'second-factor-selector';

let cachedToken;

async function accessToken() {
  if (cachedToken && cachedToken.expiresAt > Date.now()) {
    return cachedToken.value;
  }

  const response = await fetch(`${BASE_URL}/realms/master/protocol/openid-connect/token`, {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      client_id: 'admin-cli',
      grant_type: 'password',
      username: ADMIN_USER,
      password: ADMIN_PASSWORD,
    }),
  });

  if (!response.ok) {
    throw new Error(`Cannot authenticate against Keycloak (${response.status}). Is docker compose up?`);
  }

  const body = await response.json();
  cachedToken = { value: body.access_token, expiresAt: Date.now() + (body.expires_in - 10) * 1000 };
  return cachedToken.value;
}

export async function admin(path, { method = 'GET', body, realm = REALM } = {}) {
  const response = await fetch(`${BASE_URL}/admin/realms/${realm}${path}`, {
    method,
    headers: {
      authorization: `Bearer ${await accessToken()}`,
      ...(body === undefined ? {} : { 'content-type': 'application/json' }),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });

  if (!response.ok) {
    throw new Error(`${method} ${realm}${path} failed with ${response.status}: ${await response.text()}`);
  }

  return response.status === 204 || response.headers.get('content-length') === '0'
    ? undefined
    : response.json().catch(() => undefined);
}

export async function waitUntilReady({ attempts = 60, delayMs = 2000, realm = REALM } = {}) {
  for (let attempt = 1; attempt <= attempts; attempt += 1) {
    try {
      await admin('', { realm });
      return;
    } catch (error) {
      if (attempt === attempts) {
        throw error;
      }
      await new Promise((resolve) => setTimeout(resolve, delayMs));
    }
  }
}

async function userId(username, realm = REALM) {
  const [user] = await admin(`/users?username=${encodeURIComponent(username)}&exact=true`, { realm });

  if (!user) {
    throw new Error(`User '${username}' does not exist in realm '${realm}'`);
  }

  return user.id;
}

/** Required actions the account is carrying, whoever set them. */
export async function requiredActionsOf(username, realm = REALM) {
  const user = await admin(`/users/${await userId(username, realm)}`, { realm });
  return [...(user.requiredActions ?? [])].sort();
}

/** Attributes Keycloak reports for the account, which excludes unmanaged ones on some profiles. */
export async function attributesOf(username, realm = REALM) {
  const user = await admin(`/users/${await userId(username, realm)}`, { realm });
  return user.attributes ?? {};
}

/** Whether the user comes from a user storage provider such as an AD/LDAP directory. */
export async function isFederated(username, realm = REALM) {
  const user = await admin(`/users/${await userId(username, realm)}`, { realm });
  return Boolean(user.federationLink);
}

/**
 * Credential types Keycloak holds for the user. A credential provided by the directory rather than
 * stored by Keycloak shows up without an id, and is reported separately by federatedCredentialTypes.
 */
export async function credentialTypes(username, realm = REALM) {
  const credentials = await admin(`/users/${await userId(username, realm)}/credentials`, { realm });
  return credentials.filter((credential) => credential.id).map((credential) => credential.type).sort();
}

/** Credential types the user has in a user storage provider instead of in Keycloak. */
export async function federatedCredentialTypes(username, realm = REALM) {
  const credentials = await admin(`/users/${await userId(username, realm)}/credentials`, { realm });
  return credentials.filter((credential) => !credential.id).map((credential) => credential.type).sort();
}

/** Brings a user back to "password only" so a scenario can be replayed. */
export async function resetToPasswordOnly(username, realm = REALM) {
  const id = await userId(username, realm);
  const credentials = await admin(`/users/${id}/credentials`, { realm });

  for (const credential of credentials.filter((candidate) => candidate.type !== 'password')) {
    await admin(`/users/${id}/credentials/${credential.id}`, { method: 'DELETE', realm });
  }

  const user = await admin(`/users/${id}`, { realm });
  if (user.requiredActions?.length) {
    await admin(`/users/${id}`, { method: 'PUT', body: { ...user, requiredActions: [] }, realm });
  }
}

export async function requiredAction(alias, realm = REALM) {
  return admin(`/authentication/required-actions/${encodeURIComponent(alias)}`, { realm });
}

export async function setRequiredActionEnabled(alias, enabled, realm = REALM) {
  const action = await requiredAction(alias, realm);
  await admin(`/authentication/required-actions/${encodeURIComponent(alias)}`, {
    method: 'PUT',
    body: { ...action, enabled },
    realm,
  });
}

/** Finds the selector's configuration through whichever browser flow the realm is bound to. */
async function selectorConfigId(realm = REALM) {
  const { browserFlow } = await admin('', { realm });
  const executions = await admin(
    `/authentication/flows/${encodeURIComponent(browserFlow)}/executions`, { realm });
  const execution = executions.find((candidate) => candidate.providerId === SELECTOR_PROVIDER_ID);

  if (!execution?.authenticationConfig) {
    throw new Error(`No configuration attached to the '${SELECTOR_PROVIDER_ID}' execution of '${browserFlow}'`);
  }

  return execution.authenticationConfig;
}

export async function selectorConfig(realm = REALM) {
  return admin(`/authentication/config/${await selectorConfigId(realm)}`, { realm });
}

/** Updates the authenticator configuration and returns a function restoring the previous one. */
export async function patchSelectorConfig(patch, realm = REALM) {
  const id = await selectorConfigId(realm);
  const before = await admin(`/authentication/config/${id}`, { realm });

  await admin(`/authentication/config/${id}`, {
    method: 'PUT',
    body: { ...before, config: { ...before.config, ...patch } },
    realm,
  });

  return async () => {
    await admin(`/authentication/config/${id}`, { method: 'PUT', body: before, realm });
  };
}
