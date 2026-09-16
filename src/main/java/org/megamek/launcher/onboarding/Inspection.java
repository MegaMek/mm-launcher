package org.megamek.launcher.onboarding;

import java.util.List;

public record Inspection(String canonicalRoot, List<Product> products, String observedBuild,
                         String confidence) {
    public Inspection {
        products = List.copyOf(products);
    }
}
