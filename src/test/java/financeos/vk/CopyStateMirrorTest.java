package financeos.vk;

import financeos.data.StateSnapshotStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public final class CopyStateMirrorTest {
    private static final class FakeRemote implements StateSnapshotStore {
        byte[] bytes;
        int saves;

        @Override public Optional<byte[]> latest() {
            return Optional.ofNullable(bytes);
        }

        @Override public void save(byte[] stateJson) {
            bytes = stateJson.clone();
            saves++;
        }
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory(Path.of(".local-state"), "copy-mirror-test-");
        Path state = root.resolve("state.json");
        Path marker = root.resolve("marker.txt");
        try {
            var remote = new FakeRemote();
            Files.write(state, bytes("first"));
            try (var mirror = new CopyStateMirror(state, marker, remote, ignored -> {})) {
                mirror.prepare();
                if (remote.saves != 1 || !same(remote.bytes, "first")) {
                    throw new AssertionError("Initial local state was not copied");
                }
                Files.write(state, bytes("second"));
                if (!mirror.syncOnce() || !same(remote.bytes, "second")) {
                    throw new AssertionError("Changed local state was not copied");
                }
            }
            Files.write(state, bytes("third"));
            try (var restarted = new CopyStateMirror(state, marker, remote, ignored -> {})) {
                restarted.prepare();
                if (!same(remote.bytes, "third")) {
                    throw new AssertionError("Pending local change was not recovered on restart");
                }
                remote.bytes = bytes("changed elsewhere");
                Files.write(state, bytes("fourth"));
                try {
                    restarted.syncOnce();
                    throw new AssertionError("Remote conflict was overwritten");
                } catch (IOException expected) {
                    if (!same(remote.bytes, "changed elsewhere")) {
                        throw new AssertionError("Remote conflict was changed");
                    }
                }
            }
            Files.delete(state);
            Files.delete(marker);
            remote.bytes = bytes("restored");
            try (var restored = new CopyStateMirror(state, marker, remote, ignored -> {})) {
                restored.prepare();
                if (!same(Files.readAllBytes(state), "restored")) {
                    throw new AssertionError("Remote state was not restored locally");
                }
            }
        } finally {
            Files.deleteIfExists(state);
            Files.deleteIfExists(marker);
            Files.deleteIfExists(root);
        }
        System.out.println("Copy state mirror recovery and conflict checks passed.");
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static boolean same(byte[] actual, String expected) {
        return java.util.Arrays.equals(actual, bytes(expected));
    }
}
