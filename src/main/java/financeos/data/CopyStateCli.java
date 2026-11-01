package financeos.data;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

/** Manual copy-only state transfer. Never overwrites a local file on download. */
public final class CopyStateCli {
    private CopyStateCli() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3 || (!"upload".equals(args[0]) && !"download".equals(args[0]))) {
            throw new IllegalArgumentException("Expected upload|download, key path, local state path");
        }
        var token = new GoogleServiceAccountToken(Path.of(args[1]),
                GoogleServiceAccountToken.Scope.READ_WRITE);
        var store = new GoogleSheetsCopyStateStore(URI.create("https://sheets.googleapis.com"),
                GoogleSheetsCopyProbe.TEST_COPY_ID, token);
        Path statePath = Path.of(args[2]);
        if ("upload".equals(args[0])) {
            byte[] state = Files.readAllBytes(statePath);
            store.save(state);
            Path marker = statePath.resolveSibling(statePath.getFileName() + ".copy-sync");
            Path temporary = marker.resolveSibling(marker.getFileName() + ".tmp-" + UUID.randomUUID());
            try {
                String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(state));
                Files.writeString(temporary, hash, StandardOpenOption.CREATE_NEW);
                try {
                    Files.move(temporary, marker, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, marker, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
            System.out.println("Local Java state saved and verified in the test copy.");
        } else {
            byte[] state = store.latest().orElseThrow(() -> new IOException("No Java state in test copy"));
            Files.write(statePath, state, StandardOpenOption.CREATE_NEW);
            System.out.println("Java state downloaded from the test copy to a new local file.");
        }
    }
}
