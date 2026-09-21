package org.megamek.launcher.registry;

import org.megamek.launcher.onboarding.Product;

import java.util.List;

public record InstallationRecord(String id, String name, String canonicalRoot,
                                  String observedBuild, List<Product> products,
                                  String pin, boolean updateEligible,
                                  String registeredAt) {
    public InstallationRecord {
        products = products == null ? null : List.copyOf(products);
    }
}
