package me.sshcrack.mc_talking.manager;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeminiStreamTest {
    @Test
    void aTurnWithoutAudioDoesNotBlockTheNext() {
        // Seen in a playtest: the model only called tools, the turn ended silent, and the next turn
        // failed with "Cannot replace an audible turn that is still DRAINING".
        GeminiStream stream = new GeminiStream(null);
        UUID silent = UUID.randomUUID();
        stream.beginTurn(silent);
        assertTrue(stream.flushAudio(silent));
        assertDoesNotThrow(() -> stream.beginTurn(UUID.randomUUID()));
    }
}
