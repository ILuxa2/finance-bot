package financeos.vk;

import financeos.core.Screen;

public record BotReply(String text, Screen screen, byte[] imagePng) {
    public BotReply(String text, Screen screen) { this(text, screen, null); }
    public BotReply {
        if (imagePng != null) imagePng = imagePng.clone();
    }
    @Override public byte[] imagePng() {
        return imagePng == null ? null : imagePng.clone();
    }
}
