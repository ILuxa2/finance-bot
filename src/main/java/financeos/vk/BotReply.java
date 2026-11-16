package financeos.vk;

import financeos.core.Screen;

public record BotReply(String text, Screen screen, byte[] imagePng, java.util.List<MenuButton> buttons, java.util.List<byte[]> additionalImages) {
    public BotReply(String text, Screen screen, byte[] imagePng, java.util.List<MenuButton> buttons) { this(text,screen,imagePng,buttons,java.util.List.of()); }
    public BotReply(String text, Screen screen) { this(text, screen, null, null); }
    public BotReply(String text, Screen screen, byte[] imagePng) { this(text, screen, imagePng, null); }
    public BotReply {
        additionalImages = additionalImages==null?java.util.List.of():additionalImages.stream().map(byte[]::clone).toList();
        if (buttons != null) buttons = java.util.List.copyOf(buttons);
        if (imagePng != null) imagePng = imagePng.clone();
    }
    @Override public java.util.List<byte[]> additionalImages(){return additionalImages.stream().map(byte[]::clone).toList();}
    public java.util.List<byte[]> images(){var result=new java.util.ArrayList<byte[]>();if(imagePng!=null)result.add(imagePng());result.addAll(additionalImages());return java.util.List.copyOf(result);}
    @Override public byte[] imagePng() {
        return imagePng == null ? null : imagePng.clone();
    }
}
