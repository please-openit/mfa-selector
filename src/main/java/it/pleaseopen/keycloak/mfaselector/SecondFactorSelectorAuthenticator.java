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

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Locale;

import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import org.jboss.logging.Logger;
import org.keycloak.common.util.Time;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.Authenticator;
import org.keycloak.events.Details;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

/**
 * Asks a user who has no second factor to enrol one.
 *
 * <p>The authenticator never collects the credential itself: the user picks a method, the matching
 * required action is added to the authentication session and Keycloak runs it right after the flow
 * completes, exactly as it would for a required action set on the account. See
 * {@code AuthenticationManager#getApplicableRequiredActionsSorted}, which merges the user's required
 * actions with the ones carried by the authentication session.
 *
 * <p>Users who already hold a second factor go through untouched.
 */
public class SecondFactorSelectorAuthenticator implements Authenticator {

    private static final Logger logger = Logger.getLogger(SecondFactorSelectorAuthenticator.class);

    /** Name of the submit button carrying the chosen required action alias. */
    public static final String SELECTION_PARAM = "second-factor";

    /** Name of the submit button used to postpone the enrolment, when the option is enabled. */
    public static final String SKIP_PARAM = "skip-second-factor";

    static final String TEMPLATE = "select-second-factor.ftl";
    static final String OPTIONS_ATTRIBUTE = "secondFactorOptions";
    static final String SKIP_ALLOWED_ATTRIBUTE = "secondFactorSkipAllowed";
    static final String DEADLINE_ATTRIBUTE = "secondFactorDeadline";
    static final String OVERDUE_ATTRIBUTE = "secondFactorOverdue";

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        KeycloakSession session = context.getSession();
        UserModel user = context.getUser();

        PendingEnrolments.reconcile(user);

        if (SecondFactors.alreadyEnrolled(session, user)) {
            // Whatever enrolment was still awaited is moot now, including one for another factor.
            PendingEnrolments.release(user);
            context.success();
            return;
        }

        List<SecondFactorOption> options = offered(context);
        if (options.isEmpty()) {
            RealmModel realm = context.getRealm();
            logger.warnf("No second factor can be enrolled in realm '%s': letting user '%s' through. Check that the"
                            + " required actions registering a second factor are enabled and, if the '%s' option is set,"
                            + " that it lists them.",
                    realm.getName(), user.getUsername(), SecondFactorSelectorAuthenticatorFactory.OFFERED_ACTIONS);
            context.success();
            return;
        }

        context.challenge(form(context, options, null));
    }

    @Override
    public void action(AuthenticationFlowContext context) {
        MultivaluedMap<String, String> formData = context.getHttpRequest().getDecodedFormParameters();
        UserModel user = context.getUser();

        if (formData.containsKey(SKIP_PARAM) && skipAllowed(context)) {
            // Takes back a factor picked in a hurry, so postponing stays possible until the grace
            // period is over.
            PendingEnrolments.release(user);
            context.getEvent().detail(Details.CUSTOM_REQUIRED_ACTION, "second-factor-enrolment-skipped");
            context.success();
            return;
        }

        List<SecondFactorOption> options = offered(context);
        String selected = formData.getFirst(SELECTION_PARAM);

        SecondFactorOption option = options.stream()
                .filter(candidate -> candidate.getRequiredAction().equals(selected))
                .findFirst()
                .orElse(null);

        if (option == null) {
            // Only reachable if the submitted value was tampered with or the offer changed meanwhile.
            context.challenge(form(context, options, "selectSecondFactorInvalidSelection"));
            return;
        }

        // Handing over to Keycloak: the required action runs once the authentication flow is done.
        // Put on the account it also survives an abandoned enrolment and fires on a login that goes
        // through the cookie rather than this flow, which is why it is recorded before being armed.
        if (PendingEnrolments.remember(user, option.getRequiredAction())) {
            user.addRequiredAction(option.getRequiredAction());
        } else {
            context.getAuthenticationSession().addRequiredAction(option.getRequiredAction());
        }

        context.getEvent().detail(Details.CREDENTIAL_TYPE, option.getCredentialType());
        context.success();
    }

    private List<SecondFactorOption> offered(AuthenticationFlowContext context) {
        return SecondFactors.offered(context.getSession(), context.getRealm(), context.getUser(),
                context.getAuthenticationSession(), SecondFactorSelectorAuthenticatorFactory.offeredActions(context));
    }

    /** Postponing needs both the option to be on and the grace period not to be over. */
    private boolean skipAllowed(AuthenticationFlowContext context) {
        return SecondFactorSelectorAuthenticatorFactory.skipAllowed(context)
                && SecondFactorSelectorAuthenticatorFactory.schedule(context).postponeAllowed(now());
    }

    private Response form(AuthenticationFlowContext context, List<SecondFactorOption> options, String error) {
        EnrolmentSchedule schedule = SecondFactorSelectorAuthenticatorFactory.schedule(context);
        String deadline = format(context, schedule.deadline());

        var form = context.form()
                .setAttribute(OPTIONS_ATTRIBUTE, options)
                .setAttribute(SKIP_ALLOWED_ATTRIBUTE, skipAllowed(context))
                .setAttribute(OVERDUE_ATTRIBUTE, deadline != null && schedule.overdue(now()));

        if (deadline != null) {
            form.setAttribute(DEADLINE_ATTRIBUTE, deadline);
        }

        if (error != null) {
            form.setError(error);
        }

        return form.createForm(TEMPLATE);
    }

    /** The deadline as the user reads it: their language, the server's time zone. */
    private String format(AuthenticationFlowContext context, Instant deadline) {
        if (deadline == null) {
            return null;
        }

        Locale locale = context.getSession().getContext().resolveLocale(context.getUser());

        return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.LONG, FormatStyle.SHORT)
                .withLocale(locale)
                .withZone(ZoneId.systemDefault())
                .format(deadline);
    }

    private static Instant now() {
        return Instant.ofEpochMilli(Time.currentTimeMillis());
    }

    @Override
    public boolean requiresUser() {
        return true;
    }

    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        return true;
    }

    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
        // Nothing to set upfront: the required action is chosen by the user during the flow.
    }

    @Override
    public void close() {
    }
}
