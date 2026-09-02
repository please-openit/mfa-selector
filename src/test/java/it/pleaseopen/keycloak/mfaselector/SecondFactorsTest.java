/*
 * Copyright 2026 Please Open It
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package it.pleaseopen.keycloak.mfaselector;

import static it.pleaseopen.keycloak.mfaselector.TestFixtures.CONFIGURE_TOTP;
import static it.pleaseopen.keycloak.mfaselector.TestFixtures.OTP;
import static it.pleaseopen.keycloak.mfaselector.TestFixtures.PASSWORD;
import static it.pleaseopen.keycloak.mfaselector.TestFixtures.UPDATE_PASSWORD;
import static it.pleaseopen.keycloak.mfaselector.TestFixtures.WEBAUTHN;
import static it.pleaseopen.keycloak.mfaselector.TestFixtures.WEBAUTHN_PASSWORDLESS;
import static it.pleaseopen.keycloak.mfaselector.TestFixtures.WEBAUTHN_REGISTER;
import static it.pleaseopen.keycloak.mfaselector.TestFixtures.WEBAUTHN_REGISTER_PASSWORDLESS;
import static it.pleaseopen.keycloak.mfaselector.TestFixtures.aliases;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class SecondFactorsTest {

    private TestFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new TestFixtures()
                .registrator(CONFIGURE_TOTP, OTP)
                .registrator(WEBAUTHN_REGISTER, WEBAUTHN)
                .registrator(WEBAUTHN_REGISTER_PASSWORDLESS, WEBAUTHN_PASSWORDLESS)
                .plainRequiredAction(UPDATE_PASSWORD);
    }

    @Nested
    @DisplayName("already enrolled")
    class AlreadyEnrolled {

        @Test
        void isFalseWhenTheUserHasNoCredentialAtAll() {
            fixtures.storedCredentials();

            assertThat(fixtures.alreadyEnrolled()).isFalse();
        }

        @Test
        void isFalseWhenTheUserOnlyHasAPassword() {
            fixtures.storedCredentials(PASSWORD);

            assertThat(fixtures.alreadyEnrolled()).isFalse();
        }

        @Test
        void isTrueWhenTheUserHasATwoFactorCredential() {
            fixtures.storedCredentials(PASSWORD, OTP);

            assertThat(fixtures.alreadyEnrolled()).isTrue();
        }

        @Test
        void isTrueWhenTheUserHasAPasswordlessCredential() {
            fixtures.storedCredentials(PASSWORD, WEBAUTHN_PASSWORDLESS);

            assertThat(fixtures.alreadyEnrolled()).isTrue();
        }

        @Test
        void ignoresCredentialTypesNoProviderKnowsAbout() {
            fixtures.storedCredentials(PASSWORD, "some-orphan-credential");

            assertThat(fixtures.alreadyEnrolled()).isFalse();
        }
    }

    @Nested
    @DisplayName("offered options")
    class Offered {

        @Test
        void areDiscoveredFromTheRealmWhenNoneAreConfigured() {
            assertThat(aliases(fixtures.offered()))
                    .containsExactlyInAnyOrder(CONFIGURE_TOTP, WEBAUTHN_REGISTER, WEBAUTHN_REGISTER_PASSWORDLESS);
        }

        @Test
        void excludeRequiredActionsThatRegisterNoCredential() {
            assertThat(aliases(fixtures.offered())).doesNotContain(UPDATE_PASSWORD);
        }

        @Test
        void areLimitedToTheConfiguredOnes() {
            assertThat(aliases(fixtures.offered(CONFIGURE_TOTP))).containsExactly(CONFIGURE_TOTP);
        }

        @Test
        void keepTwoFactorBeforePasswordless() {
            assertThat(aliases(fixtures.offered()))
                    .containsExactly(CONFIGURE_TOTP, WEBAUTHN_REGISTER, WEBAUTHN_REGISTER_PASSWORDLESS);
        }

        @Test
        void excludeDisabledRequiredActions() {
            fixtures.registrator(CONFIGURE_TOTP, CONFIGURE_TOTP, OTP, false);

            assertThat(aliases(fixtures.offered())).doesNotContain(CONFIGURE_TOTP);
            assertThat(aliases(fixtures.offered(CONFIGURE_TOTP))).isEmpty();
        }

        @Test
        void ignoreUnknownAliases() {
            assertThat(aliases(fixtures.offered("does-not-exist", CONFIGURE_TOTP))).containsExactly(CONFIGURE_TOTP);
        }

        @Test
        void acceptAProviderIdConfiguredInsteadOfTheAlias() {
            fixtures.registrator("custom-alias", "custom-provider-id", OTP, true);

            assertThat(aliases(fixtures.offered("custom-provider-id"))).containsExactly("custom-alias");
        }

        @Test
        void areDeduplicated() {
            assertThat(aliases(fixtures.offered(CONFIGURE_TOTP, CONFIGURE_TOTP))).containsExactly(CONFIGURE_TOTP);
        }

        @Test
        void excludeFactorsThatWouldNotSatisfyTheRequirement() {
            // A registrator for a basic-authentication credential would leave the user prompted forever.
            fixtures.registrator("register-password", PASSWORD);

            assertThat(aliases(fixtures.offered())).doesNotContain("register-password");
            assertThat(aliases(fixtures.offered("register-password"))).isEmpty();
        }

        @Test
        void excludeCredentialTypesNoProviderKnowsAbout() {
            fixtures.registrator("register-unknown", "unknown-credential-type");

            assertThat(aliases(fixtures.offered())).doesNotContain("register-unknown");
        }

        @Test
        void carryTheCredentialMetadataKeycloakPublishes() {
            List<SecondFactorOption> options = fixtures.offered(CONFIGURE_TOTP);

            assertThat(options).singleElement().satisfies(option -> {
                assertThat(option.getRequiredAction()).isEqualTo(CONFIGURE_TOTP);
                assertThat(option.getCredentialType()).isEqualTo(OTP);
                assertThat(option.getDisplayName()).isEqualTo("otp-display-name");
                assertThat(option.getHelpText()).isEqualTo("otp-help-text");
                assertThat(option.getIconCssClass()).isEqualTo("kcAuthenticatorotpClass");
            });
        }
    }
}
