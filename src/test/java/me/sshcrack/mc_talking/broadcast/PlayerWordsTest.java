package me.sshcrack.mc_talking.broadcast;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerWordsTest {
    private final UUID citizen = UUID.randomUUID();

    @AfterEach
    void forget() {
        PlayerWords.clear();
    }

    @Test
    void speechInProgressCountsBeforeTheTurnEnds() {
        PlayerWords.record(citizen, "Hello there.", 0);
        PlayerWords.hearing(citizen, " Please announce: the market");
        PlayerWords.hearing(citizen, " opens at dawn.");
        assertEquals("Hello there.\nPlease announce: the market opens at dawn.", PlayerWords.recent(citizen, 1));
        PlayerWords.record(citizen, "Please announce: the market opens at dawn.", 2);
        assertEquals("Hello there.\nPlease announce: the market opens at dawn.", PlayerWords.recent(citizen, 3));
    }

    @Test
    void oldWordsAreForgotten() {
        PlayerWords.record(citizen, "Tell everyone the fair is on Sunday.", 0);
        assertEquals("", PlayerWords.recent(citizen, PlayerWords.KEEP_MILLIS + 1));
    }

    @Test
    void anAnnouncementMustComeFromThePlayersWords() {
        String asked = "Please announce to the whole colony: the market opens tomorrow at dawn, everyone bring spare wheat.";
        assertTrue(BroadcastMessages.isFromPlayer("The market opens tomorrow at dawn! Bring your spare wheat.", asked));
        assertTrue(BroadcastMessages.isFromPlayer("Market at dawn tomorrow, bring wheat", asked));
        // Seen in the automated playtest: the player only asked how to tell everyone.
        String howTo = "How can I tell everyone in the colony something? Can you do it for me?";
        assertFalse(BroadcastMessages.isFromPlayer("Player Dev wants to let everyone know that the colony is running low on "
                + "building supplies and we need to gather more resources soon!", howTo));
    }
}
