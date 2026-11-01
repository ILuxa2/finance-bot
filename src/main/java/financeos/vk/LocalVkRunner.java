package financeos.vk;

import financeos.data.LegacyDataMapper;
import financeos.data.LocalSheetBackupReader;
import financeos.data.GoogleServiceAccountToken;
import financeos.data.GoogleSheetsCopyProbe;
import financeos.data.GoogleSheetsCopyStateStore;
import financeos.core.RecurringBook;
import financeos.core.Screen;
import java.net.URI;
import java.net.http.HttpClient;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs the new VK bot on this computer against a local backup, without Google writes. */
public final class LocalVkRunner {
    private static final Path LOG_PATH = Path.of(".local-output", "logs", "vk-local.log");
    private static final int RESTART_EXIT_CODE = 75;
    private static SingleInstanceGuard stateLock;
    private LocalVkRunner() {}

    public static void main(String[] args) {
        int exitCode = 0;
        try {
            exitCode = run(args);
        } catch (Exception failed) {
            log("Бот остановлен: " + safeError(failed) + ".");
            System.err.println("Локальный VK-бот остановлен. Журнал: "
                    + LOG_PATH.toAbsolutePath());
            exitCode = 1;
        } finally {
            if (stateLock != null) {
                try { stateLock.close(); } catch (java.io.IOException ignored) { }
            }
        }
        if (exitCode != 0) System.exit(exitCode);
    }

    private static int run(String[] args) throws Exception {
        if (args.length != 8 && args.length != 9) {
            throw new IllegalArgumentException("Expected eight local VK arguments and optional test-copy key path");
        }
        Path backup = Path.of(args[0]);
        String token = Files.readString(Path.of(args[1]), StandardCharsets.UTF_8).strip();
        long groupId = Long.parseLong(args[2]);
        long vkUserId = Long.parseLong(args[3]);
        Path statePath = Path.of(args[4]);
        Path inboxPath = Path.of(args[5]);
        Path tsPath = Path.of(args[6]);
        long allowedChatPeerId = Long.parseLong(args[7]);
        if (allowedChatPeerId < 2_000_000_000L) {
            throw new IllegalArgumentException("Expected a VK chat peer ID");
        }
        Path reminderPath = statePath.resolveSibling("vk-" + vkUserId + "-reminded.txt");
        Path morningPath = statePath.resolveSibling("vk-" + vkUserId + "-morning.txt");
        if (token.isBlank() || token.contains("\n")) throw new IllegalArgumentException("Invalid VK token file");
        stateLock = SingleInstanceGuard.acquire(statePath);
        var sheets = new LocalSheetBackupReader().read(backup);
        var ids = new TreeSet<String>();
        for (var row : sheets.get("StartupState")) {
            String id = row.getOrDefault("telegram_user_id", "");
            if (!id.isBlank()) ids.add(id);
        }
        if (ids.size() != 1) throw new IllegalStateException("Expected one financial user");
        var reminderSettings = DailyReminderSettings.fromRows(sheets.get("UserSettings"), ids.first());
        var data = new LegacyDataMapper().user(sheets, ids.first());
        var stateFile = new LocalStateFile(statePath, vkUserId, data);
        CopyStateMirror mirror = null;
        if (args.length == 9) {
            var googleToken = new GoogleServiceAccountToken(Path.of(args[8]),
                    GoogleServiceAccountToken.Scope.READ_WRITE);
            var copyStore = new GoogleSheetsCopyStateStore(URI.create("https://sheets.googleapis.com"),
                    GoogleSheetsCopyProbe.TEST_COPY_ID, googleToken);
            Path markerPath = statePath.resolveSibling(statePath.getFileName() + ".copy-sync");
            mirror = new CopyStateMirror(statePath, markerPath, copyStore, LocalVkRunner::log);
            mirror.prepare();
        }
        var engine = new LocalBotEngine(vkUserId,
                Set.of(vkUserId, allowedChatPeerId), data,
                Clock.system(ZoneId.of("Europe/Moscow")));
        stateFile.load().ifPresent(engine::restoreState);
        if (mirror != null) mirror.start();
        var inbox = new DurableEventInbox(inboxPath);
        var http = HttpClient.newHttpClient();
        var poll = new VkGroupLongPollClient(http,
                URI.create("https://api.vk.com/method/groups.getLongPollServer"), token, groupId);
        var sender = new VkMessagesClient(URI.create("https://api.vk.com/method/messages.send"),
                token, http);
        var members = new VkConversationMembersClient(http,
                URI.create("https://api.vk.com/method/messages.getConversationMembers"),
                token, groupId);
        var notified = new LinkedHashSet<String>();
        if (Files.exists(reminderPath)) {
            notified.addAll(Files.readAllLines(reminderPath, StandardCharsets.UTF_8));
        }
        String lastMorning = Files.exists(morningPath)
                ? Files.readString(morningPath, StandardCharsets.UTF_8).strip() : "";
        log("FinanceOS Java слушает сообщения сообщества VK."
                + (mirror == null ? " Данные Google Sheets остаются без изменений."
                        : " Состояние сохраняется в тестовую копию Google Sheets.")
                + " Остановка: Ctrl+C.");
        drain(inbox, engine, stateFile, mirror, sender, members, vkUserId, allowedChatPeerId);
        remind(engine, sender, members, groupId, vkUserId, allowedChatPeerId,
                notified, reminderPath);
        lastMorning = morning(engine, sender, members, groupId, vkUserId,
                allowedChatPeerId,
                reminderSettings, lastMorning, morningPath);
        var server = poll.server();
        log("Соединение Long Poll с VK установлено.");
        var restartRequested = new AtomicBoolean();
        startRestartInput(restartRequested);
        String ts = Files.exists(tsPath)
                ? Files.readString(tsPath, StandardCharsets.UTF_8).strip() : server.ts();
        int connectionFailures = 0;
        long lastHeartbeat = System.nanoTime();
        while (true) {
            if (restartRequested.get()) {
                if (mirror != null) mirror.close();
                log("Перезапуск по команде из окна бота.");
                return RESTART_EXIT_CODE;
            }
            VkGroupLongPollClient.Batch batch;
            try {
                batch = poll.poll(server, ts);
                if (connectionFailures > 0) log("Связь с VK восстановлена.");
                connectionFailures = 0;
            } catch (VkGroupLongPollClient.ResetRequired reset) {
                try {
                    server = poll.server();
                    if (reset.reason() == 1) {
                        ts = server.ts();
                        saveTimestamp(tsPath, ts);
                    }
                    connectionFailures = 0;
                } catch (java.io.IOException unavailable) {
                    connectionFailures++;
                    Thread.sleep(5000);
                }
                continue;
            } catch (java.io.IOException unavailable) {
                connectionFailures++;
                if (connectionFailures == 1 || connectionFailures % 12 == 0) {
                    log("Связь с VK временно недоступна: " + safeError(unavailable)
                            + ". Повторяю подключение.");
                }
                Thread.sleep(5000);
                continue;
            }
            if (batch.events().isEmpty()
                    && System.nanoTime() - lastHeartbeat >= java.util.concurrent.TimeUnit.MINUTES.toNanos(1)) {
                log("Связь с VK активна; новых событий нет.");
                lastHeartbeat = System.nanoTime();
            }
            if (!batch.events().isEmpty()) {
                log("От VK получено событий: " + batch.events().size() + ".");
                lastHeartbeat = System.nanoTime();
            }
            for (var event : batch.events()) {
                inbox.enqueue(event);
            }
            saveTimestamp(tsPath, batch.nextTs());
            ts = batch.nextTs();
            drain(inbox, engine, stateFile, mirror, sender, members, vkUserId, allowedChatPeerId);
            remind(engine, sender, members, groupId, vkUserId, allowedChatPeerId,
                    notified, reminderPath);
            lastMorning = morning(engine, sender, members, groupId, vkUserId,
                    allowedChatPeerId,
                    reminderSettings, lastMorning, morningPath);
        }
    }

