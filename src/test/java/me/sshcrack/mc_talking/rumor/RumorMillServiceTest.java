package me.sshcrack.mc_talking.rumor;

import me.sshcrack.mc_talking.api.memory.AddonConfirmedOutcome;
import me.sshcrack.mc_talking.conversations.memory.data.CitizenMemories;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RumorMillServiceTest {
    @Test
    void sleepingCitizensOnlyShareWithHousematesAndNeverAloud() {
        assertEquals(RumorMillService.PairMode.AWAKE, RumorMillService.pairMode(false, false, false, true));
        assertEquals(RumorMillService.PairMode.SKIP, RumorMillService.pairMode(false, false, true, false));
        assertEquals(RumorMillService.PairMode.PILLOW_TALK, RumorMillService.pairMode(true, true, true, false),
                "housemates share at night even when their beds are apart");
        assertEquals(RumorMillService.PairMode.PILLOW_TALK, RumorMillService.pairMode(true, false, true, true),
                "never voiced while one of them sleeps");
        assertEquals(RumorMillService.PairMode.SKIP, RumorMillService.pairMode(true, false, false, true),
                "a sleeper next to a stranger shares nothing");
    }

    @Test
    void consumesFirstHandEventWithoutMutatingItsSnapshotOrLeavingProvenance() {
        var memory = new CitizenMemories();
        memory.addEvent("I finished the bakery.");
        var originalEvents = memory.getEvents();
        var pendingCompaction = memory.snapshotCompaction();

        assertEquals("I finished the bakery.", RumorMillService.takeFirstHandEvent(memory));
        assertEquals(List.of(), memory.getEvents());
        assertEquals(List.of(), memory.getEntries());
        assertEquals(List.of("I finished the bakery."), originalEvents);
        assertThrows(UnsupportedOperationException.class, () -> originalEvents.remove(0));
        assertNull(RumorMillService.takeFirstHandEvent(memory));
        assertFalse(memory.applyCompaction(pendingCompaction, "Old event summary"));
    }

    @Test
    void keepsSecondhandEventsAndUnrelatedFacts() {
        var memory = new CitizenMemories();
        memory.addFact("I am a baker.");
        memory.addEvent("Rumor: The mine flooded.");
        memory.addEvent("I delivered bread.");
        assertEquals("I delivered bread.", RumorMillService.takeFirstHandEvent(memory));
        assertEquals(List.of("Rumor: The mine flooded."), memory.getEvents());
        assertEquals(List.of("I am a baker."), memory.getFacts());
        assertEquals(2, memory.getEntries().size());
        assertNull(RumorMillService.takeFirstHandEvent(memory));
    }

    @Test
    void anIntroductionIsNotGossip() {
        var memory = new CitizenMemories();
        memory.addConfirmedOutcome(new AddonConfirmedOutcome("mc_talking", CitizenMemories.PRIVATE_OUTCOME_PREFIX + "tc_gazette:newspaper",
                "I told Dev about the Colony Gazette.", null, List.of(), List.of()));
        assertNull(RumorMillService.takeFirstHandEvent(memory));
        assertEquals(List.of("I told Dev about the Colony Gazette."), memory.getEvents(), "still remembered, just not passed on");
    }

    @Test
    void emptyMemoryHasNoEventToPromote() {
        assertNull(RumorMillService.takeFirstHandEvent(new CitizenMemories()));
    }
}
