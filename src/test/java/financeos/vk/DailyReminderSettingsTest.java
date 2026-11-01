package financeos.vk;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

public final class DailyReminderSettingsTest {
    public static void main(String[] args) {
        var settings = DailyReminderSettings.fromRows(List.of(Map.of(
                "telegram_user_id", "7", "created_at", "2026-10-30T08:00:00+03:00",
                "updated_at", "2026-10-30T08:30:00+03:00",
                "daily_limit_reminder_enabled", "true",
                "daily_limit_reminder_time", "09:00",
                "timezone", "Europe/Moscow")), "7");
        if (!settings.enabled() || !settings.time().equals(LocalTime.of(9, 0))) {
            throw new AssertionError("Existing reminder setting was not imported");
        }
        var day = ZonedDateTime.of(2026, 10, 30, 9, 0, 0, 0, ZoneId.of("Europe/Moscow"));
        if (settings.due(day.minusMinutes(1), "") || !settings.due(day, "")
                || settings.due(day.plusHours(3), "")
                || settings.due(day, "2026-10-30")) {
            throw new AssertionError("Morning reminder was not sent exactly once after its time");
        }
        if (DailyReminderSettings.fromRows(List.of(), "7").enabled()) {
            throw new AssertionError("Missing setting enabled reminders");
        }
        System.out.println("Morning reminder setting checks passed.");
    }
}
