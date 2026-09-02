# Keycloak MFA Selector

A Keycloak authenticator that makes sure every user ends up with a second factor.

Placed after the password form in a browser flow, it checks whether the user already holds a second
factor. If they do, nothing happens and the login carries on. If they only have a password, it shows
a screen listing the factors they may enrol — an authenticator application, a security key, a
passkey, recovery codes, or any second factor a third-party provider adds — and hands over to
Keycloak to run the matching enrolment.

## How it works

Two Keycloak mechanisms carry the whole feature, so this provider stays small.

**Handing over to a required action.** The authenticator does not collect any credential and ships no
enrolment screen of its own: the user goes through Keycloak's stock required actions
(`CONFIGURE_TOTP`, `webauthn-register`, `webauthn-register-passwordless`,
`CONFIGURE_RECOVERY_AUTHN_CODES`, or a third-party one), with their usual screens, validation and
events. Once the user picks a method the authenticator calls
`authenticationSession.addRequiredAction(alias)` and succeeds.
`AuthenticationManager#getApplicableRequiredActionsSorted` merges the required actions carried by the
authentication session with the ones set on the account, so Keycloak runs the enrolment right after
the flow completes, on the same login. No second login, no redirect of our own.

**Discovering the factors.** Keycloak marks every required action able to register a credential with
the `CredentialRegistrator` interface, whose `getCredentialType` says which credential it produces —
this is what the admin API exposes as `credential-registrators`. From that credential type, the
credential provider publishes a `CredentialTypeMetadata` holding a display name, a help text, an icon
class and a category. So:

- a factor can be **offered** if an enabled required action registers a credential whose category is
  `TWO_FACTOR` or `PASSWORDLESS`;
- a user **already has** a second factor if they hold a credential in one of those categories.

Both sides read the same source of truth, which is what keeps the screen from reappearing after a
successful enrolment. A third-party provider implementing `CredentialRegistrator` is picked up with
no change here, and is rendered with its own name, help text and icon.

## Behaviour

| Situation | What happens |
| --- | --- |
| The user holds an OTP, security key, passkey or recovery codes | Nothing is shown, the login carries on |
| The user only has a password | The selection screen is shown |
| The user picks a factor | The matching required action is put on the account and runs immediately after the flow |
| The user postpones, while allowed to | The login carries on and any enrolment this authenticator armed is taken back |
| Nothing can be enrolled (every required action disabled, or none configured) | A warning is logged and the user is let through, rather than facing an empty screen |
| A submitted choice was not on offer | The screen is shown again with an error; no required action is armed |

Only the factors the screen actually proposed are accepted, so a tampered form cannot arm an
arbitrary required action.

## Passwords in an AD or LDAP directory

Supported, and the case the project is tested against: the directory holds passwords and nothing
else, while second factors are registered by Keycloak.

A credential delegated to a user storage provider is not stored by Keycloak, and a password is a
`BASIC_AUTHENTICATION` credential in any case, so it is never mistaken for a second factor. The
factors themselves — OTP, security keys, passkeys, recovery codes — are always written to Keycloak's
own store, including for a federated user, so a directory in `READ_ONLY` edit mode is never written
to.

The stack in [docker/](docker/) runs an OpenLDAP holding two users whose passwords exist only there,
and [e2e/tests/federated-password.test.mjs](e2e/tests/federated-password.test.mjs) checks the whole
path: no credential stored in Keycloak, the selection screen shown all the same, the factor
registered by Keycloak with the directory untouched, and the next login done with the directory
password plus the second factor.

