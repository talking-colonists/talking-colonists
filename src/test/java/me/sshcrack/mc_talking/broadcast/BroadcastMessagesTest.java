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
    void callsForAttentionAnnounceNothing() {
        assertTrue(BroadcastMessages.isPlaceholder("Attention everyone, Steve has an announcement!"));
        assertTrue(BroadcastMessages.isPlaceholder("Attention everyone, a message from Steve!"));
        assertTrue(BroadcastMessages.isPlaceholder("Attention everyone! A message from Steve!"));
        assertTrue(BroadcastMessages.isPlaceholder("Hear ye, hear ye!"));
        assertTrue(BroadcastMessages.isPlaceholder("Steve has something to tell everyone."));
    }

    @Test
    void thePlayersWordsAre() {
        assertFalse(BroadcastMessages.isPlaceholder("The harvest fair starts at noon at the town hall."));
        assertFalse(BroadcastMessages.isPlaceholder("Everyone gets Sunday off!"));
        assertFalse(BroadcastMessages.isPlaceholder("Attention everyone: the market opens at dawn, a message from Steve."));
        assertFalse(BroadcastMessages.isPlaceholder("Hear ye! The bakery has news: bread is free today."));
    }
}