    private static void startRestartInput(AtomicBoolean requested) {
        startRestartInput(requested, System.in, System.out);
    }

    static Thread startRestartInput(AtomicBoolean requested, InputStream source, PrintStream output) {
        output.println("Управление: R и Enter — перезапустить бот в этом окне; Ctrl+C — остановить.");
        Thread input = new Thread(() -> {
            var reader = new BufferedReader(new InputStreamReader(source, StandardCharsets.UTF_8));
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.strip().equalsIgnoreCase("r")) {
                        requested.set(true);
                        output.println("Перезапуск запрошен. Ожидаю завершения текущего обращения к VK…");
                        return;
                    }
                    output.println("Для перезапуска введите R и нажмите Enter.");
                }
            } catch (java.io.IOException ignored) {
                // A detached console leaves VK polling available.
            }
        }, "financeos-console-control");
        input.setDaemon(true);
        input.start();
        return input;
    }

    private static String morning(LocalBotEngine engine, VkMessagesClient sender,
                                  VkConversationMembersClient members,
                                  long groupId, long vkUserId, long chatPeerId,
                                  DailyReminderSettings settings,
                                  String lastSent, Path path) throws Exception {
        var now = ZonedDateTime.now(settings.zone());
        if (!settings.due(now, lastSent)) return lastSent;
        String text = engine.morningLimitText();
        if (text.contains("не установлен")) return lastSent;
        if (!members.onlyOwnerAndCommunity(chatPeerId, vkUserId)) {
            log("Утреннее сообщение не отправлено: состав беседы не подтверждён.");
            return lastSent;
        }
        String date = now.toLocalDate().toString();
        var event = new VkEvent("message_new", groupId, vkUserId, chatPeerId,
                "morning-" + date, "");
        sender.send(event, new BotReply(text, Screen.LIMITS));
        saveTimestamp(path, date);
        return date;
    }

    private static void remind(LocalBotEngine engine, VkMessagesClient sender,
                               VkConversationMembersClient members,
                               long groupId, long vkUserId, long chatPeerId,
                               LinkedHashSet<String> notified,
                               Path reminderPath) throws Exception {
        var book = new RecurringBook();
        book.restore(engine.exportState().recurring());
        var now = ZonedDateTime.now(ZoneId.of("Europe/Moscow"));
        var due = new RecurringReminderService().toNotify(book, now.toLocalDate(),
                now.toLocalTime(), notified);
        if (!due.isEmpty() && !members.onlyOwnerAndCommunity(chatPeerId, vkUserId)) {
            log("Напоминание не отправлено: состав беседы не подтверждён.");
            return;
        }
        for (var item : due) {
            var rule = item.rule();
            String text = "⏳ Подтвердите регулярную операцию за " + item.date()
                    + "\n" + rule.category() + ": "
                    + rule.amount().setScale(2).toPlainString() + " ₽"
                    + "\nКод: " + item.code()
                    + "\nОткройте /regular_confirm и отправьте код."
                    + " Можно пропустить через /regular_skip.";
            var event = new VkEvent("message_new", groupId, vkUserId, chatPeerId,
                    "reminder-" + item.code(), "");
            sender.send(event, new BotReply(text, Screen.RECURRING));
            notified.add(item.code());
            saveTimestamp(reminderPath, String.join("\n", notified));
        }
    }

    private static void drain(DurableEventInbox inbox, LocalBotEngine engine,
                              LocalStateFile stateFile, CopyStateMirror mirror, VkMessagesClient sender,
                              VkConversationMembersClient members,
                              long vkUserId, long chatPeerId) throws Exception {
        try {
            inbox.drain(event -> {
                try {
                    if (event.userId() != vkUserId
                            || (event.peerId() != vkUserId && event.peerId() != chatPeerId)) {
                        log("Сообщение VK пропущено: разрешённый профиль " + vkUserId
                                + ", фактический профиль " + event.userId()
                                + ", ID диалога " + event.peerId() + ".");
                        return;
                    }
                    if (event.peerId() == chatPeerId
                            && !members.onlyOwnerAndCommunity(chatPeerId, vkUserId)) {
                        log("Сообщение VK пропущено: состав беседы не подтверждён."
                                + " ID диалога " + chatPeerId + ".");
                        return;
                    }
                    var reply = engine.handle(event);
                    if (reply.isPresent()) {
                        sender.send(event, reply.get());
                        log("Ответ отправлен в VK.");
                    } else if (event.userId() == vkUserId && event.peerId() == vkUserId) {
                        log("Событие VK уже обработано или не содержит команды.");
                    }
                    stateFile.save(engine.exportState());
                    if (mirror != null) mirror.signal();
                } catch (Exception failure) {
                    log("Не удалось обработать команду VK: " + safeError(failure) + ".");
                    throw new EventProcessingFailure(failure);
                }
            });
        } catch (EventProcessingFailure failure) {
            throw failure;
        }
    }

    private static void saveTimestamp(Path path, String ts) throws Exception {
        Files.createDirectories(path.toAbsolutePath().getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.writeString(temporary, ts, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static synchronized void log(String message) {
        String line = ZonedDateTime.now(ZoneId.of("Europe/Moscow")) + " " + message;
        System.out.println(line);
        try {
            Files.createDirectories(LOG_PATH.toAbsolutePath().getParent());
            Files.writeString(LOG_PATH, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (java.io.IOException unavailable) {
            System.err.println("Не удалось записать локальный журнал VK.");
        }
    }

    private static String safeError(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) cause = cause.getCause();
        String message = cause.getMessage();
        if ("Another local VK bot is already using this state".equals(message)) {
            return "другой локальный VK-бот уже запущен; закройте его перед запуском";
        }
        if ("Local and remote Java state differ; manual review required".equals(message)) {
            return "локальные данные и тестовая копия расходятся; нужна сверка";
        }
        if (message != null && message.matches("Google Sheets copy state operation failed with HTTP [0-9]+")) {
            return message;
        }
        if (message != null && message.matches("VK (API error|HTTP status|Long Poll setup error|member check error|member check HTTP) [0-9]+")) {
            return message;
        }
        return cause.getClass().getSimpleName();
    }

    private static final class EventProcessingFailure extends RuntimeException {
        EventProcessingFailure(Throwable cause) { super(cause); }
    }
}
