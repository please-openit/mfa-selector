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

import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticatorUtil;
import org.keycloak.authentication.CredentialRegistrator;
import org.keycloak.authentication.RequiredActionProvider;
import org.keycloak.credential.CredentialModel;
import org.keycloak.credential.CredentialProvider;
import org.keycloak.credential.CredentialTypeMetadata;
import org.keycloak.credential.CredentialTypeMetadata.Category;
import org.keycloak.credential.CredentialTypeMetadataContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RequiredActionProviderModel;
import org.keycloak.models.UserModel;
import org.keycloak.sessions.AuthenticationSessionModel;

/**
 * Discovery of the second factors a user holds and of the ones they may enrol.
 *
 * <p>Nothing here is specific to OTP or WebAuthn. A second factor is any credential whose provider
 * declares itself as {@link Category#TWO_FACTOR} or {@link Category#PASSWORDLESS}, and an enrolment
 * option is any enabled required action implementing {@link CredentialRegistrator} that registers
 * such a credential. Third-party factors are therefore picked up with no code change here.
 */
final class SecondFactors {

    private static final Logger logger = Logger.getLogger(SecondFactors.class);

    private static final Set<Category> SECOND_FACTOR_CATEGORIES = EnumSet.of(Category.TWO_FACTOR, Category.PASSWORDLESS);

    /** Same ordering Keycloak uses for credentials: two-factor before passwordless, then by type. */
    private static final Comparator<SecondFactorOption> DISPLAY_ORDER =
            Comparator.comparing(SecondFactorOption::getCategory, Comparator.nullsLast(Category::compareWith))
                    .thenComparing(SecondFactorOption::getCredentialType);

    private SecondFactors() {
    }

    /**
     * Whether the user already holds a credential acting as a second factor, in which case the
     * selection screen must not be shown.
     */
    static boolean alreadyEnrolled(KeycloakSession session, UserModel user) {
        Set<String> storedTypes = user.credentialManager().getStoredCredentialsStream()
                .map(CredentialModel::getType)
                .collect(Collectors.toSet());

        if (storedTypes.isEmpty()) {
            return false;
        }

        return AuthenticatorUtil.getCredentialProviders(session)
                .filter(provider -> storedTypes.contains(provider.getType()))
                .anyMatch(provider -> isSecondFactor(metadata(session, user, provider)));
    }

    /**
     * Enrolment options to offer.
     *
     * @param configuredAliases required action aliases picked by the administrator; when empty every
     *                          enabled required action of the realm able to register a second factor
     *                          is offered.
     */
    static List<SecondFactorOption> offered(KeycloakSession session, RealmModel realm, UserModel user,
                                            AuthenticationSessionModel authSession, List<String> configuredAliases) {
        Stream<RequiredActionProviderModel> candidates = configuredAliases.isEmpty()
                ? realm.getRequiredActionProvidersStream()
                : new LinkedHashSet<>(configuredAliases).stream()
                        .map(alias -> resolve(realm, alias))
                        .filter(Objects::nonNull);

        return candidates
                .filter(RequiredActionProviderModel::isEnabled)
                .map(model -> toOption(session, user, authSession, model))
                .filter(Objects::nonNull)
                .sorted(DISPLAY_ORDER)
                .collect(Collectors.toList());
    }

    /** Looks up a required action by alias, tolerating a provider id being configured instead. */
    private static RequiredActionProviderModel resolve(RealmModel realm, String alias) {
        RequiredActionProviderModel model = realm.getRequiredActionProviderByAlias(alias);

        if (model == null) {
            model = realm.getRequiredActionProvidersStream()
                    .filter(candidate -> alias.equals(candidate.getProviderId()))
                    .findFirst()
                    .orElse(null);
        }

        if (model == null) {
            logger.warnf("Required action '%s' is not registered in realm '%s', ignoring it.", alias, realm.getName());
        }

        return model;
    }

    private static SecondFactorOption toOption(KeycloakSession session, UserModel user,
                                               AuthenticationSessionModel authSession, RequiredActionProviderModel model) {
        RequiredActionProvider provider = session.getProvider(RequiredActionProvider.class, model.getProviderId());
        if (!(provider instanceof CredentialRegistrator registrator)) {
            return null;
        }

        String credentialType = registrator.getCredentialType(session, authSession);
        if (credentialType == null) {
            return null;
        }

        CredentialTypeMetadata metadata = AuthenticatorUtil.getCredentialProviders(session)
                .filter(candidate -> candidate.supportsCredentialType(credentialType))
                .findFirst()
                .map(candidate -> metadata(session, user, candidate))
                .orElse(null);

        if (metadata == null) {
            logger.warnf("No credential provider found for type '%s' registered by required action '%s', ignoring it.",
                    credentialType, model.getAlias());
            return null;
        }

        // Offering a factor that would not satisfy alreadyEnrolled() would ask the user again on
        // every login, so anything outside the second factor categories is dropped on purpose.
        if (!isSecondFactor(metadata)) {
            logger.warnf("Required action '%s' registers credential type '%s' of category '%s', which is not a second"
                    + " factor, ignoring it.", model.getAlias(), credentialType, metadata.getCategory());
            return null;
        }

        return new SecondFactorOption(model.getAlias(), credentialType, metadata.getDisplayName(),
                metadata.getHelpText(), metadata.getIconCssClass(), metadata.getCategory());
    }

    private static CredentialTypeMetadata metadata(KeycloakSession session, UserModel user, CredentialProvider<?> provider) {
        return provider.getCredentialTypeMetadata(CredentialTypeMetadataContext.builder().user(user).build(session));
    }

    private static boolean isSecondFactor(CredentialTypeMetadata metadata) {
        return metadata != null && SECOND_FACTOR_CATEGORIES.contains(metadata.getCategory());
    }
}
