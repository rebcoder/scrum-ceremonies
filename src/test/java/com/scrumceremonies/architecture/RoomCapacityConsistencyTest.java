package com.scrumceremonies.architecture;

import com.scrumceremonies.model.MoodRoom;
import com.scrumceremonies.model.RetrospectiveBoard;
import com.scrumceremonies.model.Room;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins agreement of the ten-user room cap across every place it is expressed: the
 * Redis Lua script that actually enforces it, each domain model's own
 * {@code MAX_USERS} constant, and the number the frontend displays.
 *
 * <p>The Redis Lua script in {@code RedisRoomPresenceService} is the only point that
 * actually refuses an eleventh user, and it already has its own tests for that. What
 * it does not test is whether the <em>other</em> copies of the cap agree with it.
 *
 * <p>{@code Room.canAddUser()} (and its counterparts on the other room models) is not
 * dead weight — it runs on every join from {@code RoomService.addUserName}, and the
 * cap is documented as being enforced by both the Lua script and the Java model, i.e.
 * two enforcement points by design. That is genuine defence-in-depth. It also means the
 * Java-side check can never be exercised through the normal join path: the Redis layer
 * always answers first, so a broken inner constant produces no visible failure there.
 * The only way to pin the inner layer is to call it directly, which is what
 * {@link #everyRoomModelEnforcesTheSameCap()} does.
 *
 * <p>The frontend is not an enforcement point at all; it only renders the number and
 * reacts to the server's {@code roomFull} flag.
 *
 * <h2>Why agreement is the thing worth testing, not the number</h2>
 *
 * Because the Lua script is the real gate and already well covered, the risk that
 * matters here is divergence — someone changing {@code app.room.max-users} to 12 while
 * a model's {@code MAX_USERS} constant stays 10 and the UI keeps rendering {@code /10}.
 * None of those existing tests would fail, and the product would disagree with itself
 * about its own limit in three separate places. That divergence is what this class
 * pins.
 */
@DisplayName("the v1 room cap agrees across every place it is written")
class RoomCapacityConsistencyTest {

    /**
     * The single source of truth for the cap. {@code application.properties} holds the
     * runtime value handed to the Lua script that actually enforces it; every other
     * copy of this number must match it.
     */
    private static final int CAP = 10;

    private static final Path PROPS = Path.of("src/main/resources/application.properties");
    private static final Path V1 = Path.of("frontend/v1");

    @Test
    @DisplayName("the configured cap — the value the enforcing Lua script is handed — is 10")
    void configuredCapIsTen() throws IOException {
        String props = Files.readString(PROPS, StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("(?m)^app\\.room\\.max-users\\s*=\\s*(\\d+)").matcher(props);
        assertThat(m.find())
                .as("app.room.max-users is not set in application.properties. It has a default of 10 "
                        + "at the @Value injection point, so removing it does not break anything "
                        + "today — but it moves the number out of the file this test reads and into "
                        + "an annotation nobody looks at.")
                .isTrue();
        assertThat(Integer.parseInt(m.group(1)))
                .as("app.room.max-users is the value handed to the Lua script, which is the ONLY "
                        + "point that actually refuses an 11th user. Changing it changes the "
                        + "product's behaviour; CONTRIBUTING.md lists it as a frozen surface.")
                .isEqualTo(CAP);
    }

    @Test
    @DisplayName("all three v1 room models refuse the 11th user — the copy that was unpinned")
    void everyRoomModelEnforcesTheSameCap() {
        // Every other capacity test exercises the Redis path, so a wrong MAX_USERS
        // constant on a model would not be caught anywhere else. These assertions call
        // the model directly so the constant itself is what fails here.
        List<String> wrong = new ArrayList<>();

        Room room = new Room();
        for (int i = 0; i < CAP; i++) {
            if (!room.canAddUser()) wrong.add("Room refused user " + (i + 1) + " of " + CAP);
            room.addUserName("u" + i, "name" + i);
        }
        if (room.canAddUser()) wrong.add("Room still accepts users at " + CAP);

        RetrospectiveBoard board = new RetrospectiveBoard();
        for (int i = 0; i < CAP; i++) {
            if (!board.canAddUser()) wrong.add("RetrospectiveBoard refused user " + (i + 1));
            board.addUser("u" + i, "name" + i);
        }
        if (board.canAddUser()) wrong.add("RetrospectiveBoard still accepts users at " + CAP);

        MoodRoom mood = new MoodRoom();
        for (int i = 0; i < CAP; i++) {
            if (!mood.canAddUser()) wrong.add("MoodRoom refused user " + (i + 1));
            mood.addUser("u" + i, "name" + i);
        }
        if (mood.canAddUser()) wrong.add("MoodRoom still accepts users at " + CAP);

        assertThat(wrong)
                .as("A v1 room model disagrees with the configured cap of %d. All three carry their "
                        + "own MAX_USERS constant, and none of the three was pinned by anything "
                        + "before this test: Room.MAX_USERS was raised to 1000 and the entire v1 "
                        + "suite stayed green.", CAP)
                .isEmpty();
    }

    @Test
    @DisplayName("the v1 UI shows the same number the server enforces")
    void frontendDisplaysTheSameCap() throws IOException {
        // The frontend is NOT an enforcement point — it renders the count and reacts to the
        // server's roomFull flag. But it is a PROMISE to the user, and a UI promising /10 while the
        // server allows 12 would be the product disagreeing with itself in front of the user.
        Map<String, Pattern> sites = Map.of(
                "script.js", Pattern.compile("\\$\\{userCount}/(\\d+)`"),
                "retro.js", Pattern.compile("\\$\\{count}/(\\d+)`"),
                "mood.js", Pattern.compile("max (\\d+) participants"));

        List<String> mismatches = new ArrayList<>();
        int sitesChecked = 0;

        for (Map.Entry<String, Pattern> e : sites.entrySet()) {
            Path f = V1.resolve(e.getKey());
            assertThat(f)
                    .as("%s does not exist — the v1 frontend moved and this check is reading "
                            + "nothing, which would report perfect agreement", e.getKey())
                    .isRegularFile();
            Matcher m = e.getValue().matcher(Files.readString(f, StandardCharsets.UTF_8));
            boolean found = false;
            while (m.find()) {
                found = true;
                sitesChecked++;
                int shown = Integer.parseInt(m.group(1));
                if (shown != CAP) {
                    mismatches.add(e.getKey() + " shows " + shown + ", server enforces " + CAP);
                }
            }
            if (!found) {
                mismatches.add(e.getKey() + ": the cap display was reworded — pattern /"
                        + e.getValue() + "/ matched nothing, so this file is no longer checked");
            }
        }

        // FLOOR. Four known sites across three files; a regex that stopped matching would report
        // perfect agreement over zero comparisons.
        assertThat(sitesChecked)
                .as("only %d cap displays were found across the v1 frontend — the sweep is vacuous",
                        sitesChecked)
                .isGreaterThanOrEqualTo(4);

        assertThat(mismatches)
                .as("The v1 UI shows a different capacity than the server enforces. The frontend "
                        + "does not gate joins — it renders the count and obeys the server's "
                        + "roomFull flag — so this is not a security hole. It is the product "
                        + "disagreeing with itself in front of the user, which is what "
                        + "'all three must agree' is actually asking for.")
                .isEmpty();
    }
}
