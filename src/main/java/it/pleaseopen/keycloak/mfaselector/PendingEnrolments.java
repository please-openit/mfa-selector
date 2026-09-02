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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.jboss.logging.Logger;
import org.keycloak.models.UserModel;
import org.keycloak.storage.ReadOnlyException;

/**
 * Remembers which required actions this authenticator put on an account.
 *
 * <p>Postponing has to take back what the user picked earlier, otherwise a hasty click would leave
 * an enrolment they cannot escape until the grace period is over. Taking back only what is recorded
 * here means a required action an administrator or another tool set is never touched, even when it
 * happens to be the same one.
 */
final class PendingEnrolments {

    static final String ATTRIBUTE = "mfa-selector.pending-enrolments";

    private static final Logger logger = Logger.getLogger(PendingEnrolments.class);

    private PendingEnrolments() {
    }

    /** Forgets the actions the account no longer carries: they were run, or removed elsewhere. */
    static void reconcile(UserModel user) {
        Set<String> remembered = remembered(user);

        if (remembered.isEmpty()) {
            return;
        }

        Set<String> stillPending = user.getRequiredActionsStream().collect(Collectors.toSet());

        if (remembered.retainAll(stillPending)) {
            store(user, remembered);
        }
    }

    /**
     * Records the factor the user just picked, in place of any earlier one: hesitating between
     * factors must not end up asking them to enrol several.
     *
     * @return whether the choice could be recorded. When it could not, the caller must keep the
     *         required action to the login at hand, since nothing would be able to take it back.
     */
    static boolean remember(UserModel user, String alias) {
        remembered(user).forEach(user::removeRequiredAction);
        return store(user, Set.of(alias));
    }

    /** Takes back every required action this authenticator armed. */
    static void release(UserModel user) {
        Set<String> remembered = remembered(user);

        if (remembered.isEmpty()) {
            return;
        }

        remembered.forEach(user::removeRequiredAction);
        store(user, Set.of());
    }

    private static Set<String> remembered(UserModel user) {
        return user.getAttributeStream(ATTRIBUTE)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static boolean store(UserModel user, Set<String> aliases) {
        try {
            if (aliases.isEmpty()) {
                user.removeAttribute(ATTRIBUTE);
            } else {
                user.setAttribute(ATTRIBUTE, List.copyOf(aliases));
            }

            return true;
        } catch (ReadOnlyException e) {
            // Federated accounts in read-only mode, an AD holding the passwords being the usual
            // case, accept no attribute of ours. The caller falls back to a choice that lasts for
            // the current login only, which needs no bookkeeping.
            logger.debugf("Account '%s' is read-only, the chosen second factor will not be remembered"
                    + " beyond this login", user.getUsername());
            return false;
        }
    }
}
