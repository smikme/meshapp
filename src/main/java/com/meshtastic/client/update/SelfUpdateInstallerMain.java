package com.meshtastic.client.update;

import java.nio.file.Path;

/**
 * Separate JVM entry point used to apply a staged self-update.
 */
public final class SelfUpdateInstallerMain {

    private SelfUpdateInstallerMain() {}

    public static void main(String[] args) {
        try {
            SelfUpdateInstaller.Request request = parse(args);
            new SelfUpdateInstaller().apply(request);
        } catch (Exception e) {
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static SelfUpdateInstaller.Request parse(String[] args) {
        Path root = null;
        Path archive = null;
        String targetVersion = null;
        String sha256 = null;
        long parentPid = 0;
        String launcher = null;
        SelfUpdateEnvironment.Layout layout = SelfUpdateEnvironment.Layout.MANAGED;

        for (int i = 0; args != null && i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--root" -> root = Path.of(next(args, ++i, arg));
                case "--archive" -> archive = Path.of(next(args, ++i, arg));
                case "--target-version" -> targetVersion = next(args, ++i, arg);
                case "--sha256" -> sha256 = next(args, ++i, arg);
                case "--parent-pid" -> parentPid = Long.parseLong(next(args, ++i, arg));
                case "--layout" -> layout = SelfUpdateEnvironment.Layout.fromId(next(args, ++i, arg));
                case "--launcher" -> launcher = next(args, ++i, arg);
                default -> throw new IllegalArgumentException("Unknown argument: " + arg);
            }
        }

        return new SelfUpdateInstaller.Request(
                root,
                archive,
                targetVersion,
                sha256,
                parentPid,
                launcher,
                layout
        );
    }

    private static String next(String[] args, int index, String option) {
        if (args == null || index >= args.length) {
            throw new IllegalArgumentException("Missing value for " + option);
        }
        return args[index];
    }
}
