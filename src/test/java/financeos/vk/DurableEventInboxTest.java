package financeos.vk;

import java.nio.file.Files;
import java.util.ArrayList;

public final class DurableEventInboxTest {
    public static void main(String[] args) throws Exception {
        var directory = java.nio.file.Path.of(System.getProperty("user.dir"), "target",
                "vk-inbox-test-" + java.util.UUID.randomUUID());
        Files.createDirectories(directory);
        var event = new VkEvent("message_new", 99, 123, 123, "event-7", "/report");
        var first = new DurableEventInbox(directory);
        if (!first.enqueue(event) || first.enqueue(event) || first.pendingCount() != 1) {
            throw new AssertionError("Inbox did not deduplicate the pending event");
        }
        try {
            first.drain(ignored -> { throw new IllegalStateException("temporary failure"); });
            throw new AssertionError("The temporary processing failure was hidden");
        } catch (IllegalStateException expected) {
            if (first.pendingCount() != 1) throw new AssertionError("Failed event was lost");
        }
        var reopened = new DurableEventInbox(directory);
        var accepted = new ArrayList<VkEvent>();
        if (reopened.drain(accepted::add) != 1 || accepted.size() != 1
                || !accepted.getFirst().text().equals("/report")
                || reopened.pendingCount() != 0) {
            throw new AssertionError("Queued event was not replayed after reopening");
        }
        System.out.println("Durable VK inbox checks passed.");
    }
}
