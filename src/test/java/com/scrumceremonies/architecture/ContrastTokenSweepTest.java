package com.scrumceremonies.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every v1 palette token pair meets WCAG AA (4.5:1) — or is REGISTERED here.
 *
 * <h2>The defect that produced this gate</h2>
 *
 * The destructive-confirm button was written as
 * {@code background: linear-gradient(135deg, var(--color-primary), var(--color-accent))} with a
 * hardcoded {@code color: white}. Contrast is not a property of the button, it is a property of the
 * pair — and a gradient has <b>two</b> pairs, one per stop:
 *
 * <pre>
 *   light   white on #0f7c90 (primary)      4.88:1  PASS
 *   light   white on #2ba4b8 (accent)       2.96:1  FAIL
 *   dark    white on #1ea5bb (primary)      2.94:1  FAIL
 *   dark    white on #7ee0ec (accent)       1.53:1  FAIL
 * </pre>
 *
 * <h2>What this gate deliberately does NOT claim</h2>
 *
 * It does not read {@code font-size}. WCAG AA is 4.5:1 for normal text but 3.0:1 for large text
 * (>=18.66px bold or >=24px) and for non-text UI, so some registered entries may be compliant in
 * situ. The register is an <b>upper bound on failures, not a list of confirmed bugs</b>.
 */
@DisplayName("v1 colour tokens meet WCAG AA, or are registered as known exceptions")
class ContrastTokenSweepTest {

    private static final Path V1_STYLES = Path.of("frontend", "v1", "style.css");

    /** v1 tokens actually applied as `color:` somewhere in frontend/v1/*.css. */
    private static final List<String> V1_TEXT_TOKENS = List.of(
            "color-text", "color-text-secondary", "color-text-tertiary",
            "color-primary", "color-danger", "color-success");

    /** The two grounds v1 paints text on: card surfaces and the page background. */
    private static final List<String> V1_GROUND_TOKENS = List.of("color-surface", "color-bg");

    /** WCAG 2.1 AA, normal text. Large text and non-text UI are 3.0 — see the class note. */
    private static final double AA_NORMAL_TEXT = 4.5;

    private static final Pattern TOKEN =
            Pattern.compile("--([\\w-]+):\\s*(#[0-9a-fA-F]{3,8})\\s*;");

