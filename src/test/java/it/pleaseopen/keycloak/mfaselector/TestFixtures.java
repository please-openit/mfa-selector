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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.keycloak.authentication.CredentialRegistrator;
import org.keycloak.authentication.RequiredActionContext;
import org.keycloak.authentication.RequiredActionProvider;
import org.keycloak.credential.CredentialModel;
import org.keycloak.credential.CredentialProvider;
import org.keycloak.credential.CredentialProviderFactory;
import org.keycloak.credential.CredentialTypeMetadata;
import org.keycloak.credential.CredentialTypeMetadata.Category;
import org.keycloak.credential.CredentialTypeMetadataContext;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RequiredActionProviderModel;
import org.keycloak.models.SubjectCredentialManager;
import org.keycloak.models.UserModel;
import org.keycloak.provider.ProviderFactory;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.keycloak.storage.ReadOnlyException;

/**
 * A miniature realm: real credential providers and required actions (Keycloak inspects their
 * generic types, so mocks would not do) wired into a mocked session.
 */
final class TestFixtures {

    static final String OTP = "otp";
    static final String WEBAUTHN = "webauthn";
    static final String WEBAUTHN_PASSWORDLESS = "webauthn-passwordless";
    static final String PASSWORD = "password";

    static final String CONFIGURE_TOTP = "CONFIGURE_TOTP";
    static final String WEBAUTHN_REGISTER = "webauthn-register";
    static final String WEBAUTHN_REGISTER_PASSWORDLESS = "webauthn-register-passwordless";
    static final String UPDATE_PASSWORD = "UPDATE_PASSWORD";

    final KeycloakSession session = mock(KeycloakSession.class);
    final KeycloakContext context = mock(KeycloakContext.class);
    final KeycloakSessionFactory sessionFactory = mock(KeycloakSessionFactory.class);
    final RealmModel realm = mock(RealmModel.class);
    final UserModel user = mock(UserModel.class);
    final SubjectCredentialManager credentialManager = mock(SubjectCredentialManager.class);
    final AuthenticationSessionModel authSession = mock(AuthenticationSessionModel.class);

    private final Map<String, RequiredActionProviderModel> requiredActions = new LinkedHashMap<>();
    private final List<ProviderFactory> credentialFactories = new ArrayList<>();

    /** Just enough of an account to exercise what the authenticator writes on it. */
    private final Map<String, List<String>> attributes = new LinkedHashMap<>();
    private final Set<String> pendingActions = new LinkedHashSet<>();
    private boolean readOnly;

