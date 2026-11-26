package financeos.vk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import financeos.core.BudgetPlan;
import financeos.core.Debt;
import financeos.core.EntryKind;
import financeos.core.Goal;
import financeos.core.LedgerEntry;
import financeos.core.Money;
import financeos.core.MonthlyRule;
import financeos.core.Navigation;
import financeos.core.RecurringBook;
import financeos.core.Screen;
import financeos.data.LegacyUserData;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Private local trial state. Refuses to mix it with a changed Google Sheets snapshot. */
public final class LocalStateFile {
    private final Path path;
    private final long vkUserId;
    private final String baseFingerprint;
    private final ObjectMapper mapper = new ObjectMapper();

    public LocalStateFile(Path path, long vkUserId, LegacyUserData original) {
        this.path = path.toAbsolutePath();
        this.vkUserId = vkUserId;
        this.baseFingerprint = fingerprint(original);
    }

    public Optional<BotLocalState> load() throws IOException {
        if (!Files.exists(path)) return Optional.empty();
        try {
            JsonNode root = mapper.readTree(Files.readString(path, StandardCharsets.UTF_8));
            int version = root.path("version").asInt();
            if ((version < 1 || version > 13)
                    || root.path("vkUserId").asLong() != vkUserId
                    || !baseFingerprint.equals(root.path("baseFingerprint").asText())) {
                throw new IOException("Локальная копия относится к другим исходным данным."
                        + " Сохраните её отдельно перед новым запуском.");
            }
            requireArray(root, "entries");
            requireArray(root, "plans");
            requireArray(root, "debts");
            requireArray(root, "goals");
            requireArray(root, "seenEvents");
            if (!root.path("limits").isObject()) throw new IOException("Invalid limits in local state");
            var entries = new ArrayList<LegacyUserData.TimedEntry>();
            for (var item : root.path("entries")) {
                var entry = new LedgerEntry(text(item, "id"),
                        LocalDate.parse(text(item, "date")),
                        EntryKind.valueOf(text(item, "kind")), Money.of(text(item, "amount")),
                        text(item, "category"), text(item, "description"),
                        item.path("planned").asBoolean(false));
                entries.add(new LegacyUserData.TimedEntry(
                        OffsetDateTime.parse(text(item, "timestamp")), entry));
            }
            var plans = new ArrayList<BudgetPlan>();
            for (var item : root.path("plans")) {
                plans.add(new BudgetPlan(YearMonth.parse(text(item, "month")),
                        text(item, "category"), Money.of(text(item, "amount"))));
            }
            var debts = new ArrayList<Debt>();
            for (var item : root.path("debts")) {
                debts.add(new Debt(text(item, "name"), Money.of(text(item, "remaining")),
                        Money.of(text(item, "monthlyPayment")), item.path("paymentDay").asInt(),
                        item.path("active").asBoolean()));
            }
            var goals = new ArrayList<Goal>();
            for (var item : root.path("goals")) {
                goals.add(new Goal(text(item, "name"), Money.of(text(item, "target")),
                        Money.of(text(item, "current")), Money.of(text(item, "monthlyTarget")),
                        item.path("active").asBoolean()));
            }
            Map<YearMonth, BigDecimal> limits = new HashMap<>();
            root.path("limits").fields().forEachRemaining(field ->
                    limits.put(YearMonth.parse(field.getKey()), Money.of(field.getValue().asText())));
            var seen = new ArrayList<String>();
            for (var item : root.path("seenEvents")) seen.add(item.asText());
            RecurringBook.State recurring = new RecurringBook().state();
            if (version >= 2) {
                requireArray(root, "rules");
                if (!root.path("decisions").isObject()) {
                    throw new IOException("Invalid recurring decisions in local state");
                }
                var rules = new ArrayList<MonthlyRule>();
                for (var item : root.path("rules")) {
                    String end = item.path("endsOn").asText("");
                    rules.add(new MonthlyRule(text(item, "id"),
                            EntryKind.valueOf(text(item, "kind")),
                            Money.of(text(item, "amount")), item.path("dayOfMonth").asInt(),
                            LocalDate.parse(text(item, "startsOn")),
                            end.isBlank() ? null : LocalDate.parse(end),
                            text(item, "category"), text(item, "description"),
                            item.path("requiresConfirmation").asBoolean()));
                }
                Map<String, Boolean> decisions = new HashMap<>();
                root.path("decisions").fields().forEachRemaining(field ->
                        decisions.put(field.getKey(), field.getValue().asBoolean()));
                recurring = new RecurringBook.State(rules, decisions,
                        root.path("nextRuleId").asLong());
            }
            String pending = "NONE";
            String pendingCategory = "";
            Navigation.State navigation = new Navigation().state();
            if (version >= 3) {
                pending = text(root, "pending");
                pendingCategory = text(root, "pendingCategory");
                requireArray(root, "navigationBackStack");
                var back = new ArrayList<Screen>();
                for (var item : root.path("navigationBackStack")) {
                    back.add(Screen.valueOf(item.asText()));
                }
                navigation = new Navigation.State(Screen.valueOf(text(root, "currentScreen")), back);
            }
            var undoHistory = new ArrayList<BotUndoState>();
            if (version >= 4) {
                requireArray(root, "undoHistory");
                for (var item : root.path("undoHistory")) undoHistory.add(readUndo(item));
                if (undoHistory.size() > 30) throw new IOException("Invalid undo history in local state");
            }
            return Optional.of(new BotLocalState(entries, plans, debts, goals, limits,
                    root.path("nextEntryId").asLong(), seen, recurring,
                    pending, pendingCategory, navigation, undoHistory, readLinks(root), readHistory(root), readDeleted(root), root.path("chartTheme").asText("dark"), ChatCleanupSettings.read(root), readPlanEdits(root), readSections(root)));
        } catch (RuntimeException invalid) {
            throw new IOException("Локальная копия повреждена; исходная таблица не изменена", invalid);
        }
    }