One thing such an account cannot do is carry the bookkeeping behind the grace period, since a
`READ_ONLY` directory refuses attribute writes; see [Forgiving a hasty click](#forgiving-a-hasty-click).

## The demonstration realm

`mfa-flow-demo` carries a browser flow shaped the way it would be deployed, kept apart from
`mfa-demo` so the scenarios above stay untouched. It is served in French and given on the account
console at <http://localhost:8080/realms/mfa-flow-demo/account>.

```
Cookie                                      ALTERNATIVE
forms                                       ALTERNATIVE
├── Username Password Form                  REQUIRED
├── Second Factor Selector                  REQUIRED
└── second factor verification              CONDITIONAL
    ├── Condition - user configured         REQUIRED
    ├── OTP Form                            ALTERNATIVE
    ├── WebAuthn Authenticator              ALTERNATIVE
    └── WebAuthn Passwordless Authenticator ALTERNATIVE
```

Without a second factor, the selector shows its screen, the condition is false so the verification
subflow is skipped, and the required action runs when the flow ends. With one, the selector passes
in silence and the subflow asks for it.

Letting the user pick between the factors they hold needs no code either: Keycloak intersects the
credentials of the account with the alternatives of the subflow, shows the first and offers the rest
behind "Try another way". Registering a third-party factor as one more `ALTERNATIVE` is all it takes
to add it to that choice.

A walkthrough:

1. open the console as `paul` / `password`; the enrolment screen offers three factors
2. enrol one and land on the account console
3. **Connexion** to add a second factor, for instance a security key
4. sign out, sign in again: the flow asks for one factor and offers the other behind
   "Essayer une autre méthode"

Local accounts are `paul` and `nadia`, `olivier` comes from the directory, all with the password
`password`. Recovery codes are deliberately left out of `offered.second.factors` here: they belong
to account recovery rather than to a default second factor.

Two things a realm file has to carry that the admin console does for you, both learned the hard way
here: the LDAP provider's default mappers, since realm import does not run the provider's `onCreate`
hook and without them no directory user resolves; and `default-roles-<realm>` on locally defined
users, which federated users get and local ones do not, and without which the account console
refuses to load.

## Installing

Take the jar from the [latest release](../../releases/latest), or build it:

```bash
mvn package
cp target/mfa-selector-*.jar $KEYCLOAK_HOME/providers/
$KEYCLOAK_HOME/bin/kc.sh build
```

The version says both things you need to know: `1.0.0-kc.26.7.3` is the add-on's own `1.0.0`, built
against Keycloak `26.7.3`. Keycloak 26.7.3 runs on Java 21, which the provider is compiled for.

Then, in the admin console, duplicate the browser flow and add **Second Factor Selector** as
`REQUIRED` right after the username/password form. Bind the flow to the realm as the browser flow.
The demo realm in [docker/realm-mfa-demo.json](docker/realm-mfa-demo.json) shows the whole layout.

Verifying a second factor at each login stays a separate, standard concern: keep the usual
conditional subflows (`Condition - user configured` plus the OTP or WebAuthn authenticator) after
this authenticator, as the demo realm does for OTP.

## Configuration

The options live on the authenticator config in the admin console.

| Option | Default | Meaning |
| --- | --- | --- |
| `offered.second.factors` | empty | Aliases of the required actions to offer, for instance `CONFIGURE_TOTP`, `webauthn-register`, `webauthn-register-passwordless`, `CONFIGURE_RECOVERY_AUTHN_CODES`. Left empty, every enabled required action of the realm able to register a second factor is offered. A provider id is accepted where the alias differs. |
| `allow.skip` | `false` | Adds a button letting the user finish the login without enrolling. The screen comes back on the next login. |
| `announced.deadline` | empty | Date and time the screen announces as the deadline, ISO 8601 with an offset. Announced only. |
| `grace.period.end` | empty | Date and time past which postponing stops being offered, same format. Left empty, `allow.skip` applies without a time limit. |

A required action that is disabled in the realm is never offered, whether it is listed or not.

### Postponing within a time window

`allow.skip` opens the door, the two dates decide how long it stays open:

- before the announced deadline, the screen reads "set one up before *date*" and carries the button
- past it while the grace period lasts, the same screen says the deadline has gone by, and still
  carries the button
- once the grace period is over the button is gone, and enrolling is the only way through

A grace period ending no later than the announced deadline means no grace at all, and it is then the
end of the grace period that gets announced, so the screen never promises time the server will not
give. Dates are read as ISO 8601 with an offset, `2026-10-15T18:00:00+02:00`, and shown in the
user's language and the server's time zone. One that cannot be read is treated as a grace period
already over: a typo must not hand out unlimited postponement.

### Forgiving a hasty click

Picking a factor puts the matching required action on the account rather than on the login alone, so
an abandoned enrolment is still waiting at the next login, and fires even when the user arrives on a
cookie without going through this flow at all.

That would trap whoever clicked the wrong factor, so postponing takes the pending enrolment back. It
takes back only what this authenticator armed, recorded in the `mfa-selector.pending-enrolments`
attribute: a required action an administrator set is never touched, even when it happens to be the
same one. A new choice replaces the earlier one rather than adding to it, so hesitating never ends
up demanding two enrolments, and the record is tidied at the next login once the action has left the
account, having been run.

**Accounts in a read-only directory carry no record.** Keycloak refuses every attribute write on
them but `locale`, and an AD holding the passwords in `READ_ONLY` edit mode is exactly that case.
The choice then lasts for the login at hand only, as it did before this option existed: the user is
never stuck, but an abandoned enrolment is forgotten and none fires on a cookie login. Setting the
LDAP provider's edit mode to `UNSYNCED` with import enabled restores the full behaviour, Keycloak
keeping its own data locally while still never writing to the directory.

### Adding a custom second factor

Nothing to configure here. A provider shipping a `RequiredActionProvider` that implements
`CredentialRegistrator`, together with a `CredentialProvider` whose metadata category is
`TWO_FACTOR` or `PASSWORDLESS`, shows up on the screen on its own. List its alias in
`offered.second.factors` if you want to restrict the offer to a subset.

### Wording

The screen reuses the names and help texts Keycloak already publishes for each credential type, but
prefers an enrolment wording when this provider ships one, because Keycloak's own texts are written
for signing in. The keys, overridable per realm in **Realm settings → Localization**:

```
selectSecondFactorTitle, selectSecondFactorInstruction, selectSecondFactorSkip,
selectSecondFactorInvalidSelection
selectSecondFactorName-<credential type>, selectSecondFactorHelp-<credential type>
```

English and French are shipped. A credential type without a `selectSecondFactor*-<type>` entry falls
back to the display name and help text of its credential provider, so custom factors read correctly
without touching this project.

### The admin console in French

The configuration screen of the authenticator is translated. Its labels and help texts are message
keys rather than literal English, and the admin console resolves them against the very bundle this
provider ships, so the screen reads in French for an administrator whose console is in French and in
English otherwise:

```
secondFactorSelector.offeredActions.label / .help
secondFactorSelector.allowSkip.label / .help
```

The console follows the language of the administrator's account, in the realm they sign in to. To
put it in French, enable internationalization on that realm — usually `master` — with `fr` among the
supported locales, then set French as the language of the administrator's account. The development
stack keeps `master` in memory, so the end to end test arranges this itself rather than assuming it.

Two things to know before editing these values. The admin console has no `MessageFormat` behind it,
so apostrophes here must **not** be doubled, unlike the login keys above; a unit test enforces both
that rule and the presence of every key in both languages. And the name and description of the
authenticator in the flow editor cannot be translated at all: the admin console prints what the
server sends without passing it through its translation function, which is why Keycloak's own
authenticators keep English names in a French console. Ours stays `Second Factor Selector` to match
them.

## Releasing

Releases are cut by tagging, and the tag is the trigger:

```bash
git tag v1.0.0-kc.26.7.3
git push origin v1.0.0-kc.26.7.3
```

[The release workflow](.github/workflows/release.yml) then refuses to go on unless the tag is of the
form `v<add-on version>-kc.<keycloak version>` and says the same as the pom, both for the add-on
version and for the Keycloak the build actually compiles against. A tag can therefore never claim a
compatibility the artefact does not have. It runs the unit tests, brings up Keycloak and the
directory and runs the whole end to end suite against them, and only then publishes the release with
the jar and its SHA-256.

So releasing a fix against a newer Keycloak means bumping `keycloak.version` and `version` in the
pom together, then tagging to match. [The build workflow](.github/workflows/build.yml) runs the unit
tests on every push and pull request.

## Layout

```
.github/workflows/                               unit tests on push, release on tag
src/main/java/it/pleaseopen/keycloak/mfaselector/
  SecondFactorSelectorAuthenticator.java         the flow step
  SecondFactorSelectorAuthenticatorFactory.java  registration and configuration
  SecondFactors.java                             discovery of held and offerable factors
  SecondFactorOption.java                        one choice on the screen
  EnrolmentSchedule.java                         the window during which postponing is offered
  PendingEnrolments.java                         what this authenticator armed, so it can take it back
src/main/resources/theme-resources/              the screen and its translations
docker/                                          Keycloak 26.7.3, an OpenLDAP and the demo realms
e2e/                                             Puppeteer tests driving a real browser
```


## Tests

Unit tests cover the discovery rules and the authenticator's decisions:

```bash
mvn test
```

End to end tests drive a real browser against a real Keycloak. Start the server first — the image
builds the provider and imports the demo realm:

```bash
docker compose -f docker/docker-compose.yml up -d --build --wait
cd e2e && npm install && npm test
```

They cover: the four factors being offered in category order, the handover to the OTP and WebAuthn
required actions, the credential actually being registered, the screen not coming back afterwards,
the OTP challenge on the next login, postponing when `allow.skip` is on and being refused when it is
not, a realm with nothing to offer letting users through, a tampered selection being rejected, and
the French translation, and a directory user going through the same enrolment with only a
federated password, and the time window for postponing: the deadline announced then said to have
passed, the button gone once the grace period is over, a forged postponement refused all the same, a
factor picked in a hurry taken back on the next login, and a read-only directory account going
through it all without the login breaking. The demonstration realm has its own scenarios: the three factors offered in
French, the handover, the challenge on the next login, adding a second factor the way the account
console does, choosing between the two, and the account console itself loading afterwards. A last one signs in to
the admin console in French and opens the authenticator's configuration dialog, checking it reads in
French with no message key left showing.

WebAuthn runs against a Chrome virtual authenticator, so no hardware is involved. Set
`E2E_HEADFUL=true` to watch the browser.

The stack exposes `mfa-demo` and `mfa-flow-demo` on <http://localhost:8080>, with an admin at
`admin`/`admin`. `mfa-demo` has the local users `alice`, `bob`, `carol`, `dave`, `erin` and `frank`
and the directory users `grace` and `heidi`; `mfa-flow-demo` has `paul`, `nadia` and the directory
user `olivier`. All use the password `password`. Tests reset the users they touch, so the suite can
be replayed without restarting the server.

## Licence

[Apache License 2.0](LICENSE), the licence Keycloak itself uses.
