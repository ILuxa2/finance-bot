package financeos.vk;
@FunctionalInterface public interface ReceiptReader {
 String read(VkEvent event)throws java.io.IOException,InterruptedException;
}
