package org.megamek.launcher.onboarding;

import java.util.List;

public record Product(String key, String jar, String mainClass, String build, List<String> classPath) {
    public Product {
        classPath = List.copyOf(classPath);
    }
}
