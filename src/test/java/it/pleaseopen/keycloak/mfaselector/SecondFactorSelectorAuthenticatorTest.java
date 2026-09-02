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
import static it.pleaseopen.keycloak.mfaselector.TestFixtures.WEBAUTHN;
import static it.pleaseopen.keycloak.mfaselector.TestFixtures.WEBAUTHN_REGISTER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.events.Details;
import org.keycloak.events.EventBuilder;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.http.HttpRequest;
import org.keycloak.models.AuthenticatorConfigModel;
import org.mockito.ArgumentCaptor;

class SecondFactorSelectorAuthenticatorTest {

    private final SecondFactorSelectorAuthenticator authenticator = new SecondFactorSelectorAuthenticator();

    private TestFixtures fixtures;
    private AuthenticationFlowContext context;
    private LoginFormsProvider form;
    private HttpRequest httpRequest;
    private MultivaluedMap<String, String> formData;
    private Response challenge;

    @BeforeEach
    void setUp() {
        fixtures = new TestFixtures()
                .registrator(CONFIGURE_TOTP, OTP)
                .registrator(WEBAUTHN_REGISTER, WEBAUTHN)
                .storedCredentials(PASSWORD);

        form = mock(LoginFormsProvider.class);
        challenge = mock(Response.class);
        when(form.setAttribute(anyString(), any())).thenReturn(form);
        when(form.setError(anyString())).thenReturn(form);
        when(form.createForm(anyString())).thenReturn(challenge);

        formData = new MultivaluedHashMap<>();
        httpRequest = mock(HttpRequest.class);
        when(httpRequest.getDecodedFormParameters()).thenReturn(formData);

        context = mock(AuthenticationFlowContext.class);
        when(context.getSession()).thenReturn(fixtures.session);
        when(context.getRealm()).thenReturn(fixtures.realm);
        when(context.getUser()).thenReturn(fixtures.user);
        when(context.getAuthenticationSession()).thenReturn(fixtures.authSession);
        when(context.getHttpRequest()).thenReturn(httpRequest);
        when(context.form()).thenReturn(form);
        when(context.getEvent()).thenReturn(mock(EventBuilder.class));
    }

    private void configure(Map<String, String> options) {
        AuthenticatorConfigModel config = new AuthenticatorConfigModel();
        config.setConfig(options);
        when(context.getAuthenticatorConfig()).thenReturn(config);
    }

    private void allowSkip(boolean allowed) {
        configure(Map.of(SecondFactorSelectorAuthenticatorFactory.ALLOW_SKIP, String.valueOf(allowed)));
    }

    private void allowSkipUntil(String gracePeriodEnd) {
        configure(Map.of(
                SecondFactorSelectorAuthenticatorFactory.ALLOW_SKIP, "true",
                SecondFactorSelectorAuthenticatorFactory.GRACE_PERIOD_END, gracePeriodEnd));
    }

    private Object formAttribute(String name) {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(form).setAttribute(eq(name), captor.capture());
        return captor.getValue();
    }

    @Test
    void letsAUserWhoAlreadyHasASecondFactorThrough() {
        fixtures.storedCredentials(PASSWORD, OTP);

        authenticator.authenticate(context);

        verify(context).success();
        verify(context, never()).challenge(any());
    }

    @Test
    void showsTheSelectionScreenToAUserWithoutSecondFactor() {
        authenticator.authenticate(context);

        verify(context).challenge(challenge);
        verify(context, never()).success();
        verify(form).createForm(SecondFactorSelectorAuthenticator.TEMPLATE);

        assertThat(offeredOnScreen())
                .extracting(SecondFactorOption::getRequiredAction)
                .containsExactly(CONFIGURE_TOTP, WEBAUTHN_REGISTER);
    }

    @Test
    void tellsTheScreenWhetherSkippingIsAllowed() {
        allowSkip(true);

        authenticator.authenticate(context);

        verify(form).setAttribute(SecondFactorSelectorAuthenticator.SKIP_ALLOWED_ATTRIBUTE, true);
    }

    @Test
    void doesNotBlockTheLoginWhenNoFactorCanBeEnrolled() {
        // Every offered alias is unknown to the realm: nothing left to propose.
        AuthenticatorConfigModel config = new AuthenticatorConfigModel();
        config.setConfig(Map.of(SecondFactorSelectorAuthenticatorFactory.OFFERED_ACTIONS, "does-not-exist"));
        when(context.getAuthenticatorConfig()).thenReturn(config);

        authenticator.authenticate(context);

        verify(context).success();
        verify(context, never()).challenge(any());
    }

    @Test
    void armsTheRequiredActionMatchingTheUserChoice() {
        formData.putSingle(SecondFactorSelectorAuthenticator.SELECTION_PARAM, CONFIGURE_TOTP);

        authenticator.action(context);

        // Put on the account rather than the login, so it survives an abandoned enrolment.
        assertThat(fixtures.pendingActions()).containsExactly(CONFIGURE_TOTP);
        assertThat(fixtures.remembered()).containsExactly(CONFIGURE_TOTP);
        verify(fixtures.authSession, never()).addRequiredAction(anyString());
        verify(context.getEvent()).detail(Details.CREDENTIAL_TYPE, OTP);
        verify(context).success();
    }

    @Test
    void keepsTheChoiceToTheLoginWhenTheAccountRefusesToRememberIt() {
        fixtures.readOnlyAccount();
        formData.putSingle(SecondFactorSelectorAuthenticator.SELECTION_PARAM, CONFIGURE_TOTP);

        authenticator.action(context);

        // Nothing could record it, so nothing would be able to take it back either.
        verify(fixtures.authSession).addRequiredAction(CONFIGURE_TOTP);
        assertThat(fixtures.pendingActions()).isEmpty();
        verify(context).success();
    }

