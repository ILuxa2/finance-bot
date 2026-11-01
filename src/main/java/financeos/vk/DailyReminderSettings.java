package financeos.vk;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Existing user's reminder preference from the read-only UserSettings sheet. */
public record DailyReminderSettings(boolean enabled, LocalTime time, ZoneId zone) {
    public static DailyReminderSettings fromRows(List<Map<String, String>> rows, String userId) {
        var latest = rows.stream()
                .filter(row -> userId.equals(row.get("telegram_user_id")))
                .max(Comparator.comparing(DailyReminderSettings::modifiedAt));
        if (latest.isEmpty()) {
            return new DailyReminderSettings(false, LocalTime.of(9, 0), ZoneId.of("Europe/Moscow"));
        }
        var row = latest.get();
        String raw = row.getOrDefault("daily_limit_reminder_enabled", "").strip();
        boolean enabled = raw.equalsIgnoreCase("true") || raw.equals("1")
                || raw.equalsIgnoreCase("yes") || raw.equalsIgnoreCase("on");
        String timeText = row.getOrDefault("daily_limit_reminder_time", "").strip();
        LocalTime time = timeText.isBlank() ? LocalTime.of(9, 0) : LocalTime.parse(timeText);
        String zoneText = row.getOrDefault("timezone", "").strip();
        ZoneId zone = zoneText.isBlank() ? ZoneId.of("Europe/Moscow") : ZoneId.of(zoneText);
        return new DailyReminderSettings(enabled, time, zone);
    }

    public boolean due(ZonedDateTime now, String lastSentDate) {
        if (!enabled) return false;
        var local = now.withZoneSameInstant(zone);
        return !local.toLocalTime().isBefore(time)
                && local.toLocalTime().isBefore(time.plusHours(2))
                && !local.toLocalDate().toString().equals(lastSentDate);
    }

    private static OffsetDateTime modifiedAt(Map<String, String> row) {
        String updated = row.getOrDefault("updated_at", "").strip();
        return OffsetDateTime.parse(updated.isBlank() ? row.get("created_at") : updated);
    }
}
