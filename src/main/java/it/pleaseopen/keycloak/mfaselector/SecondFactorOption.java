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

import org.keycloak.credential.CredentialTypeMetadata;

/**
 * One enrolment choice offered on the selection screen.
 *
 * <p>It pairs the required action to trigger with the presentation metadata Keycloak already
 * publishes for the credential type that action registers, so the screen looks and reads like the
 * built-in credential selection screens and is translated in every language Keycloak ships.
 *
 * <p>Plain getters (rather than a record) because FreeMarker 2.3 only exposes bean properties.
 */
public class SecondFactorOption {

    private final String requiredAction;
    private final String credentialType;
    private final String displayName;
    private final String helpText;
    private final String iconCssClass;
    private final CredentialTypeMetadata.Category category;

    SecondFactorOption(String requiredAction, String credentialType, String displayName, String helpText,
                       String iconCssClass, CredentialTypeMetadata.Category category) {
        this.requiredAction = requiredAction;
        this.credentialType = credentialType;
        this.displayName = displayName;
        this.helpText = helpText;
        this.iconCssClass = iconCssClass;
        this.category = category;
    }

    /** Alias of the required action to add to the authentication session when this option is picked. */
    public String getRequiredAction() {
        return requiredAction;
    }

    /** Credential type that required action registers, e.g. {@code otp} or {@code webauthn}. */
    public String getCredentialType() {
        return credentialType;
    }

    /** Message key, e.g. {@code otp-display-name}. */
    public String getDisplayName() {
        return displayName;
    }

    /** Message key, e.g. {@code otp-help-text}. */
    public String getHelpText() {
        return helpText;
    }

    public String getIconCssClass() {
        return iconCssClass;
    }

    CredentialTypeMetadata.Category getCategory() {
        return category;
    }

    @Override
    public String toString() {
        return "SecondFactorOption[" + requiredAction + " -> " + credentialType + "]";
    }
}
