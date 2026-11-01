package financeos.vk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import financeos.core.Screen;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class VkKeyboardFactory {
    private final ObjectMapper mapper = new ObjectMapper();

    public String keyboard(Screen screen) {
        List<List<Map<String, Object>>> rows = new ArrayList<>();
        List<Map<String, Object>> current = new ArrayList<>(2);
        for (MenuButton button : MenuCatalog.buttons(screen)) {
            current.add(Map.of("action", Map.of("type", "text", "label", button.label(),
                    "payload", "{\"cmd\":\"" + button.command() + "\"}"), "color", "primary"));
            if (current.size() == 2) {
                rows.add(List.copyOf(current));
                current.clear();
            }
        }
        if (!current.isEmpty()) rows.add(List.copyOf(current));
        if (rows.size() > 6) throw new IllegalStateException("VK inline menu has too many rows");
        try {
            return mapper.writeValueAsString(Map.of("inline", true, "one_time", false,
                    "buttons", rows));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Could not render VK menu", error);
        }
    }
}
