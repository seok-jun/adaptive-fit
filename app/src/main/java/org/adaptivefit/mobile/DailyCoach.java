package org.adaptivefit.mobile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The app owns the evidence and daily goals. A provider can only propose a change. */
final class DailyCoach {
    interface Provider {
        Response answer(Request request) throws Exception;
    }

    // The connection issue installs a provider. No simulated feedback in the shipped app.
    static volatile Provider provider;

    record Target(BigDecimal kg, int reps, int sets, int minutes) {
        boolean valid(boolean cardio) {
            if (cardio) return kg == null && reps == 0 && sets == 0 && minutes > 0;
            return kg != null && kg.signum() >= 0 && reps >= 1 && reps <= 100 && sets > 0 && minutes == 0;
        }
    }

    record Evidence(String ref, String text) {}

    record Request(String id, String exercise, String question, String goal, LocalDate day, ZoneId zone,
                   Target yesterdayTarget, Target todayTarget, List<Evidence> evidence) {
        Request {
            evidence = Collections.unmodifiableList(new ArrayList<>(evidence));
        }
    }

    record Response(String requestId, String feedback, List<String> refs, Target proposal) {
        Response {
            refs = refs == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(refs));
        }

        boolean valid(Request request) {
            if (!request.id().equals(requestId) || feedback == null || feedback.trim().isEmpty()
                    || refs.isEmpty() || (proposal != null && !proposal.valid(request.exercise() == null))) {
                return false;
            }
            Set<String> available = new HashSet<>();
            for (Evidence item : request.evidence()) available.add(item.ref());
            return available.containsAll(refs);
        }
    }
}
