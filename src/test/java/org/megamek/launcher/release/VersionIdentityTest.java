package org.megamek.launcher.release;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionIdentityTest {
    @Test
    void normalizesOnlyNumericComponentsAndOptionalTagPrefix() {
        VersionIdentity observed = VersionIdentity.fromExact("0.50.7").orElseThrow();
        assertEquals(observed, VersionIdentity.fromExact("0.50.07").orElseThrow());
        assertEquals(observed, VersionIdentity.fromExact("v0.50.07").orElseThrow());
        assertEquals(observed,
                VersionIdentity.fromDisplay("MegaMek version 0.50.007").orElseThrow());
    }

    @Test
    void preservesSuffixAndComponentIdentity() {
        assertNotEquals(VersionIdentity.fromExact("0.50.7").orElseThrow(),
                VersionIdentity.fromExact("v0.50.7-beta").orElseThrow());
        assertNotEquals(VersionIdentity.fromExact("0.50.7+build1").orElseThrow(),
                VersionIdentity.fromExact("0.50.7+build2").orElseThrow());
        assertNotEquals(VersionIdentity.fromExact("0.50.7").orElseThrow(),
                VersionIdentity.fromExact("0.50.7.0").orElseThrow());
        assertTrue(VersionIdentity.fromDisplay("0.50.7 and 0.50.8").isEmpty());
    }
}
