package org.megamek.launcher.release;

import java.io.IOException;

public enum OfficialRepository {
    MEGAMEK("megamek", "MegaMek/megamek", "MegaMek-", "megamek"),
    LAB("lab", "MegaMek/megameklab", "MegaMekLab-", "lab"),
    MEKHQ("mekhq", "MegaMek/mekhq", "MekHQ-", "mekhq");

    private final String key;
    private final String slug;
    private final String assetPrefix;
    private final String requiredProduct;

    OfficialRepository(String key, String slug, String assetPrefix, String requiredProduct) {
        this.key = key;
        this.slug = slug;
        this.assetPrefix = assetPrefix;
        this.requiredProduct = requiredProduct;
    }

    public String key() { return key; }
    public String slug() { return slug; }
    public String assetPrefix() { return assetPrefix; }
    public String requiredProduct() { return requiredProduct; }

    public static OfficialRepository parse(String value) throws IOException {
        for (OfficialRepository repository : values()) {
            if (repository.key.equals(value)) return repository;
        }
        throw new IOException("application must be one of: megamek, mekhq, lab");
    }
}
