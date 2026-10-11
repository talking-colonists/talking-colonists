package me.sshcrack.mc_talking.onboarding;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntroductionTextsTest {
    @Test
    void theLineIsSaidOnceAndNotWrittenToMemoryByTheModel() {
        String welcome = IntroductionTexts.directive("Dev", true, "Welcome them warmly.", false);
        assertTrue(welcome.contains("handed them a Colony Handbook"));
        assertTrue(welcome.endsWith("Do not call any functions for this."));
        assertFalse(IntroductionTexts.directive("Dev", false, "Tell them about the notice board.", true).contains("handed them"));
    }

    @Test
    void theCitizenRemembersItIsDone() {
        assertTrue(IntroductionTexts.memory("Dev", true, "").contains("don't welcome them again"));
        String topic = IntroductionTexts.memory("Dev", false, "the notice board");
        assertTrue(topic.startsWith("I told Dev once about the notice board."));
        assertTrue(topic.contains("not something to tell stories about"));
    }
}
