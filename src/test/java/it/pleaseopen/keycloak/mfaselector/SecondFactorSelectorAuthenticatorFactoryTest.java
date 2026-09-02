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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.models.AuthenticationExecutionModel.Requirement;
import org.keycloak.models.AuthenticatorConfigModel;
import org.keycloak.provider.ProviderConfigProperty;

class SecondFactorSelectorAuthenticatorFactoryTest {

    private final SecondFactorSelectorAuthenticatorFactory factory = new SecondFactorSelectorAuthenticatorFactory();

    private AuthenticationFlowContext contextWith(Map<String, String> config) {
        AuthenticationFlowContext context = mock(AuthenticationFlowContext.class);

        if (config != null) {
            AuthenticatorConfigModel model = new AuthenticatorConfigModel();
            model.setConfig(config);
            when(context.getAuthenticatorConfig()).thenReturn(model);
        }

        return context;
    }

    @Test
    void offersEverythingWhenNotConfigured() {
        assertThat(SecondFactorSelectorAuthenticatorFactory.offeredActions(contextWith(null))).isEmpty();
        assertThat(SecondFactorSelectorAuthenticatorFactory.offeredActions(contextWith(Map.of()))).isEmpty();
        assertThat(SecondFactorSelectorAuthenticatorFactory.offeredActions(
                contextWith(Map.of(SecondFactorSelectorAuthenticatorFactory.OFFERED_ACTIONS, "  ")))).isEmpty();
    }

    @Test
    void splitsTheMultivaluedConfigurationKeycloakStores() {
        var context = contextWith(Map.of(SecondFactorSelectorAuthenticatorFactory.OFFERED_ACTIONS,
                "CONFIGURE_TOTP## webauthn-register ##"));

        assertThat(SecondFactorSelectorAuthenticatorFactory.offeredActions(context))
                .containsExactly("CONFIGURE_TOTP", "webauthn-register");
    }

    @Test
    void forbidsSkippingUnlessExplicitlyAllowed() {
        assertThat(SecondFactorSelectorAuthenticatorFactory.skipAllowed(contextWith(null))).isFalse();
        assertThat(SecondFactorSelectorAuthenticatorFactory.skipAllowed(contextWith(Map.of()))).isFalse();
        assertThat(SecondFactorSelectorAuthenticatorFactory.skipAllowed(
                contextWith(Map.of(SecondFactorSelectorAuthenticatorFactory.ALLOW_SKIP, "false")))).isFalse();
        assertThat(SecondFactorSelectorAuthenticatorFactory.skipAllowed(
                contextWith(Map.of(SecondFactorSelectorAuthenticatorFactory.ALLOW_SKIP, "true")))).isTrue();
    }

    @Test
    void declaresItselfToTheAdminConsole() {
        assertThat(factory.getId()).isEqualTo("second-factor-selector");
        assertThat(factory.isConfigurable()).isTrue();
        assertThat(factory.getRequirementChoices()).containsExactly(Requirement.REQUIRED, Requirement.DISABLED);
        assertThat(factory.isUserSetupAllowed()).isFalse();
        assertThat(factory.getConfigProperties())
                .extracting(ProviderConfigProperty::getName)
                .containsExactly(SecondFactorSelectorAuthenticatorFactory.OFFERED_ACTIONS,
                        SecondFactorSelectorAuthenticatorFactory.ALLOW_SKIP,
                        SecondFactorSelectorAuthenticatorFactory.ANNOUNCED_DEADLINE,
                        SecondFactorSelectorAuthenticatorFactory.GRACE_PERIOD_END);
    }

    @Test
    void hasEveryConfigurationLabelTranslated() throws IOException {
        Properties english = bundle("messages_en.properties");
        Properties french = bundle("messages_fr.properties");

        // The admin console resolves the label and help text of a property as message keys, so a
        // missing entry would put the raw key in front of the administrator.
        for (ProviderConfigProperty property : factory.getConfigProperties()) {
            for (String key : List.of(property.getLabel(), property.getHelpText())) {
                assertThat(english.getProperty(key)).as("English text of '%s'", key).isNotBlank();
                assertThat(french.getProperty(key)).as("French text of '%s'", key).isNotBlank()
                        // Unlike the login screens, the admin console has no MessageFormat behind
                        // it, so a doubled apostrophe would be shown as written.
                        .doesNotContain("''");
            }
        }
    }

    private Properties bundle(String name) throws IOException {
        Properties properties = new Properties();

        try (InputStream stream = getClass().getResourceAsStream("/theme-resources/messages/" + name)) {
            assertThat(stream).as("bundle '%s'", name).isNotNull();
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }

        return properties;
    }

    @Test
    void isDiscoverableThroughTheServiceLoader() {
        List<String> registered = List.of(new java.util.Scanner(
                getClass().getResourceAsStream("/META-INF/services/org.keycloak.authentication.AuthenticatorFactory"),
                java.nio.charset.StandardCharsets.UTF_8).useDelimiter("\\A").next().trim().split("\\R"));

        assertThat(registered).contains(SecondFactorSelectorAuthenticatorFactory.class.getName());
    }
}
