package financeos.vk;

import com.fasterxml.jackson.databind.ObjectMapper;

public final class VkConversationMembersClientTest {
    public static void main(String[] args) throws Exception {
        var mapper = new ObjectMapper();
        var privateChat = mapper.readTree("""
                {"count":2,"items":[{"member_id":564689957},{"member_id":-242685493}]}
                """);
        var thirdPerson = mapper.readTree("""
                {"count":3,"items":[{"member_id":564689957},{"member_id":-242685493},
                {"member_id":12345}]}
                """);
        var differentGroup = mapper.readTree("""
                {"count":2,"items":[{"member_id":564689957},{"member_id":-987654321}]}
                """);
        var duplicate = mapper.readTree("""
                {"count":2,"items":[{"member_id":564689957},{"member_id":564689957}]}
                """);
        if (!VkConversationMembersClient.onlyOwnerAndCommunity(
                privateChat, 564689957, 242685493)
                || VkConversationMembersClient.onlyOwnerAndCommunity(
                thirdPerson, 564689957, 242685493)
                || VkConversationMembersClient.onlyOwnerAndCommunity(
                differentGroup, 564689957, 242685493)
                || VkConversationMembersClient.onlyOwnerAndCommunity(
                duplicate, 564689957, 242685493)) {
            throw new AssertionError("Private VK chat guard failed");
        }
        var allowed=java.util.Set.of(564689957L,12345L);
        if(!VkConversationMembersClient.authorizedMembers(thirdPerson,564689957,242685493,allowed).equals(allowed)
           ||!VkConversationMembersClient.authorizedMembers(thirdPerson,564689957,242685493,java.util.Set.of(564689957L)).isEmpty()
           ||!VkConversationMembersClient.authorizedMembers(duplicate,564689957,242685493,allowed).isEmpty()
           ||!VkConversationMembersClient.authorizedMembers(differentGroup,564689957,242685493,allowed).isEmpty())throw new AssertionError("Shared membership scope failed");
        System.out.println("VK private chat membership checks passed.");
    }
}