    public void save(BotLocalState state) throws IOException {
        ObjectNode root = mapper.createObjectNode();
        root.put("version", 13);
        root.set("categorySections", mapper.valueToTree(state.categorySections()));
        writePlanEdits(root, state.planEdits());
        state.chatCleanup().write(root);
        root.put("chartTheme", state.chartTheme());
        root.put("vkUserId", vkUserId);
        root.put("baseFingerprint", baseFingerprint);
        root.put("nextEntryId", state.nextEntryId());
        root.put("pending", state.pending());
        root.put("pendingCategory", state.pendingCategory());
        root.put("currentScreen", state.navigation().current().name());
        var backStack = root.putArray("navigationBackStack");
        state.navigation().backStack().forEach(screen -> backStack.add(screen.name()));
        var entries = root.putArray("entries");
        for (var timed : state.entries()) {
            var entry = timed.entry();
            var item = entries.addObject();
            item.put("timestamp", timed.timestamp().toString());
            item.put("id", entry.id());
            item.put("date", entry.date().toString());
            item.put("kind", entry.kind().name());
            item.put("amount", entry.amount().toPlainString());
            item.put("category", entry.category());
            item.put("description", entry.description());
            item.put("planned", entry.planned());
        }
        var plans = root.putArray("plans");
        for (var plan : state.plans()) {
            var item = plans.addObject();
            item.put("month", plan.month().toString());
            item.put("category", plan.category());
            item.put("amount", plan.amount().toPlainString());
        }
        var debts = root.putArray("debts");
        for (var debt : state.debts()) {
            var item = debts.addObject();
            item.put("name", debt.name());
            item.put("remaining", debt.remaining().toPlainString());
            item.put("monthlyPayment", debt.monthlyPayment().toPlainString());
            item.put("paymentDay", debt.paymentDay());
            item.put("active", debt.active());
        }
        var goals = root.putArray("goals");
        for (var goal : state.goals()) {
            var item = goals.addObject();
            item.put("name", goal.name());
            item.put("target", goal.target().toPlainString());
            item.put("current", goal.current().toPlainString());
            item.put("monthlyTarget", goal.monthlyTarget().toPlainString());
            item.put("active", goal.active());
        }
        var limits = root.putObject("limits");
        state.limits().forEach((month, amount) -> limits.put(month.toString(), amount.toPlainString()));
        var seen = root.putArray("seenEvents");
        state.seenEvents().forEach(seen::add);
        root.put("nextRuleId", state.recurring().nextRuleId());
        var rules = root.putArray("rules");
        for (var rule : state.recurring().rules()) {
            var item = rules.addObject();
            item.put("id", rule.id());
            item.put("kind", rule.kind().name());
            item.put("amount", rule.amount().toPlainString());
            item.put("dayOfMonth", rule.dayOfMonth());
            item.put("startsOn", rule.startsOn().toString());
            item.put("endsOn", rule.endsOn() == null ? "" : rule.endsOn().toString());
            item.put("category", rule.category());
            item.put("description", rule.description());
            item.put("requiresConfirmation", rule.requiresConfirmation());
        }
        var decisions = root.putObject("decisions");
        state.recurring().decisions().forEach(decisions::put);
        writeLinks(root, state.operationLinks());
        var deleted = root.putArray("deletedIds");
        state.deletedIds().stream().sorted().forEach(deleted::add);
        root.put("historyFrom", state.history().from() == null ? "" : state.history().from().toString());
        root.put("historyThrough", state.history().through() == null ? "" : state.history().through().toString());
        root.put("historyPage", state.history().page());
        root.put("historyCategory", state.history().category());
        root.put("historyKind", state.history().kind());
        root.put("historyQuery", state.history().query());
        root.put("historyAmount", state.history().amount());
        root.put("reportFrom",state.history().reportFrom()==null?"":state.history().reportFrom().toString());
        root.put("reportThrough",state.history().reportThrough()==null?"":state.history().reportThrough().toString());
        root.put("reportSection",state.history().reportSection());
        root.put("editorPage", state.history().editorPage());
        root.put("editorContext", state.history().editorContext());
        var undoHistory = root.putArray("undoHistory");
        for (var snapshot : state.undoHistory()) writeUndo(undoHistory.addObject(), snapshot);
        Path parent = path.getParent();
        Files.createDirectories(parent);
        Path temp = parent.resolve(path.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.writeString(temp, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                    StandardCharsets.UTF_8);
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException noAtomicMove) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private void writeUndo(ObjectNode root, BotUndoState state) {
        root.set("categorySections", mapper.valueToTree(state.categorySections()));
        writePlanEdits(root, state.planEdits());
        writeLinks(root, state.operationLinks());
        var deleted = root.putArray("deletedIds");
        state.deletedIds().stream().sorted().forEach(deleted::add);
        var entries = root.putArray("entries");
        for (var timed : state.entries()) {
            var entry = timed.entry();
            var item = entries.addObject();
            item.put("timestamp", timed.timestamp().toString());
            item.put("id", entry.id());
            item.put("date", entry.date().toString());
            item.put("kind", entry.kind().name());
            item.put("amount", entry.amount().toPlainString());
            item.put("category", entry.category());
            item.put("description", entry.description());
            item.put("planned", entry.planned());
        }
        var plans = root.putArray("plans");
        for (var plan : state.plans()) {
            var item = plans.addObject();
            item.put("month", plan.month().toString());
            item.put("category", plan.category());
            item.put("amount", plan.amount().toPlainString());
        }
        var debts = root.putArray("debts");
        for (var debt : state.debts()) {
            var item = debts.addObject();
            item.put("name", debt.name());
            item.put("remaining", debt.remaining().toPlainString());
            item.put("monthlyPayment", debt.monthlyPayment().toPlainString());
            item.put("paymentDay", debt.paymentDay());
            item.put("active", debt.active());
        }
        var goals = root.putArray("goals");
        for (var goal : state.goals()) {
            var item = goals.addObject();
            item.put("name", goal.name());
            item.put("target", goal.target().toPlainString());
            item.put("current", goal.current().toPlainString());
            item.put("monthlyTarget", goal.monthlyTarget().toPlainString());
            item.put("active", goal.active());
        }
        var limits = root.putObject("limits");
        state.limits().forEach((month, amount) -> limits.put(month.toString(), amount.toPlainString()));
        root.put("nextRuleId", state.recurring().nextRuleId());
        var rules = root.putArray("rules");
        for (var rule : state.recurring().rules()) {
            var item = rules.addObject();
            item.put("id", rule.id());
            item.put("kind", rule.kind().name());
            item.put("amount", rule.amount().toPlainString());
            item.put("dayOfMonth", rule.dayOfMonth());
            item.put("startsOn", rule.startsOn().toString());
            item.put("endsOn", rule.endsOn() == null ? "" : rule.endsOn().toString());
            item.put("category", rule.category());
            item.put("description", rule.description());
            item.put("requiresConfirmation", rule.requiresConfirmation());
        }
        var decisions = root.putObject("decisions");
        state.recurring().decisions().forEach(decisions::put);
    }

    private BotUndoState readUndo(JsonNode root) throws IOException {
        requireArray(root, "entries");
        requireArray(root, "plans");
        requireArray(root, "debts");
        requireArray(root, "goals");
        requireArray(root, "rules");
        if (!root.path("limits").isObject() || !root.path("decisions").isObject()) {
            throw new IOException("Invalid undo state");
        }
        var entries = new ArrayList<LegacyUserData.TimedEntry>();
        for (var item : root.path("entries")) {
            var entry = new LedgerEntry(text(item, "id"),
                    LocalDate.parse(text(item, "date")),
                    EntryKind.valueOf(text(item, "kind")), Money.of(text(item, "amount")),
                    text(item, "category"), text(item, "description"),
                    item.path("planned").asBoolean(false));
            entries.add(new LegacyUserData.TimedEntry(
                    OffsetDateTime.parse(text(item, "timestamp")), entry));
        }
        var plans = new ArrayList<BudgetPlan>();
        for (var item : root.path("plans")) {
            plans.add(new BudgetPlan(YearMonth.parse(text(item, "month")),
                    text(item, "category"), Money.of(text(item, "amount"))));
        }
        var debts = new ArrayList<Debt>();
        for (var item : root.path("debts")) {
            debts.add(new Debt(text(item, "name"), Money.of(text(item, "remaining")),
                    Money.of(text(item, "monthlyPayment")), item.path("paymentDay").asInt(),
                    item.path("active").asBoolean()));
        }
        var goals = new ArrayList<Goal>();
        for (var item : root.path("goals")) {
            goals.add(new Goal(text(item, "name"), Money.of(text(item, "target")),
                    Money.of(text(item, "current")), Money.of(text(item, "monthlyTarget")),
                    item.path("active").asBoolean()));
        }
        Map<YearMonth, BigDecimal> limits = new HashMap<>();
        root.path("limits").fields().forEachRemaining(field ->
                limits.put(YearMonth.parse(field.getKey()), Money.of(field.getValue().asText())));
        var rules = new ArrayList<MonthlyRule>();
        for (var item : root.path("rules")) {
            String end = item.path("endsOn").asText("");
            rules.add(new MonthlyRule(text(item, "id"), EntryKind.valueOf(text(item, "kind")),
                    Money.of(text(item, "amount")), item.path("dayOfMonth").asInt(),
                    LocalDate.parse(text(item, "startsOn")),
                    end.isBlank() ? null : LocalDate.parse(end),
                    text(item, "category"), text(item, "description"),
                    item.path("requiresConfirmation").asBoolean()));
        }
        Map<String, Boolean> decisions = new HashMap<>();
        root.path("decisions").fields().forEachRemaining(field ->
                decisions.put(field.getKey(), field.getValue().asBoolean()));
        return new BotUndoState(entries, plans, debts, goals, limits,
                new RecurringBook.State(rules, decisions, root.path("nextRuleId").asLong()), readLinks(root), readDeleted(root), readPlanEdits(root), readSections(root));
    }

    private financeos.core.CategorySections readSections(JsonNode root) throws IOException {
        return root.has("categorySections") ? mapper.treeToValue(root.path("categorySections"),financeos.core.CategorySections.class) : financeos.core.CategorySections.empty();
    }
    private void writePlanEdits(ObjectNode root,List<financeos.core.PlanEdit> edits) {
        var array=root.putArray("planEdits");for(var edit:edits){var item=array.addObject();item.put("month",edit.month().toString());item.put("source",edit.source());item.put("category",edit.category());item.put("amount",edit.amount().toPlainString());item.put("canonical",edit.canonical());item.put("deleted",edit.deleted());}
    }
    private List<financeos.core.PlanEdit> readPlanEdits(JsonNode root) throws IOException {
        if(!root.has("planEdits"))return List.of();requireArray(root,"planEdits");var edits=new ArrayList<financeos.core.PlanEdit>();
        for(var item:root.path("planEdits"))edits.add(new financeos.core.PlanEdit(YearMonth.parse(text(item,"month")),text(item,"source"),text(item,"category"),Money.of(text(item,"amount")),item.path("canonical").asBoolean(),item.path("deleted").asBoolean()));
        return List.copyOf(edits);
    }

    private java.util.Set<String> readDeleted(JsonNode root) throws IOException {
        if (!root.has("deletedIds")) return java.util.Set.of();
        requireArray(root, "deletedIds");
        var result = new java.util.HashSet<String>();
        for (var item : root.path("deletedIds")) {
            if (!item.isTextual() || item.asText().isBlank()) throw new IOException("Invalid deleted operation ID");
            result.add(item.asText());
        }
        return java.util.Set.copyOf(result);
    }

    private void writeLinks(ObjectNode root, Map<String, financeos.core.OperationLink> links) {
        var object = root.putObject("operationLinks");
        links.forEach((id, link) -> {
            var value = object.putObject(id);
            value.put("type", link.type().name());
            value.put("target", link.target());
        });
    }

    private Map<String, financeos.core.OperationLink> readLinks(JsonNode root) throws IOException {
        if (!root.has("operationLinks")) return Map.of();
        if (!root.path("operationLinks").isObject()) throw new IOException("Invalid operation links");
        var links = new HashMap<String, financeos.core.OperationLink>();
        var fields = root.path("operationLinks").fields();
        while (fields.hasNext()) {
            var field = fields.next();
            links.put(field.getKey(), new financeos.core.OperationLink(
                    financeos.core.OperationLink.Type.valueOf(text(field.getValue(), "type")), text(field.getValue(), "target")));
        }
        return links;
    }

    private HistoryCursor readHistory(JsonNode root) {
        String from = root.path("historyFrom").asText("");
        String through = root.path("historyThrough").asText("");
        return new HistoryCursor(from.isBlank() ? null : LocalDate.parse(from),
                through.isBlank() ? null : LocalDate.parse(through), root.path("historyPage").asInt(0), root.path("historyCategory").asText(""),
                root.path("historyQuery").asText(""), root.path("historyAmount").asText(""),
                root.path("editorPage").asInt(0), root.path("editorContext").asBoolean(false),root.path("historyKind").asText(""),
                root.path("reportFrom").asText("").isBlank()?null:LocalDate.parse(root.path("reportFrom").asText()),
                root.path("reportThrough").asText("").isBlank()?null:LocalDate.parse(root.path("reportThrough").asText()),root.path("reportSection").asText(""));
    }

    private void requireArray(JsonNode root, String name) throws IOException {
        if (!root.path(name).isArray()) throw new IOException("Invalid " + name + " in local state");
    }

    private String text(JsonNode item, String name) throws IOException {
        JsonNode value = item.path(name);
        if (!value.isTextual()) throw new IOException("Invalid " + name + " in local state");
        return value.asText();
    }

    private String fingerprint(LegacyUserData original) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            String value = original.startingBalance() + "\n" + original.snapshotTime() + "\n"
                    + original.transactions() + "\n" + original.plans() + "\n"
                    + original.extras().debts() + "\n" + original.extras().goals() + "\n"
                    + original.extras().categories() + "\n"
                    + new java.util.TreeMap<>(original.extras().monthlyLimits()) + "\n"
                    + new java.util.TreeMap<>(original.extras().dailyOverrides());
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
