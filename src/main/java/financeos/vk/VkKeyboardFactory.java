package financeos.vk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import financeos.core.Screen;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class VkKeyboardFactory {
    private final ObjectMapper mapper = new ObjectMapper();

    private String payload(String command) {
        try { return mapper.writeValueAsString(Map.of("cmd", command)); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    public String keyboard(Screen screen) {
        return keyboard(MenuCatalog.buttons(screen));
    }

    public String keyboard(List<MenuButton> buttons) {
        if (buttons.size() > 10) throw new IllegalArgumentException("Inline keyboard exceeds 10 buttons");
        List<List<Map<String, Object>>> rows = new ArrayList<>();
        var footer = new ArrayList<MenuButton>();
        buttons.stream().filter(b -> b.command().equals("/back")).findFirst().ifPresent(footer::add);
        buttons.stream().filter(b -> b.command().equals("/start")).findFirst().ifPresent(footer::add);
        boolean mainMenu = buttons.stream().anyMatch(b -> b.command().equals("/receipt"))
                && buttons.stream().anyMatch(b -> b.command().equals("/regular_pending"));
        var content = buttons.stream().filter(b -> !b.command().equals("/back") && !b.command().equals("/start")).toList();
        Map<String, Object> pendingShort = null;
        boolean compactChoices = buttons.stream().filter(b -> b.command().startsWith("/choose/")).count() > 3;
        boolean choiceBlock = false;
        for (MenuButton button : content) {
            boolean isChoice = button.command().startsWith("/choose/");
            if (compactChoices && choiceBlock && !isChoice && pendingShort != null) {
                rows.add(List.of(pendingShort));
                pendingShort = null;
            }
            choiceBlock = isChoice;
            int length = button.label().codePointCount(0, button.label().length());
            if (length > 40) throw new IllegalArgumentException("VK button label exceeds 40 characters");
            Map<String, Object> rendered = Map.of("action", Map.of("type", "text", "label", button.label(),
                    "payload", payload(button.command())), "color", "primary");
            if ((!mainMenu && length > (isChoice ? 12 : 14)) || (isChoice && !compactChoices)) {
                // Flush before a wide button so visual order matches the numbered list.
                if(pendingShort!=null){rows.add(List.of(pendingShort));pendingShort=null;}
                rows.add(List.of(rendered));
            } else if (pendingShort == null) {
                pendingShort = rendered;
            } else {
                rows.add(List.of(pendingShort, rendered));
                pendingShort = null;
            }
        }
        if (pendingShort != null) rows.add(List.of(pendingShort));
        if (!footer.isEmpty()) rows.add(footer.stream().map(button -> Map.<String, Object>of(
                "action", Map.of("type", "text", "label", button.label(), "payload", payload(button.command())),
                "color", "secondary")).toList());
        if (rows.size() > 6) throw new IllegalStateException("VK inline menu has too many rows");
        try {
            return mapper.writeValueAsString(Map.of("inline", true, "one_time", false,
                    "buttons", rows));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Could not render VK menu", error);
        }
    }
}
