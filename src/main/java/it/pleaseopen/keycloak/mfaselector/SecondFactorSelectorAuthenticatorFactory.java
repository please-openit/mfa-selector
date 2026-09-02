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

import java.util.Arrays;
import java.util.List;

import org.keycloak.Config;
import org.keycloak.authentication.AbstractAuthenticationFlowContext;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel.Requirement;
import org.keycloak.models.AuthenticatorConfigModel;
import org.keycloak.models.Constants;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.provider.ProviderConfigurationBuilder;

public class SecondFactorSelectorAuthenticatorFactory implements AuthenticatorFactory {

    public static final String PROVIDER_ID = "second-factor-selector";

    /** Required action aliases to offer; when unset, every eligible required action of the realm is offered. */
    public static final String OFFERED_ACTIONS = "offered.second.factors";

    /** Whether the selection screen shows a button letting the user postpone the enrolment. */
    public static final String ALLOW_SKIP = "allow.skip";

    /** Deadline announced on the screen, as an ISO 8601 date and time with an offset. */
    public static final String ANNOUNCED_DEADLINE = "announced.deadline";

    /** Instant past which postponing stops being offered, same format. */
    public static final String GRACE_PERIOD_END = "grace.period.end";

    private static final Requirement[] REQUIREMENT_CHOICES = {Requirement.REQUIRED, Requirement.DISABLED};

    // The admin console runs the label and help text of a configuration property through its
    // translation function, so these are message keys resolved against the admin message bundle
    // this provider ships in theme-resources. See StringComponent and BooleanComponent in admin-ui.
    private static final String OFFERED_ACTIONS_LABEL = "secondFactorSelector.offeredActions.label";
    private static final String OFFERED_ACTIONS_HELP = "secondFactorSelector.offeredActions.help";
    private static final String ALLOW_SKIP_LABEL = "secondFactorSelector.allowSkip.label";
    private static final String ALLOW_SKIP_HELP = "secondFactorSelector.allowSkip.help";
    private static final String ANNOUNCED_DEADLINE_LABEL = "secondFactorSelector.announcedDeadline.label";
    private static final String ANNOUNCED_DEADLINE_HELP = "secondFactorSelector.announcedDeadline.help";
    private static final String GRACE_PERIOD_END_LABEL = "secondFactorSelector.gracePeriodEnd.label";
    private static final String GRACE_PERIOD_END_HELP = "secondFactorSelector.gracePeriodEnd.help";

    private static final List<ProviderConfigProperty> CONFIG_PROPERTIES = ProviderConfigurationBuilder.create()
            .property()
            .name(OFFERED_ACTIONS)
            .label(OFFERED_ACTIONS_LABEL)
            .helpText(OFFERED_ACTIONS_HELP)
            .type(ProviderConfigProperty.MULTIVALUED_STRING_TYPE)
            .add()
            .property()
            .name(ALLOW_SKIP)
            .label(ALLOW_SKIP_LABEL)
            .helpText(ALLOW_SKIP_HELP)
            .type(ProviderConfigProperty.BOOLEAN_TYPE)
            .defaultValue(false)
            .add()
            .property()
            .name(ANNOUNCED_DEADLINE)
            .label(ANNOUNCED_DEADLINE_LABEL)
            .helpText(ANNOUNCED_DEADLINE_HELP)
            .type(ProviderConfigProperty.STRING_TYPE)
            .add()
            .property()
            .name(GRACE_PERIOD_END)
            .label(GRACE_PERIOD_END_LABEL)
            .helpText(GRACE_PERIOD_END_HELP)
            .type(ProviderConfigProperty.STRING_TYPE)
            .add()
            .build();

    static List<String> offeredActions(AbstractAuthenticationFlowContext context) {
        String value = configValue(context, OFFERED_ACTIONS);

        if (value == null || value.isBlank()) {
            return List.of();
        }

        return Arrays.stream(Constants.CFG_DELIMITER_PATTERN.split(value))
                .map(String::trim)
                .filter(alias -> !alias.isEmpty())
                .toList();
    }

    static boolean skipAllowed(AbstractAuthenticationFlowContext context) {
        return Boolean.parseBoolean(configValue(context, ALLOW_SKIP));
    }

    /** The window during which postponing is still offered, when the option allows it at all. */
    static EnrolmentSchedule schedule(AbstractAuthenticationFlowContext context) {
        return EnrolmentSchedule.of(configValue(context, ANNOUNCED_DEADLINE), configValue(context, GRACE_PERIOD_END));
    }

    private static String configValue(AbstractAuthenticationFlowContext context, String key) {
        AuthenticatorConfigModel config = context.getAuthenticatorConfig();
        return config == null || config.getConfig() == null ? null : config.getConfig().get(key);
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getDisplayType() {
        return "Second Factor Selector";
    }

    @Override
    public String getHelpText() {
        return "Asks a user who has no second factor to pick one to enrol, then hands over to the matching required"
                + " action. Users who already have a second factor are not prompted.";
    }

    @Override
    public String getReferenceCategory() {
        // No credential is validated here, so this authenticator is tied to no credential type.
        return null;
    }

    @Override
    public boolean isConfigurable() {
        return true;
    }

    @Override
    public Requirement[] getRequirementChoices() {
        return REQUIREMENT_CHOICES;
    }

    @Override
    public boolean isUserSetupAllowed() {
        return false;
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return CONFIG_PROPERTIES;
    }

    @Override
    public Authenticator create(KeycloakSession session) {
        return new SecondFactorSelectorAuthenticator();
    }

    @Override
    public void init(Config.Scope config) {
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
    }

    @Override
    public void close() {
    }
}
