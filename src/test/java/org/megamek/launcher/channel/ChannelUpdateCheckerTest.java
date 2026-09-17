package org.megamek.launcher.channel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChannelUpdateCheckerTest {
    @Test
    void ordersThreeAndFourComponentVersionsNumericallyWithoutLexicalMistakes() {
        assertEquals(ChannelUpdateChecker.Status.UPDATE_AVAILABLE,
                ChannelUpdateChecker.compareTags("v0.50.10", "v0.51.0"));
        assertEquals(ChannelUpdateChecker.Status.INSTALLED_AHEAD,
                ChannelUpdateChecker.compareTags("v0.51.00.1", "v0.51.0"));
        assertEquals(ChannelUpdateChecker.Status.UPDATE_AVAILABLE,
                ChannelUpdateChecker.compareTags("v0.9.99", "v0.10.0"));
        assertEquals(ChannelUpdateChecker.Status.EXACT_CURRENT,
                ChannelUpdateChecker.compareTags("v0.051.0", "v0.051.0"));
    }

    @Test
    void equalNumericDifferentIdentityAndSuffixesAreNotAssumedCurrentOrDowngraded() {
        assertEquals(ChannelUpdateChecker.Status.NON_COMPARABLE,
                ChannelUpdateChecker.compareTags("v0.51.0", "v0.51.0.0"));
        assertEquals(ChannelUpdateChecker.Status.NON_COMPARABLE,
                ChannelUpdateChecker.compareTags("v0.051.0", "v0.51.0"));
        assertEquals(ChannelUpdateChecker.Status.NON_COMPARABLE,
                ChannelUpdateChecker.compareTags("v0.51.0-beta", "v0.51.0"));
        assertEquals(ChannelUpdateChecker.Status.NON_COMPARABLE,
                ChannelUpdateChecker.compareTags("release-0.51.0", "v0.51.0"));
    }
}
