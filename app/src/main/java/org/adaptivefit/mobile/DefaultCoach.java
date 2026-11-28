package org.adaptivefit.mobile;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** One in-flight default per workout scope across foreground screen recreation. Main-thread owned. */
final class DefaultCoach {
    enum Status { READY, LOADING, NO_PROVIDER, NO_GOAL, NO_RECORDS, FAILED }
    record State(Status status, DailyCoach.Target target, String feedback, List<String> citations) {}
    private record Scope(String database, String exercise, LocalDate day, ZoneId zone, String goal) {}

    private static final Map<Scope, Job> pending = new HashMap<>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private final Context context;
    private final DailyGoals goals;
    private Scope scope;
    private Consumer<State> render;
    private State state;
    private Job job;

    DefaultCoach(Context context, WorkoutStore workouts) {
        this.context = context.getApplicationContext();
        goals = new DailyGoals(workouts);
    }

    void unbind() {
        if (job != null) job.observers.remove(this);
        scope = null;
        render = null;
        state = null;
        job = null;
    }

    private Scope scope(String exercise) {
        ZoneId zone = ZoneId.systemDefault();
        try (CoachingStore coaching = new CoachingStore(context)) {
            return new Scope(context.getDatabasePath("workouts.db").getPath(), exercise,
                    LocalDate.now(zone), zone, coaching.goal());
        }
    }

    void bind(String exercise, Consumer<State> listener) {
        try {
            Scope next = scope(exercise);
            if (!next.equals(scope)) unbind();
            scope = next;
            render = listener;
            State stored = goals.saved(exercise, next.day());
            if (stored != null) {
                if (job != null) job.observers.remove(this);
                job = null;
                show(stored);
                return;
            }
            if (next.goal() == null) { show(empty(Status.NO_GOAL)); return; }
            DailyCoach.Provider provider = DailyCoach.provider;
            if (provider == null) { show(empty(Status.NO_PROVIDER)); return; }
            if (state != null && state.status() == Status.FAILED) { show(state); return; }
            Job existing = pending.get(next);
            if (existing == null) {
                String question = exercise == null ? "오늘 유산소 목표 시간을 제안해 주세요."
                        : "오늘 " + exercise + "의 무게, 반복 횟수와 세트 수를 제안해 주세요.";
                DailyCoach.Request request = goals.request(exercise, question, next.goal(), next.day(), next.zone());
                if (request.evidence().isEmpty()) { show(empty(Status.NO_RECORDS)); return; }
                existing = new Job(next, request, provider);
                pending.put(next, existing);
                existing.start();
            }
            job = existing;
            if (!job.observers.contains(this)) job.observers.add(this);
            show(empty(Status.LOADING));
        } catch (RuntimeException failure) {
            unbind();
            render = listener;
            show(empty(Status.FAILED));
        }
    }

    private static State empty(Status status) {
        return new State(status, null, null, Collections.emptyList());
    }

    private void show(State value) {
        state = value;
        render.accept(value);
    }

    private void complete(Job source, DailyCoach.Response response) {
        if (job != source || render == null) return;
        job = null;
        try {
            if (!Objects.equals(scope, scope(source.scope.exercise())) || source.provider != DailyCoach.provider) {
                show(empty(Status.FAILED));
                return;
            }
            if (response != null && response.proposal() != null && response.valid(source.request)) {
                goals.saveFirst(source.request, response);
            }
            State stored = goals.saved(scope.exercise(), scope.day());
            show(stored == null ? empty(Status.FAILED) : stored);
        } catch (RuntimeException failure) {
            show(empty(Status.FAILED));
        }
    }

    private static final class Job {
        final Scope scope;
        final DailyCoach.Request request;
        final DailyCoach.Provider provider;
        final List<DefaultCoach> observers = new ArrayList<>();
        final Runnable timeout = () -> finish(null);
        Thread worker;

        Job(Scope scope, DailyCoach.Request request, DailyCoach.Provider provider) {
            this.scope = scope;
            this.request = request;
            this.provider = provider;
        }

        void start() {
            worker = new Thread(() -> {
                DailyCoach.Response result = null;
                try {
                    result = provider.answer(request);
                } catch (Exception ignored) {
                    // Keep ordinary workout entry usable if the provider fails.
                }
                DailyCoach.Response response = result;
                main.post(() -> finish(response));
            }, "default-workout-coach");
            worker.start();
            main.postDelayed(timeout, 30_000);
        }

        void finish(DailyCoach.Response response) {
            if (pending.get(scope) != this) return;
            pending.remove(scope);
            main.removeCallbacks(timeout);
            worker.interrupt();
            for (DefaultCoach observer : new ArrayList<>(observers)) observer.complete(this, response);
            observers.clear();
        }
    }
}
