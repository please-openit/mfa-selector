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
import static it.pleaseopen.keycloak.mfaselector.TestFixtures.UPDATE_PASSWORD;
import static it.pleaseopen.keycloak.mfaselector.TestFixtures.WEBAUTHN_REGISTER;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PendingEnrolmentsTest {

    private TestFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new TestFixtures();
    }

    @Test
    void remembersTheChoiceOnTheAccount() {
        assertThat(PendingEnrolments.remember(fixtures.user, CONFIGURE_TOTP)).isTrue();

        assertThat(fixtures.remembered()).containsExactly(CONFIGURE_TOTP);
    }

    @Test
    void replacesAnEarlierChoiceRatherThanPilingThemUp() {
        fixtures.alreadyPicked(CONFIGURE_TOTP);

        PendingEnrolments.remember(fixtures.user, WEBAUTHN_REGISTER);

        assertThat(fixtures.remembered()).containsExactly(WEBAUTHN_REGISTER);
        assertThat(fixtures.pendingActions()).doesNotContain(CONFIGURE_TOTP);
    }

    @Test
    void takesBackEverythingItArmedWhenReleased() {
        fixtures.alreadyPicked(CONFIGURE_TOTP);

        PendingEnrolments.release(fixtures.user);

        assertThat(fixtures.remembered()).isEmpty();
        assertThat(fixtures.pendingActions()).isEmpty();
    }

    @Test
    void leavesAloneARequiredActionSetBySomeoneElse() {
        fixtures.requiredActionSetElsewhere(UPDATE_PASSWORD).alreadyPicked(CONFIGURE_TOTP);

        PendingEnrolments.release(fixtures.user);

        assertThat(fixtures.pendingActions()).containsExactly(UPDATE_PASSWORD);
    }

    @Test
    void forgetsAnEnrolmentTheAccountNoLongerCarries() {
        // The action ran, so Keycloak took it off the account and the record is stale.
        fixtures.alreadyPicked(CONFIGURE_TOTP);
        fixtures.user.removeRequiredAction(CONFIGURE_TOTP);

        PendingEnrolments.reconcile(fixtures.user);

        assertThat(fixtures.remembered()).isEmpty();
    }

    @Test
    void keepsAnEnrolmentStillAwaited() {
        fixtures.alreadyPicked(CONFIGURE_TOTP);

        PendingEnrolments.reconcile(fixtures.user);

        assertThat(fixtures.remembered()).containsExactly(CONFIGURE_TOTP);
    }

    @Test
    void doesNothingOnAnAccountItNeverTouched() {
        fixtures.requiredActionSetElsewhere(UPDATE_PASSWORD);

        PendingEnrolments.reconcile(fixtures.user);
        PendingEnrolments.release(fixtures.user);

        assertThat(fixtures.pendingActions()).containsExactly(UPDATE_PASSWORD);
    }

    @Test
    void reportsThatAReadOnlyAccountCannotRememberAnything() {
        // A federated account in read-only mode, an AD holding the passwords being the usual case.
        fixtures.readOnlyAccount();

        assertThat(PendingEnrolments.remember(fixtures.user, CONFIGURE_TOTP)).isFalse();
        assertThat(fixtures.remembered()).isEmpty();
    }
}
