package org.megamek.launcher.gui;

final class VersionDisplay {
    private VersionDisplay() {
    }

    static String programChannelVersion(String program, String channel, String version) {
        StringBuilder label = new StringBuilder(program);
        if (channel != null && !channel.isBlank()) {
            label.append(' ').append(channel);
        }
        if (version != null && !version.isBlank()) {
            label.append(" (").append(version).append(')');
        }
        return label.toString();
    }

    static String installLatest(String program, String channel, String version) {
        return "Install latest " + programChannelVersion(program, channel, version);
    }

    static String launch(String program, String channel, String version) {
        return "Launch " + programChannelVersion(program, channel, version);
    }
}
