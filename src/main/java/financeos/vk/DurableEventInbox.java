package financeos.vk;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Consumer;

/** Persists an authenticated VK event before acknowledging it. Failed jobs remain for replay. */
public final class DurableEventInbox {
    private final Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    public DurableEventInbox(Path directory) throws IOException {
        this.directory = directory.toAbsolutePath();
        Files.createDirectories(this.directory);
    }

    public synchronized boolean enqueue(VkEvent event) throws IOException {
        Path destination = directory.resolve(id(event) + ".json");
        if (Files.exists(destination)) return false;
        Path temporary = directory.resolve(".tmp-" + UUID.randomUUID());
        try {
            Files.writeString(temporary, mapper.writeValueAsString(event), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, destination);
            } catch (FileAlreadyExistsException duplicate) {
                return false;
            }
            return true;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public synchronized int drain(Consumer<VkEvent> process) throws IOException {
        int processed = 0;
        try (var files = Files.list(directory)) {
            for (Path path : files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(Path::toString)).toList()) {
                VkEvent event = mapper.readValue(Files.readString(path, StandardCharsets.UTF_8), VkEvent.class);
                process.accept(event);
                Files.delete(path);
                processed++;
            }
        }
        return processed;
    }

    public synchronized long pendingCount() throws IOException {
        try (var files = Files.list(directory)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".json")).count();
        }
    }

    private static String id(VkEvent event) {
        try {
            String key = event.type() + ":" + event.groupId() + ":"
                    + event.peerId() + ":" + event.eventId();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
