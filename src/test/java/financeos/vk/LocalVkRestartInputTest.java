package financeos.vk;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

public final class LocalVkRestartInputTest {
    public static void main(String[] args) throws Exception {
        var requested = new AtomicBoolean();
        var output = new ByteArrayOutputStream();
        var source = new ByteArrayInputStream("unknown\nR\n".getBytes(StandardCharsets.UTF_8));
        var input = LocalVkRunner.startRestartInput(requested, source,
                new PrintStream(output, true, StandardCharsets.UTF_8));
        input.join(1000);
        if (input.isAlive() || !requested.get()
                || !output.toString(StandardCharsets.UTF_8).contains("Перезапуск запрошен")) {
            throw new AssertionError("Console restart request was not accepted");
        }
        System.out.println("Local VK restart input passed.");
    }
}
