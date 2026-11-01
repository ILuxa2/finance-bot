package financeos.core;

import financeos.data.LegacyUserData;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

public final class BalanceService {
    public BigDecimal actualBalance(LegacyUserData data, OffsetDateTime asOf) {
        BigDecimal balance = data.startingBalance();
        for (var timed : data.transactions()) {
            if (!timed.timestamp().isAfter(data.snapshotTime()) || timed.timestamp().isAfter(asOf)) continue;
            var entry = timed.entry();
            if (entry.kind() == EntryKind.INCOME) balance = balance.add(entry.amount());
            if (entry.kind() == EntryKind.EXPENSE) balance = balance.subtract(entry.amount());
        }
        return balance;
    }
}
