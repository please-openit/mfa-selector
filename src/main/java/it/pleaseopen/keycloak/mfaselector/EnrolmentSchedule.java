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
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

import org.jboss.logging.Logger;

/**
 * The window during which a user may still postpone the enrolment.
 *
 * <p>Two instants describe it: the deadline announced to the user, and the end of the grace period
 * after which the enrolment is forced. Postponing stops at the end of the grace period whatever the
 * announced deadline says, so a grace period ending first simply means no grace at all. In that case
 * the end of the grace period is what gets announced, rather than a deadline the server would not
 * honour.
 */
final class EnrolmentSchedule {

    private static final Logger logger = Logger.getLogger(EnrolmentSchedule.class);

    /** Nothing configured: postponing is bounded by the authenticator's own option alone. */
    static final EnrolmentSchedule UNBOUNDED = new EnrolmentSchedule(null, null, false);

    private final Instant announcedDeadline;
    private final Instant gracePeriodEnd;
    private final boolean unusable;

    private EnrolmentSchedule(Instant announcedDeadline, Instant gracePeriodEnd, boolean unusable) {
        this.announcedDeadline = announcedDeadline;
        this.gracePeriodEnd = gracePeriodEnd;
        this.unusable = unusable;
    }

    static EnrolmentSchedule of(String announcedDeadline, String gracePeriodEnd) {
        Instant announced = parse(announcedDeadline, SecondFactorSelectorAuthenticatorFactory.ANNOUNCED_DEADLINE);
        Instant grace = parse(gracePeriodEnd, SecondFactorSelectorAuthenticatorFactory.GRACE_PERIOD_END);

        // An end of grace period nobody can read must not be read as "no end at all": the safe
        // reading of a misconfigured deadline is that the grace period is over.
        boolean unusable = grace == null && isSet(gracePeriodEnd);

        return new EnrolmentSchedule(announced, grace, unusable);
    }

    /** Whether the schedule still leaves room to postpone. */
    boolean postponeAllowed(Instant now) {
        if (unusable) {
            return false;
        }

        return gracePeriodEnd == null || now.isBefore(gracePeriodEnd);
    }

    /**
     * The instant to tell the user about: the announced deadline, unless the grace period ends
     * first, in which case announcing the deadline would promise more time than there is.
     */
    Instant deadline() {
        if (announcedDeadline == null) {
            return gracePeriodEnd;
        }

        if (gracePeriodEnd == null || announcedDeadline.isBefore(gracePeriodEnd)) {
            return announcedDeadline;
        }

        return gracePeriodEnd;
    }

    /** Whether that deadline has gone by, which is worth saying more firmly. */
    boolean overdue(Instant now) {
        Instant deadline = deadline();
        return deadline != null && !now.isBefore(deadline);
    }

    private static Instant parse(String value, String option) {
        if (!isSet(value)) {
            return null;
        }

        try {
            return OffsetDateTime.parse(value.trim()).toInstant();
        } catch (DateTimeParseException e) {
            logger.errorf("Option '%s' is not an ISO 8601 date and time with an offset, for instance"
                    + " 2026-10-15T18:00:00+02:00, so it is ignored: '%s'", option, value);
            return null;
        }
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }
}
