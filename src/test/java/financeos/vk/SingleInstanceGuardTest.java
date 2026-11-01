package financeos.vk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class SingleInstanceGuardTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory(Path.of(".local-state"), "single-instance-test-");
        Path state = root.resolve("state.json");
        Path lockPath = root.resolve("state.json.running.lock");
        try {
            try (var first = SingleInstanceGuard.acquire(state)) {
                try {
                    SingleInstanceGuard.acquire(state);
                    throw new AssertionError("Second local bot was allowed to start");
                } catch (IOException expected) {
                    if (!expected.getMessage().contains("already using")) throw expected;
                }
            }
            try (var afterClose = SingleInstanceGuard.acquire(state)) {
                if (afterClose == null) throw new AssertionError("Lock was not released");
            }
        } finally {
            Files.deleteIfExists(lockPath);
            Files.deleteIfExists(root);
        }
        System.out.println("Single local VK instance guard checks passed.");
    }
}
