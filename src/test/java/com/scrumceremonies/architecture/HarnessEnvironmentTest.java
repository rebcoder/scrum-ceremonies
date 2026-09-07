package com.scrumceremonies.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The harness ASSERTS the environment it requires, rather than adapting to whatever it finds.
 *
 * <h2>Why this FAILS rather than SKIPS</h2>
 *
 * A suite whose executing test set depends on ambient machine state has no reproducible baseline:
 * two runs of the same commit are not comparable if a wrong JDK or a stopped Docker daemon silently
 * changes which tests run, or how they are instrumented. So these checks fail, loudly, with
 * instructions. A red build that names its cause is strictly better than a green one measured under
 * conditions nobody recorded.
 *
 * <h2>Declared, not discovered</h2>
 *
 * The Docker expectation lives in {@code app.harness.expect-docker} (default {@code true}). A
 * developer who genuinely wants to run without Docker sets it to {@code false} — a deliberate,
 * recorded act, which is the entire point: declared state beats discovered state.
 */
@DisplayName("The test harness asserts its environment instead of adapting to it")
class HarnessEnvironmentTest {

    @Test
    @DisplayName("the JDK is the one the project targets — a silent mismatch mis-instruments everything")
    void jdkMajorVersionMatchesTheProjectTarget() {
        int major = Runtime.version().feature();
        assertThat(major)
                .as("""
                        Running on Java %d, but this project targets Java 17.

                        This is not cosmetic. JaCoCo 0.8.12 cannot instrument newer class files, so \
                        coverage collapses (measured: 15.1%% against ~80%% sustained) and \
                        Testcontainers tests fail with stack traces that read like Docker faults. It \
                        cost an afternoon to trace once already, and it nearly caused a real bug to \
                        be dismissed as environmental.

                        Cause: JAVA_HOME unset, so Maven falls back to whatever JDK is first on the \
                        system. Fix: export JAVA_HOME to a Java 17 home before running the suite.""",
                        major)
                .isEqualTo(17);
    }

    /**
     * Docker died mid-session once and every Testcontainers class — the whole cache suite — errored
     * at container start. Nothing said "Docker is down"; it read as dozens of test failures.
     *
     * <p>A gate written from a list of past incidents covers the incidents, not the class. The
     * general property is <i>any ambient dependency that changes which tests execute</i>, and the
     * next uncovered axis is whichever one has not failed yet.
     */
    @Test
    @DisplayName("Docker is reachable — Testcontainers classes error rather than skip when it is not")
    void dockerIsReachableForTestcontainers() {
        boolean expectDocker = Boolean.parseBoolean(
                System.getProperty("app.harness.expect-docker", "true"));
        boolean reachable = dockerReachable();

        assertThat(reachable)
                .as("""
                        Docker reachable=%s, but the harness declares expect-docker=%s.

                        WHY THIS FAILS RATHER THAN SKIPS: Testcontainers classes do not skip when the \
                        daemon is absent — they ERROR at container start, which reads as test \
                        failures rather than as a missing dependency. The entire cache suite is \
                        affected.

                        FIX: start Docker (and `docker compose up -d redis`), or declare the intent: \
                        -Dapp.harness.expect-docker=false

                        Declared state beats discovered state.""",
                        reachable, expectDocker)
                .isEqualTo(expectDocker);
    }

    /**
     * This probe asserts the OUTPUT, not the exit code, and the difference is the whole gate.
     *
     * <p>The first version returned {@code done && pr.exitValue() == 0}. <b>{@code docker info}
     * exits 0 when the daemon is unreachable</b> — it prints {@code "Cannot connect to the Docker
     * daemon"} and still returns success, because the CLIENT ran fine. So the check was true
     * whenever the {@code docker} binary merely existed on PATH, and <b>could not fail</b>: a full
     * suite once had every Testcontainers class erroring at container start while this gate passed.
     *
     * <p>So the probe now requires a <b>positive identification</b>: a server version string. An
     * absent daemon renders {@code {{.ServerVersion}}} as an error message, not as digits.
     * Asserting what the answer must LOOK LIKE beats asserting that the command did not complain.
     */
    private static boolean dockerReachable() {
        try {
            Process pr = new ProcessBuilder("docker", "info", "--format", "{{.ServerVersion}}")
                    .redirectErrorStream(true).start();
            String output = new String(pr.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
            boolean done = pr.waitFor(8, java.util.concurrent.TimeUnit.SECONDS);
            // A reachable daemon renders a version like "28.0.4". An unreachable one renders
            // "Cannot connect to the Docker daemon at unix://..." — and exits 0 either way.
            return done && pr.exitValue() == 0 && DOCKER_SERVER_VERSION.matcher(output).find();
        } catch (Exception e) {
            return false;
        }
    }

    /** First line must START with a version number — not merely contain one somewhere in prose. */
    private static final java.util.regex.Pattern DOCKER_SERVER_VERSION =
            java.util.regex.Pattern.compile("^\\d+\\.\\d+");
}
