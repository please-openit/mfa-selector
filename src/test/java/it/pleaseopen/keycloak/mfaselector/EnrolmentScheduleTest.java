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

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class EnrolmentScheduleTest {

    private static final String ANNOUNCED = "2026-10-15T18:00:00+02:00";
    private static final String GRACE_END = "2026-11-30T23:59:59+01:00";

    private static final Instant BEFORE_BOTH = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant BETWEEN = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant AFTER_BOTH = Instant.parse("2026-12-25T00:00:00Z");

    @Nested
    @DisplayName("with a deadline announced before the grace period ends")
    class WithGrace {

        private final EnrolmentSchedule schedule = EnrolmentSchedule.of(ANNOUNCED, GRACE_END);

        @Test
        void letsTheUserPostponeUntilTheGracePeriodIsOver() {
            assertThat(schedule.postponeAllowed(BEFORE_BOTH)).isTrue();
            assertThat(schedule.postponeAllowed(BETWEEN)).isTrue();
            assertThat(schedule.postponeAllowed(AFTER_BOTH)).isFalse();
        }

        @Test
        void announcesTheDeadline() {
            assertThat(schedule.deadline()).isEqualTo(Instant.parse("2026-10-15T16:00:00Z"));
        }

        @Test
        void saysTheDeadlineHasPassedDuringTheGracePeriod() {
            assertThat(schedule.overdue(BEFORE_BOTH)).isFalse();
            assertThat(schedule.overdue(BETWEEN)).isTrue();
        }
    }

    @Nested
    @DisplayName("with a grace period ending no later than the announced deadline")
    class WithoutGrace {

        @Test
        void announcesTheEndOfTheGracePeriodRatherThanADeadlineItWouldNotHonour() {
            EnrolmentSchedule schedule = EnrolmentSchedule.of("2026-11-30T00:00:00Z", "2026-10-15T00:00:00Z");

            assertThat(schedule.deadline()).isEqualTo(Instant.parse("2026-10-15T00:00:00Z"));
            assertThat(schedule.postponeAllowed(Instant.parse("2026-10-20T00:00:00Z"))).isFalse();
        }

        @Test
        void forcesTheEnrolmentAsSoonAsTheTwoCoincide() {
            EnrolmentSchedule schedule = EnrolmentSchedule.of(ANNOUNCED, ANNOUNCED);
            Instant deadline = Instant.parse("2026-10-15T16:00:00Z");

            assertThat(schedule.deadline()).isEqualTo(deadline);
            assertThat(schedule.postponeAllowed(deadline.minusSeconds(1))).isTrue();
            assertThat(schedule.postponeAllowed(deadline)).isFalse();
        }
    }

    @Nested
    @DisplayName("partly or not configured")
    class PartlyConfigured {

        @Test
        void leavesPostponingUnboundedWhenNothingIsSet() {
            EnrolmentSchedule schedule = EnrolmentSchedule.of(null, "  ");

            assertThat(schedule.postponeAllowed(AFTER_BOTH)).isTrue();
            assertThat(schedule.deadline()).isNull();
            assertThat(schedule.overdue(AFTER_BOTH)).isFalse();
        }

        @Test
        void announcesTheEndOfTheGracePeriodWhenNoDeadlineIsGiven() {
            EnrolmentSchedule schedule = EnrolmentSchedule.of(null, GRACE_END);

            assertThat(schedule.deadline()).isEqualTo(Instant.parse("2026-11-30T22:59:59Z"));
        }

        @Test
        void announcesADeadlineEvenWithNoGracePeriodConfigured() {
            EnrolmentSchedule schedule = EnrolmentSchedule.of(ANNOUNCED, null);

            assertThat(schedule.deadline()).isEqualTo(Instant.parse("2026-10-15T16:00:00Z"));
            assertThat(schedule.postponeAllowed(AFTER_BOTH)).isTrue();
        }
    }

    @Nested
    @DisplayName("misconfigured")
    class Misconfigured {

        @Test
        void treatsAnUnreadableEndOfGracePeriodAsAlreadyOver() {
            // Failing open on a typo would silently hand out an unlimited grace period.
            EnrolmentSchedule schedule = EnrolmentSchedule.of(ANNOUNCED, "15/10/2026");

            assertThat(schedule.postponeAllowed(BEFORE_BOTH)).isFalse();
        }

        @Test
        void ignoresAnUnreadableAnnouncedDeadline() {
            EnrolmentSchedule schedule = EnrolmentSchedule.of("next monday", GRACE_END);

            assertThat(schedule.deadline()).isEqualTo(Instant.parse("2026-11-30T22:59:59Z"));
            assertThat(schedule.postponeAllowed(BETWEEN)).isTrue();
        }

        @Test
        void rejectsADateWithoutAnOffset() {
            assertThat(EnrolmentSchedule.of(null, "2026-10-15T18:00:00").postponeAllowed(BEFORE_BOTH)).isFalse();
        }
    }
}
