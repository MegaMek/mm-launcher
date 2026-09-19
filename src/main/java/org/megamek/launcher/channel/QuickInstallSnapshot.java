package org.megamek.launcher.channel;

import org.megamek.launcher.release.OfficialRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable, complete six-choice first-launch catalog snapshot. */
public final class QuickInstallSnapshot {
    public static final QuickInstallOption.Key DEFAULT_KEY =
            new QuickInstallOption.Key(OfficialRepository.MEKHQ, FollowChannel.MILESTONE);
    public static final List<QuickInstallOption.Key> MENU_KEYS = List.of(
            new QuickInstallOption.Key(OfficialRepository.MEGAMEK, FollowChannel.MILESTONE),
            new QuickInstallOption.Key(OfficialRepository.LAB, FollowChannel.MILESTONE),
            new QuickInstallOption.Key(OfficialRepository.MEKHQ, FollowChannel.DEVELOPMENT),
            new QuickInstallOption.Key(OfficialRepository.MEGAMEK, FollowChannel.DEVELOPMENT),
            new QuickInstallOption.Key(OfficialRepository.LAB, FollowChannel.DEVELOPMENT));
    public static final List<QuickInstallOption.Key> ALL_KEYS = List.of(
            DEFAULT_KEY,
            MENU_KEYS.get(0), MENU_KEYS.get(1), MENU_KEYS.get(2), MENU_KEYS.get(3),
            MENU_KEYS.get(4));

    private final List<QuickInstallOption> options;
    private final Map<QuickInstallOption.Key, QuickInstallOption> byKey;

    public QuickInstallSnapshot(List<QuickInstallOption> options) {
        Objects.requireNonNull(options, "options");
        LinkedHashMap<QuickInstallOption.Key, QuickInstallOption> indexed =
                new LinkedHashMap<>();
        for (QuickInstallOption option : options) {
            Objects.requireNonNull(option, "quick-install option");
            if (indexed.putIfAbsent(option.key(), option) != null) {
                throw new IllegalArgumentException(
                        "duplicate quick-install option: " + option.key());
            }
        }
        if (!List.copyOf(indexed.keySet()).equals(ALL_KEYS)) {
            throw new IllegalArgumentException(
                    "quick-install snapshot must contain all six choices in fixed order");
        }
        this.options = List.copyOf(indexed.values());
        this.byKey = Map.copyOf(indexed);
    }

    public List<QuickInstallOption> options() {
        return options;
    }

    public Map<QuickInstallOption.Key, QuickInstallOption> byKey() {
        return byKey;
    }

    public QuickInstallOption option(QuickInstallOption.Key key) {
        QuickInstallOption option = byKey.get(key);
        if (option == null) {
            throw new IllegalArgumentException("unknown quick-install choice: " + key);
        }
        return option;
    }
}
