package financeos.data;

import financeos.vk.CopyStateMirror;
import financeos.vk.LocalStateFile;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeSet;

/** Checks remote-to-local restoration in an isolated temporary directory. */
public final class VerifyCopyRestore {
    private VerifyCopyRestore() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("Expected backup path, key path, VK user ID");
        }
        Path backup = Path.of(args[0]);
        long vkUserId = Long.parseLong(args[2]);
        var sheets = new LocalSheetBackupReader().read(backup);
        var legacyIds = new TreeSet<String>();
        for (var row : sheets.get("StartupState")) {
            String id = row.getOrDefault("telegram_user_id", "");
            if (!id.isBlank()) legacyIds.add(id);
        }
        if (legacyIds.size() != 1) throw new IllegalStateException("Expected one financial user");
        var data = new LegacyDataMapper().user(sheets, legacyIds.first());
        var token = new GoogleServiceAccountToken(Path.of(args[1]),
                GoogleServiceAccountToken.Scope.READ_WRITE);
        var store = new GoogleSheetsCopyStateStore(URI.create("https://sheets.googleapis.com"),
                GoogleSheetsCopyProbe.TEST_COPY_ID, token);
        Path root = Path.of(".local-output").toAbsolutePath();
        Files.createDirectories(root);
        Path temporary = Files.createTempDirectory(root, "restore-check-");
        Path statePath = temporary.resolve("state.json");
        Path markerPath = temporary.resolve("state.json.copy-sync");
        try {
            try (var mirror = new CopyStateMirror(statePath, markerPath, store, ignored -> {})) {
                mirror.prepare();
            }
            if (new LocalStateFile(statePath, vkUserId, data).load().isEmpty()) {
                throw new IllegalStateException("No state restored from test copy");
            }
            System.out.println("Test-copy state restored and validated against the original backup.");
        } finally {
            Files.deleteIfExists(statePath);
            Files.deleteIfExists(markerPath);
            Files.deleteIfExists(temporary);
        }
    }
}
