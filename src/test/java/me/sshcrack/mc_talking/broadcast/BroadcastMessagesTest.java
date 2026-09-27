package me.sshcrack.mc_talking.broadcast;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BroadcastMessagesTest {
    @Test
    void templatesAreNotAnnounced() {
        assertTrue(BroadcastMessages.isPlaceholder("Change this message to whatever broadcast you want to make"));
        assertTrue(BroadcastMessages.isPlaceholder("<message>"));
        assertTrue(BroadcastMessages.isPlaceholder("Your message here"));
        assertTrue(BroadcastMessages.isPlaceholder("  "));
    }

    @Test
    void thePlayersWordsAre() {
        assertFalse(BroadcastMessages.isPlaceholder("The harvest fair starts at noon at the town hall."));
        assertFalse(BroadcastMessages.isPlaceholder("Everyone gets Sunday off!"));
    }
}
