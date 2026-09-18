package org.megamek.launcher.onboarding;

import java.util.List;

public record Inspection(String canonicalRoot, List<Product> products, String observedBuild,
                         String confidence, RootIdentity rootIdentity) {
    public Inspection(String canonicalRoot, List<Product> products, String observedBuild,
                      String confidence) {
        this(canonicalRoot, products, observedBuild, confidence, null);
    }

    public Inspection {
        products = List.copyOf(products);
    }

    /** In-memory identity used only to detect a root replacement between quote and registration. */
    public record RootIdentity(Object fileKey, long creationMillis, long modifiedMillis) {
    }
}
