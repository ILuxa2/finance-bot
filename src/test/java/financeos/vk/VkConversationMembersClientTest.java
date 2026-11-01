package financeos.vk;

import com.fasterxml.jackson.databind.ObjectMapper;

public final class VkConversationMembersClientTest {
    public static void main(String[] args) throws Exception {
        var mapper = new ObjectMapper();
        var privateChat = mapper.readTree("""
                {"count":2,"items":[{"member_id":100000001},{"member_id":-100000002}]}
                """);
        var thirdPerson = mapper.readTree("""
                {"count":3,"items":[{"member_id":100000001},{"member_id":-100000002},
                {"member_id":12345}]}
                """);
        var differentGroup = mapper.readTree("""
                {"count":2,"items":[{"member_id":100000001},{"member_id":-987654321}]}
                """);
        var duplicate = mapper.readTree("""
                {"count":2,"items":[{"member_id":100000001},{"member_id":100000001}]}
                """);
        if (!VkConversationMembersClient.onlyOwnerAndCommunity(
                privateChat, 100000001, 100000002)
                || VkConversationMembersClient.onlyOwnerAndCommunity(
                thirdPerson, 100000001, 100000002)
                || VkConversationMembersClient.onlyOwnerAndCommunity(
                differentGroup, 100000001, 100000002)
                || VkConversationMembersClient.onlyOwnerAndCommunity(
                duplicate, 100000001, 100000002)) {
            throw new AssertionError("Private VK chat guard failed");
        }
        System.out.println("VK private chat membership checks passed.");
    }
}