    TestFixtures() {
        when(realm.getName()).thenReturn("test");
        when(user.getUsername()).thenReturn("alice");
        when(user.credentialManager()).thenReturn(credentialManager);
        when(session.getContext()).thenReturn(context);
        when(context.getRealm()).thenReturn(realm);
        when(context.resolveLocale(any())).thenReturn(Locale.ENGLISH);

        when(user.getAttributeStream(any()))
                .thenAnswer(invocation -> attributes.getOrDefault(invocation.getArgument(0, String.class), List.of()).stream());
        doAnswer(invocation -> {
            refuseWhenReadOnly();
            attributes.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(user).setAttribute(anyString(), any());
        doAnswer(invocation -> {
            if (attributes.containsKey(invocation.getArgument(0, String.class))) {
                refuseWhenReadOnly();
            }
            attributes.remove(invocation.getArgument(0, String.class));
            return null;
        }).when(user).removeAttribute(anyString());

        when(user.getRequiredActionsStream()).thenAnswer(invocation -> Set.copyOf(pendingActions).stream());
        doAnswer(invocation -> pendingActions.add(invocation.getArgument(0))).when(user).addRequiredAction(anyString());
        doAnswer(invocation -> pendingActions.remove(invocation.getArgument(0))).when(user).removeRequiredAction(anyString());

        storedCredentials();
        when(session.getKeycloakSessionFactory()).thenReturn(sessionFactory);
        when(sessionFactory.getProviderFactoriesStream(CredentialProvider.class))
                .thenAnswer(invocation -> credentialFactories.stream());
        when(realm.getRequiredActionProvidersStream()).thenAnswer(invocation -> List.copyOf(requiredActions.values()).stream());
        when(realm.getRequiredActionProviderByAlias(any())).thenAnswer(invocation -> requiredActions.get(invocation.getArgument(0, String.class)));

        // The credential types Keycloak ships, with the categories its providers declare.
        credentialProvider(PASSWORD, Category.BASIC_AUTHENTICATION);
        credentialProvider(OTP, Category.TWO_FACTOR);
        credentialProvider(WEBAUTHN, Category.TWO_FACTOR);
        credentialProvider(WEBAUTHN_PASSWORDLESS, Category.PASSWORDLESS);
    }

    private void refuseWhenReadOnly() {
        if (readOnly) {
            throw new ReadOnlyException("Federated storage is not writable");
        }
    }

    /** Makes the account refuse attribute writes, as a read-only federated one does. */
    TestFixtures readOnlyAccount() {
        readOnly = true;
        return this;
    }

    /** Required actions the account carries, whoever set them. */
    Set<String> pendingActions() {
        return Set.copyOf(pendingActions);
    }

    /** Required actions this authenticator recorded as its own. */
    List<String> remembered() {
        return List.copyOf(attributes.getOrDefault(PendingEnrolments.ATTRIBUTE, List.of()));
    }

    /** Puts the account in the state left by an earlier, abandoned choice. */
    TestFixtures alreadyPicked(String alias) {
        pendingActions.add(alias);
        attributes.put(PendingEnrolments.ATTRIBUTE, List.of(alias));
        return this;
    }

    /** A required action set by someone else, which this authenticator must never take back. */
    TestFixtures requiredActionSetElsewhere(String alias) {
        pendingActions.add(alias);
        return this;
    }

    /** Declares which credentials the user currently holds. */
    TestFixtures storedCredentials(String... types) {
        when(credentialManager.getStoredCredentialsStream()).thenAnswer(invocation -> Arrays.stream(types).map(type -> {
            CredentialModel model = new CredentialModel();
            model.setType(type);
            return model;
        }));
        return this;
    }

    /** Registers an enabled required action able to register the given credential type. */
    TestFixtures registrator(String alias, String credentialType) {
        return registrator(alias, alias, credentialType, true);
    }

    TestFixtures registrator(String alias, String providerId, String credentialType, boolean enabled) {
        requiredAction(alias, providerId, enabled);
        when(session.getProvider(RequiredActionProvider.class, providerId))
                .thenReturn(new TestRegistrator(credentialType));
        return this;
    }

    /** Registers an enabled required action that does not register any credential. */
    TestFixtures plainRequiredAction(String alias) {
        requiredAction(alias, alias, true);
        when(session.getProvider(RequiredActionProvider.class, alias)).thenReturn(mock(RequiredActionProvider.class));
        return this;
    }

    private void requiredAction(String alias, String providerId, boolean enabled) {
        RequiredActionProviderModel model = new RequiredActionProviderModel();
        model.setAlias(alias);
        model.setProviderId(providerId);
        model.setName(alias);
        model.setEnabled(enabled);
        requiredActions.put(alias, model);
    }

    private void credentialProvider(String type, Category category) {
        TestCredentialProvider provider = new TestCredentialProvider(session, type, category);
        credentialFactories.add(new TestCredentialProviderFactory(provider));
        when(session.getProvider(CredentialProvider.class, type)).thenReturn(provider);
    }

    List<SecondFactorOption> offered(String... configuredAliases) {
        return SecondFactors.offered(session, realm, user, authSession, List.of(configuredAliases));
    }

    boolean alreadyEnrolled() {
        return SecondFactors.alreadyEnrolled(session, user);
    }

    static List<String> aliases(List<SecondFactorOption> options) {
        return options.stream().map(SecondFactorOption::getRequiredAction).toList();
    }

    static final class TestCredentialProvider implements CredentialProvider<CredentialModel> {

        private final KeycloakSession session;
        private final String type;
        private final Category category;

        TestCredentialProvider(KeycloakSession session, String type, Category category) {
            this.session = session;
            this.type = type;
            this.category = category;
        }

        @Override
        public String getType() {
            return type;
        }

        @Override
        public CredentialModel createCredential(RealmModel realm, UserModel user, CredentialModel credentialModel) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean deleteCredential(RealmModel realm, UserModel user, String credentialId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CredentialModel getCredentialFromModel(CredentialModel model) {
            return model;
        }

        @Override
        public CredentialTypeMetadata getCredentialTypeMetadata(CredentialTypeMetadataContext context) {
            return CredentialTypeMetadata.builder()
                    .type(type)
                    .category(category)
                    .displayName(type + "-display-name")
                    .helpText(type + "-help-text")
                    .iconCssClass("kcAuthenticator" + type + "Class")
                    .removeable(true)
                    .build(session);
        }
    }

    static final class TestCredentialProviderFactory implements CredentialProviderFactory<TestCredentialProvider> {

        private final TestCredentialProvider provider;

        TestCredentialProviderFactory(TestCredentialProvider provider) {
            this.provider = provider;
        }

        @Override
        public String getId() {
            return provider.getType();
        }

        @Override
        public CredentialProvider create(KeycloakSession session) {
            return provider;
        }
    }

    static final class TestRegistrator implements RequiredActionProvider, CredentialRegistrator {

        private final String credentialType;

        TestRegistrator(String credentialType) {
            this.credentialType = credentialType;
        }

        @Override
        public String getCredentialType(KeycloakSession session, AuthenticationSessionModel authSession) {
            return credentialType;
        }

        @Override
        public void evaluateTriggers(RequiredActionContext context) {
        }

        @Override
        public void requiredActionChallenge(RequiredActionContext context) {
        }

        @Override
        public void processAction(RequiredActionContext context) {
        }

        @Override
        public void close() {
        }
    }
}