    @Test
    void takesBackAChoiceMadeInAHurryWhenTheUserPostpones() {
        fixtures.alreadyPicked(CONFIGURE_TOTP);
        allowSkip(true);
        formData.putSingle(SecondFactorSelectorAuthenticator.SKIP_PARAM, "true");

        authenticator.action(context);

        assertThat(fixtures.pendingActions()).isEmpty();
        assertThat(fixtures.remembered()).isEmpty();
        verify(context).success();
    }

    @Test
    void stopsOfferingToPostponeOnceTheGracePeriodIsOver() {
        allowSkipUntil("2020-01-01T00:00:00Z");
        formData.putSingle(SecondFactorSelectorAuthenticator.SKIP_PARAM, "true");

        authenticator.action(context);

        verify(context, never()).success();
        verify(context).challenge(challenge);
    }

    @Test
    void keepsOfferingToPostponeWhileTheGracePeriodRuns() {
        allowSkipUntil("2999-01-01T00:00:00Z");

        authenticator.authenticate(context);

        assertThat(formAttribute(SecondFactorSelectorAuthenticator.SKIP_ALLOWED_ATTRIBUTE)).isEqualTo(true);
    }

    @Test
    void announcesTheDeadlineToTheUser() {
        configure(Map.of(SecondFactorSelectorAuthenticatorFactory.ANNOUNCED_DEADLINE, "2999-10-15T18:00:00+02:00"));

        authenticator.authenticate(context);

        assertThat(formAttribute(SecondFactorSelectorAuthenticator.DEADLINE_ATTRIBUTE))
                .asString().contains("2999");
        assertThat(formAttribute(SecondFactorSelectorAuthenticator.OVERDUE_ATTRIBUTE)).isEqualTo(false);
    }

    @Test
    void saysSoWhenTheAnnouncedDeadlineHasPassed() {
        configure(Map.of(
                SecondFactorSelectorAuthenticatorFactory.ALLOW_SKIP, "true",
                SecondFactorSelectorAuthenticatorFactory.ANNOUNCED_DEADLINE, "2020-10-15T18:00:00+02:00",
                SecondFactorSelectorAuthenticatorFactory.GRACE_PERIOD_END, "2999-11-30T23:59:59+01:00"));

        authenticator.authenticate(context);

        assertThat(formAttribute(SecondFactorSelectorAuthenticator.OVERDUE_ATTRIBUTE)).isEqualTo(true);
        assertThat(formAttribute(SecondFactorSelectorAuthenticator.SKIP_ALLOWED_ATTRIBUTE)).isEqualTo(true);
    }

    @Test
    void announcesNothingWhenNoDateIsConfigured() {
        authenticator.authenticate(context);

        verify(form, never()).setAttribute(eq(SecondFactorSelectorAuthenticator.DEADLINE_ATTRIBUTE), any());
    }

    @Test
    void forgetsAnEnrolmentThatWasCompletedBeforeThisLogin() {
        fixtures.alreadyPicked(CONFIGURE_TOTP);
        fixtures.user.removeRequiredAction(CONFIGURE_TOTP);

        authenticator.authenticate(context);

        assertThat(fixtures.remembered()).isEmpty();
    }

    @Test
    void takesBackAPendingEnrolmentOnceTheUserHasAFactor() {
        fixtures.storedCredentials(PASSWORD, OTP).alreadyPicked(WEBAUTHN_REGISTER);

        authenticator.authenticate(context);

        assertThat(fixtures.pendingActions()).isEmpty();
        verify(context).success();
    }

    @Test
    void redisplaysTheScreenWhenTheSubmittedChoiceIsNotOffered() {
        formData.putSingle(SecondFactorSelectorAuthenticator.SELECTION_PARAM, "UPDATE_PASSWORD");

        authenticator.action(context);

        verify(fixtures.authSession, never()).addRequiredAction(anyString());
        verify(form).setError("selectSecondFactorInvalidSelection");
        verify(context).challenge(challenge);
        verify(context, never()).success();
    }

    @Test
    void redisplaysTheScreenWhenNothingWasSubmitted() {
        authenticator.action(context);

        verify(context).challenge(challenge);
        verify(context, never()).success();
    }

    @Test
    void letsTheUserPostponeWhenSkippingIsAllowed() {
        allowSkip(true);
        formData.putSingle(SecondFactorSelectorAuthenticator.SKIP_PARAM, "true");

        authenticator.action(context);

        verify(fixtures.authSession, never()).addRequiredAction(anyString());
        verify(context).success();
    }

    @Test
    void ignoresASkipThatWasNotAllowed() {
        allowSkip(false);
        formData.putSingle(SecondFactorSelectorAuthenticator.SKIP_PARAM, "true");

        authenticator.action(context);

        verify(context, never()).success();
        verify(context).challenge(challenge);
    }

    @Test
    void requiresAnAuthenticatedUser() {
        assertThat(authenticator.requiresUser()).isTrue();
        assertThat(authenticator.configuredFor(fixtures.session, fixtures.realm, fixtures.user)).isTrue();
    }

    @SuppressWarnings("unchecked")
    private List<SecondFactorOption> offeredOnScreen() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(form).setAttribute(eq(SecondFactorSelectorAuthenticator.OPTIONS_ATTRIBUTE), captor.capture());
        return (List<SecondFactorOption>) captor.getValue();
    }
}
