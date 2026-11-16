package financeos.vk;

import com.fasterxml.jackson.databind.ObjectMapper;
import financeos.core.Screen;

public final class MenuCatalogTest {
    public static void main(String[] args) throws Exception {
        if(MenuCatalog.commandHelp().length()>4096)throw new AssertionError("Help no longer fits one message");
        var mapper = new ObjectMapper();
        var factory = new VkKeyboardFactory();
        for (Screen screen : Screen.values()) {
            var buttons = MenuCatalog.buttons(screen);
            if (MenuCatalog.hasBack(screen) && buttons.stream().noneMatch(b -> b.command().equals("/back"))) {
                throw new AssertionError("Missing Back button on " + screen);
            }
            var rendered = mapper.readTree(factory.keyboard(screen));
            var rows = rendered.path("buttons");
            int count = 0;
            for (var row : rows) {
                if (row.size() > 2) throw new AssertionError("Too many buttons in a row");
                for (var button : row) {
                    String label = button.path("action").path("label").asText();
                    if (screen != Screen.MAIN && !label.equals("🏠 Меню") && label.codePointCount(0, label.length()) > 14 && row.size() != 1) {
                        throw new AssertionError("Long label shares its row on " + screen);
                    }
                }
                count += row.size();
            }
            if (MenuCatalog.hasBack(screen)) {
                var footer = rows.get(rows.size()-1);
                if (!footer.get(0).path("action").path("label").asText().equals("⬅️ Назад")
                        || footer.size()!=2 || !footer.get(1).path("action").path("label").asText().equals("🏠 Меню"))
                    throw new AssertionError("Back footer moved on " + screen);
            }
            if (rows.size() > 6 || count > 10 || count != buttons.size()) {
                throw new AssertionError("VK keyboard lost a button on " + screen);
            }
        }
        System.out.println("VK menu checks passed.");
    }
}
