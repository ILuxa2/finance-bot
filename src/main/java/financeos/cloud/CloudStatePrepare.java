package financeos.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import financeos.data.LegacyDataMapper;
import financeos.data.LocalSheetBackupReader;
import financeos.vk.LocalStateFile;
import financeos.vk.SingleInstanceGuard;
import financeos.vk.VkHistoryClient;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.TreeSet;

/** Creates private cutover files; refuses to run while the local bot owns its state. */
public final class CloudStatePrepare {
    private CloudStatePrepare() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 7) {
            throw new IllegalArgumentException("Expected backup, local state, VK user ID, output directory, token file, chat peer ID and group ID");
        }
        Path backup = Path.of(args[0]).toAbsolutePath();
        Path state = Path.of(args[1]).toAbsolutePath();
        long ownerId = Long.parseLong(args[2]);
        Path output = Path.of(args[3]).toAbsolutePath();
        Path tokenFile = Path.of(args[4]).toAbsolutePath();
        long chatPeerId = Long.parseLong(args[5]);
        long groupId = Long.parseLong(args[6]);
        if (!Files.isRegularFile(backup) || !Files.isRegularFile(state)) {
            throw new IOException("Backup or local state file is missing");
        }
        if (!Files.isRegularFile(tokenFile) || chatPeerId <= 0 || groupId <= 0) {
            throw new IOException("VK token file or chat peer ID is missing");
        }
        if (Files.exists(output)) throw new IOException("Output directory already exists");
        try (var ignored = SingleInstanceGuard.acquire(state)) {
            Path inbox = state.resolveSibling("vk-" + ownerId + "-inbox");
            if (Files.isDirectory(inbox)) {
                try (var files = Files.list(inbox)) {
                    if (files.anyMatch(path -> path.getFileName().toString().endsWith(".json"))) {
                        throw new IOException("Unprocessed local VK events remain in the inbox");
                    }
                }
            }
            var sheets = new LocalSheetBackupReader().read(backup);
            var legacyIds = new TreeSet<String>();
            for (var row : sheets.get("StartupState")) {
                String id = row.getOrDefault("telegram_user_id", "");
                if (!id.isBlank()) legacyIds.add(id);
            }
            if (legacyIds.size() != 1) throw new IOException("Expected one financial user");
            var base = new LegacyDataMapper().user(sheets, legacyIds.first());
            if (new LocalStateFile(state, ownerId, base).load().isEmpty()) {
                throw new IOException("Local bot state could not be validated");
            }
            byte[] stateBytes = Files.readAllBytes(state);
            byte[] backupBytes = Files.readAllBytes(backup);
            String vkToken = Files.readString(tokenFile, StandardCharsets.UTF_8).strip();
            long checkpoint = new VkHistoryClient(HttpClient.newHttpClient(),
                    URI.create("https://api.vk.com/method/messages.getHistory"),
                    vkToken, groupId).latestConversationMessageId(chatPeerId);
            if (checkpoint <= 0) throw new IOException("VK chat history has no checkpoint");
            var envelope = new CloudVkProcessor.Envelope(1,
                    Base64.getEncoder().encodeToString(stateBytes), new LinkedHashMap<>(),
                    new java.util.LinkedHashSet<>(), "", checkpoint);
            byte[] envelopeBytes = new ObjectMapper().writeValueAsBytes(envelope);
            Files.createDirectories(output);
            Files.write(output.resolve("base.json"), backupBytes, StandardOpenOption.CREATE_NEW);
            Files.write(output.resolve("state.json"), envelopeBytes, StandardOpenOption.CREATE_NEW);
            Files.writeString(output.resolve("README.txt"),
                    "Private financial cutover files. Upload only to the dedicated private bucket.\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            System.out.println("Cloud cutover files prepared. Contents contain private financial data.");
        }
    }
}
