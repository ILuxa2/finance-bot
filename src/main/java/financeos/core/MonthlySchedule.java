package financeos.core;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

public final class MonthlySchedule {
    public List<LocalDate> occurrences(MonthlyRule rule, LocalDate from, LocalDate through) {
        if (from.isAfter(through)) throw new IllegalArgumentException("Invalid date range");
        List<LocalDate> dates = new ArrayList<>();
        YearMonth month = YearMonth.from(from);
        YearMonth last = YearMonth.from(through);
        while (!month.isAfter(last)) {
            LocalDate date = month.atDay(Math.min(rule.dayOfMonth(), month.lengthOfMonth()));
            if (!date.isBefore(from) && !date.isAfter(through)
                    && !date.isBefore(rule.startsOn())
                    && (rule.endsOn() == null || !date.isAfter(rule.endsOn()))) {
                dates.add(date);
            }
            month = month.plusMonths(1);
        }
        return List.copyOf(dates);
    }
}