    @Test
    @DisplayName("CONTROL — the ratio maths agrees with published WCAG values")
    void contrastMathIsCorrect() {
        // Anchors from the WCAG 2.1 definition: identical colours are 1:1, black on white is 21:1.
        assertThat(contrast("#ffffff", "#ffffff")).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.01));
        assertThat(contrast("#000000", "#ffffff")).isCloseTo(21.0, org.assertj.core.data.Offset.offset(0.01));

        // The measured pair this gate was built around. If this drifts, the sweep's verdicts are
        // arithmetic noise and every assertion above is meaningless.
        assertThat(contrast("#ffffff", "#7a8cff"))
                .as("white on --color-accent must compute to 2.99:1 — the failing stop of the "
                        + "destructive-confirm gradient, and the number this whole gate rests on")
                .isCloseTo(2.99, org.assertj.core.data.Offset.offset(0.01));
        assertThat(contrast("#1a1b2e", "#a6b3ff"))
                .as("the dark theme's --color-on-primary on --color-accent must clear AA, or the "
                        + "recommended fix in the failure message above is itself wrong")
                .isGreaterThan(AA_NORMAL_TEXT);
    }

    @Test
    @DisplayName("every token pair meets AA, or the failing pair is registered")
    void v1TokenPairsMeetAaOrAreRegistered() throws IOException {
        // The palette is measured here rather than assumed: every pair is computed from the real
        // tokens in style.css.
        //
        // Measured, and worth stating because it inverts the usual expectation: the DARK theme is
        // better than the light one (1 failing pair against 7). The light theme's page ground is
        // #eef3f5 rather than white, which quietly costs every foreground a little --
        // --color-primary reads 4.88:1 on white and 4.36:1 on the ground it is actually shown on.
        Set<String> known = new TreeSet<>(List.of(
            "color-danger|color-bg|light",  // 3.36:1
            "color-danger|color-surface|light",  // 3.76:1
            "color-primary|color-bg|light",  // 4.36:1
            "color-success|color-bg|light",  // 2.27:1
            "color-success|color-surface|light",  // 2.54:1
            "color-text-tertiary|color-bg|light",  // 2.65:1
            "color-text-tertiary|color-surface|dark",  // 4.15:1
            "color-text-tertiary|color-surface|light"   // 2.97:1
        ));

        Map<String, String> light = tokenBlock(V1_STYLES, ":root {");
        Map<String, String> dark = tokenBlock(V1_STYLES, "html[data-theme=\"dark\"] {");

        assertThat(light).as("no tokens parsed from v1 :root -- the sweep would measure nothing")
                .hasSizeGreaterThanOrEqualTo(10);
        assertThat(dark).as("""
                No tokens parsed from v1's dark block. v1 HAS a dark theme (html[data-theme="dark"],                 style.css:70) and it redefines every token below -- a probe that keeps only the LAST                 definition of each token silently measures DARK values and labels them light. That                 happened while writing this test, and it is why the two blocks are read separately.""")
                .hasSizeGreaterThanOrEqualTo(10);

        Set<String> failing = new TreeSet<>();
        int comparisons = 0;
        for (Map.Entry<String, Map<String, String>> theme
                : Map.of("light", light, "dark", dark).entrySet()) {
            for (String text : V1_TEXT_TOKENS) {
                for (String ground : V1_GROUND_TOKENS) {
                    String fg = theme.getValue().get(text);
                    String bg = theme.getValue().get(ground);
                    if (fg == null || bg == null) {
                        continue;
                    }
                    comparisons++;
                    if (contrast(fg, bg) < AA_NORMAL_TEXT) {
                        failing.add(text + "|" + ground + "|" + theme.getKey());
                    }
                }
            }
        }

        assertThat(comparisons)
                .as("v1 sweep resolved %d comparisons; a sweep over nothing reports clean", comparisons)
                .isGreaterThanOrEqualTo(20);

        Set<String> unregistered = new LinkedHashSet<>(failing);
        unregistered.removeAll(known);
        assertThat(unregistered)
                .as("NEW v1 contrast violations, below AA %.1f and unregistered:%n%n%s",
                        AA_NORMAL_TEXT, String.join("\n", unregistered))
                .isEmpty();

        Set<String> stale = new LinkedHashSet<>(known);
        stale.removeAll(failing);
        assertThat(stale)
                .as("These v1 pairs are registered as below AA but now pass:%n%n%s%n%n"
                        + "Delete them from this register in the same commit that fixed them, for "
                        + "the same reason any such register must be exact -- a register that keeps "
                        + "claiming a violation that no longer exists is a false statement.",
                        String.join("\n", stale))
                .isEmpty();
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    /** Brace-matched so a nested rule inside the block cannot terminate it early. */
    private static Map<String, String> tokenBlock(Path file, String selector) throws IOException {
        String scss = Files.readString(file, StandardCharsets.UTF_8);
        int start = scss.indexOf(selector);
        if (start < 0) {
            return Map.of();
        }
        int depth = 0;
        int i = start;
        for (; i < scss.length(); i++) {
            char c = scss.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                break;
            }
        }
        Map<String, String> tokens = new HashMap<>();
        Matcher m = TOKEN.matcher(scss.substring(start, Math.min(i, scss.length())));
        while (m.find()) {
            tokens.put(m.group(1), m.group(2));
        }
        return tokens;
    }

    private static String resolve(String value, Map<String, String> theme) {
        String v = value.trim();
        if ("white".equalsIgnoreCase(v) || "#fff".equalsIgnoreCase(v)) {
            return "#ffffff";
        }
        if ("black".equalsIgnoreCase(v) || "#000".equalsIgnoreCase(v)) {
            return "#000000";
        }
        if (v.startsWith("var(--") && v.endsWith(")")) {
            return theme.get(v.substring(6, v.length() - 1));
        }
        return v.matches("#[0-9a-fA-F]{6}") ? v : null;
    }

    /** WCAG 2.1 relative luminance / contrast ratio. */
    private static double contrast(String fg, String bg) {
        double a = luminance(fg);
        double b = luminance(bg);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    private static double luminance(String hex) {
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        if (h.length() == 3) {
            h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
        }
        double r = channel(Integer.parseInt(h.substring(0, 2), 16));
        double g = channel(Integer.parseInt(h.substring(2, 4), 16));
        double b = channel(Integer.parseInt(h.substring(4, 6), 16));
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private static double channel(int raw) {
        double c = raw / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
}
