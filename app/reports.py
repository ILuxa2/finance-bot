"""Monthly report calculations independent of Telegram and Google Sheets."""

from collections import defaultdict
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation

from app.calculations import (
    calculate_daily_limit, calculate_daily_rollover, calculate_financial_health, percentage,
    resolve_daily_limit,
)


MONTH_NAMES_RU = (
    "январь", "февраль", "март", "апрель", "май", "июнь",
    "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь",
)


def build_operations_history(
    rows: list[dict[str, str]], *, operation_type: str | None = None,
    query: str = "", now: datetime | None = None,
) -> str:
    """Format a bounded, newest-first list of the current month's operations."""
    current_time = (now or datetime.now(timezone.utc)).astimezone(timezone.utc)
    filtered: list[tuple[datetime, dict[str, str]]] = []
    needle = query.casefold().strip()
    for row in rows:
        created_at = _parse_timestamp(row.get("created_at", ""))
        if created_at is None or (created_at.year, created_at.month) != (current_time.year, current_time.month):
            continue
        if operation_type and row.get("type") != operation_type:
            continue
        if needle and needle not in str(row.get("category", "")).casefold() and needle not in str(row.get("description", "")).casefold():
            continue
        filtered.append((created_at, row))

    filtered.sort(key=lambda item: item[0], reverse=True)
    income = sum((_decimal_value(row, "amount") for _, row in filtered if row.get("type") == "income"), Decimal("0"))
    expense = sum((_decimal_value(row, "amount") for _, row in filtered if row.get("type") == "expense"), Decimal("0"))
    month_title = f"{MONTH_NAMES_RU[current_time.month - 1]} {current_time.year}"
    if operation_type == "expense":
        lines = [f"💸 Расходы за {month_title}"]
    elif operation_type == "income":
        lines = [f"💰 Доходы за {month_title}"]
    else:
        lines = [f"📜 Операции за {month_title}"]

    shown = filtered[:30]
    if not shown:
        lines.extend(["", "Операций за этот месяц нет."])
    for created_at, row in shown:
        amount = _format_amount(_decimal_value(row, "amount"))
        category = str(row.get("category", "")).strip() or "без категории"
        description = str(row.get("description", "")).strip() or "без описания"
        formatted_date = created_at.strftime("%d.%m.%Y %H:%M")
        if operation_type == "expense":
            lines.extend(["", formatted_date, f"{amount}   {category}   {description}"])
        elif operation_type == "income":
            lines.extend(["", formatted_date, f"{amount}   {description}"])
        else:
            if row.get("type") == "transfer":
                transfer_text = description.removeprefix("Перевод: ")
                route, _, comment = transfer_text.partition(". ")
                lines.extend(["", formatted_date, f"🔁 Перевод: {amount}", route,
                              f"Комментарий: {comment or 'без комментария'}"])
            else:
                kind = "💰 Доход" if row.get("type") == "income" else "💸 Расход"
                lines.extend(["", formatted_date, f"{kind}: {amount}", f"📂 Категория: {category}", f"📝 {description}"])
    if len(filtered) > len(shown):
        lines.extend(["", f"Показаны последние 30 операций из {len(filtered)}."])
    lines.append("")
    if operation_type == "expense":
        lines.append(f"Итого расходов: {_format_amount(expense)}")
    elif operation_type == "income":
        lines.append(f"Итого доходов: {_format_amount(income)}")
    else:
        lines.extend([
            f"Всего операций: {len(filtered)}",
            f"Доходы: {_format_amount(income)}",
            f"Расходы: {_format_amount(expense)}",
            f"Баланс: {_format_amount(income - expense)}",
        ])
    return "\n".join(lines)


def build_monthly_summary(
    rows: list[dict[str, str]],
    startup_state: dict[str, str] | None = None,
    *,
    now: datetime | None = None,
) -> str:
    """Build the current UTC-month summary, optionally from a startup snapshot."""
    current_time = (now or datetime.now(timezone.utc)).astimezone(timezone.utc)
    startup_at = _parse_timestamp(startup_state.get("created_at", "")) if startup_state else None
    snapshot_is_current_month = startup_at is not None and (
        startup_at.year, startup_at.month
    ) == (current_time.year, current_time.month)
    income = _decimal_value(startup_state, "month_income_so_far") if snapshot_is_current_month else Decimal("0")
    expense = _decimal_value(startup_state, "month_expense_so_far") if snapshot_is_current_month else Decimal("0")
    balance = _decimal_value(startup_state, "total_balance")
    categories: defaultdict[str, Decimal] = defaultdict(lambda: Decimal("0"))

    for row in rows:
        created_at = _parse_timestamp(row.get("created_at", ""))
        if created_at is None or (startup_at is not None and created_at <= startup_at):
            continue
        amount = _decimal_value(row, "amount")
        transaction_type = row.get("type")
        if transaction_type == "income":
            balance += amount
        elif transaction_type == "expense":
            balance -= amount
        else:
            continue

        if (created_at.year, created_at.month) != (current_time.year, current_time.month):
            continue
        if transaction_type == "income":
            income += amount
        else:
            expense += amount
            categories[row.get("category", "") or "Без категории"] += amount

    report_balance = balance if startup_state else income - expense
    lines = [
        f"📊 Отчёт за {MONTH_NAMES_RU[current_time.month - 1]} {current_time.year}",
        "──────────",
        f"💰 Доходы: {_format_amount(income)}",
        f"💸 Расходы: {_format_amount(expense)}",
        f"🏦 Баланс: {_format_amount(report_balance)}",
        "", "📂 Топ категорий расходов:",
    ]
    if categories:
        for category, amount in sorted(categories.items(), key=lambda item: (-item[1], item[0].lower()))[:5]:
            lines.append(f"• {category}: {_format_amount(amount)}")
    else:
        lines.append("• Нет расходов за этот месяц")
    return "\n".join(lines)


def build_accounts_report_block(startup_state: dict[str, str] | None) -> str:
    """Format current account balances without recalculating their total."""
    if startup_state is None:
        return ""
    return "\n".join([
        "🏦 Счета:",
        f"Наличные: {_format_amount(_decimal_value(startup_state, 'cash_balance'))}",
        f"Карты: {_format_amount(_decimal_value(startup_state, 'card_balance'))}",
        f"Накопления: {_format_amount(_decimal_value(startup_state, 'savings_balance'))}",
        f"Всего: {_format_amount(_decimal_value(startup_state, 'total_balance'))}",
    ])


def calculate_budget(
    rows: list[dict[str, str]],
    plans: list[dict[str, str]],
    startup_state: dict[str, str] | None = None,
    *,
    now: datetime | None = None,
) -> dict[str, object]:
    """Calculate current-month plan/fact figures without allocating snapshot costs."""
    current_time = (now or datetime.now(timezone.utc)).astimezone(timezone.utc)
    startup_at = _parse_timestamp(startup_state.get("created_at", "")) if startup_state else None
    snapshot_is_current_month = startup_at is not None and (
        startup_at.year, startup_at.month
    ) == (current_time.year, current_time.month)
    startup_expense = (
        _decimal_value(startup_state, "month_expense_so_far")
        if snapshot_is_current_month else Decimal("0")
    )
    facts: defaultdict[str, Decimal] = defaultdict(lambda: Decimal("0"))
    total_operations_expense = Decimal("0")
    for row in rows:
        created_at = _parse_timestamp(row.get("created_at", ""))
        if (
            created_at is None
            or (startup_at is not None and created_at <= startup_at)
            or (created_at.year, created_at.month) != (current_time.year, current_time.month)
            or row.get("type") != "expense"
        ):
            continue
        amount = _decimal_value(row, "amount")
        total_operations_expense += amount
        facts[row.get("category", "") or "Без категории"] += amount

    plan_by_category: defaultdict[str, Decimal] = defaultdict(lambda: Decimal("0"))
    for plan in plans:
        category = str(plan.get("category", "")).strip()
        if category:
            plan_by_category[category] += _decimal_value(plan, "planned_amount")
    category_names = sorted(set(plan_by_category) | set(facts), key=str.lower)
    categories = [
        {
            "category": category,
            "planned": plan_by_category[category],
            "actual": facts[category],
            "remaining": plan_by_category[category] - facts[category],
        }
        for category in category_names
    ]
    total_planned = sum(plan_by_category.values(), Decimal("0"))
    total_actual = startup_expense + total_operations_expense
    return {
        "categories": categories,
        "total_planned": total_planned,
        "total_actual": total_actual,
        "total_remaining": total_planned - total_actual,
        "percent": _percent(total_actual, total_planned),
        "now": current_time,
    }


def build_budget_plan_report(
    rows: list[dict[str, str]], plans: list[dict[str, str]],
    startup_state: dict[str, str] | None = None, *, now: datetime | None = None,
) -> str:
    budget = calculate_budget(rows, plans, startup_state, now=now)
    current_time: datetime = budget["now"]  # type: ignore[assignment]
    categories: list[dict[str, object]] = budget["categories"]  # type: ignore[assignment]
    lines = [f"📊 План / факт за {MONTH_NAMES_RU[current_time.month - 1]} {current_time.year}", "──────────"]
    if not categories:
        lines.extend(["", "Планы на текущий месяц пока не заданы."])
    for item in categories:
        planned = item["planned"]
        actual = item["actual"]
        remaining = item["remaining"]
        percent = _percent(actual, planned)
        emoji = "" if not planned else "🟢" if percent < 80 else "🟡" if percent <= 100 else "🔴"
        plan_text = _format_amount(planned) if planned else "—"
        remaining_text = _format_amount(abs(remaining)) if planned else "—"
        percent_text = f"{percent:.1f}%" if planned else "—"
        lines.extend([
            "", f"{emoji + ' ' if emoji else ''}{item['category']}", f"План: {plan_text}",
            f"Факт: {_format_amount(actual)}",
            f"{'Осталось' if remaining >= 0 else 'Перерасход'}: {remaining_text}",
            f"Выполнение: {percent_text}",
        ])
    lines.extend([
        "", "──────────", "Итого:", f"План: {_format_amount(budget['total_planned'])}",
        f"Факт: {_format_amount(budget['total_actual'])}",
        f"{'Осталось' if budget['total_remaining'] >= 0 else 'Перерасход'}: {_format_amount(abs(budget['total_remaining']))}",
        f"Выполнение: {budget['percent']:.1f}%",
    ])
    return "\n".join(lines)


def build_budget_report_block(
    rows: list[dict[str, str]], plans: list[dict[str, str]],
    startup_state: dict[str, str] | None = None, *, now: datetime | None = None,
) -> str:
    """Build the compact budget section appended to /report."""
    budget = calculate_budget(rows, plans, startup_state, now=now)
    lines = [
        "📌 Бюджет", f"План: {_format_amount(budget['total_planned'])}",
        f"Факт: {_format_amount(budget['total_actual'])}",
        f"{'Осталось' if budget['total_remaining'] >= 0 else 'Перерасход'}: {_format_amount(abs(budget['total_remaining']))}",
        f"Выполнение: {budget['percent']:.1f}%",
    ]
    overruns = [
        item for item in budget["categories"]
        if item["planned"] > 0 and item["remaining"] < 0
    ]
    if overruns:
        lines.append("Перерасход:")
        lines.extend(f"- {item['category']}: +{_format_amount(abs(item['remaining']))}" for item in overruns)
    return "\n".join(lines)


def build_daily_limit_report_block(rows, daily_limit, *, override=None, now=None) -> str:
    """Build the daily-limit section shared by /daily_limit and /report."""
    effective = resolve_daily_limit(
        daily_limit.get("daily_limit") if daily_limit else None, override
    )
    if effective["source"] == "disabled":
        return "📆 Дневной лимит: сегодня отключён"
    result = calculate_daily_limit(rows, effective["limit"], now=now)
    if not result["is_set"]:
        return "📆 Дневной лимит: не установлен"
    status = result["status"]
    status_text = "🟢 В пределах лимита" if status == "ok" else (
        "🟡 Близко к лимиту" if status == "warning" else "🔴 Лимит превышен"
    )
    remaining = max(Decimal("0"), result["remaining"])
    lines = [
        "📆 Дневной лимит:", f"Лимит: {_format_amount(result['limit'])}",
        f"Сегодня: {_format_amount(result['spent'])}",
        f"Осталось: {_format_amount(remaining)}", f"Статус: {status_text}",
    ]
    rollover = calculate_daily_rollover(result["limit"], result["spent"], now=now)
    if rollover["unused_today"] > 0:
        lines.extend([
            "",
            f"🔄 Остаток дня к переносу: {_format_amount(rollover['unused_today'])}",
            f"Новый рекомендованный лимит: {_format_amount(rollover['recommended_daily_limit'])} /день",
        ])
    return "\n".join(lines)


def build_daily_limit_message(rows, daily_limit, *, override=None, now=None) -> str:
    effective = resolve_daily_limit(
        daily_limit.get("daily_limit") if daily_limit else None, override
    )
    if effective["source"] == "disabled":
        return "📆 Дневной лимит\nСегодня лимит отключён."
    result = calculate_daily_limit(rows, effective["limit"], now=now)
    if not result["is_set"]:
        return "Дневной лимит не установлен."
    if result["status"] == "ok":
        status = "🟢 В пределах лимита"
    elif result["status"] == "warning":
        status = "🟡 Близко к лимиту"
    else:
        status = f"🔴 Лимит превышен на {_format_amount(abs(result['remaining']))}"
    source = ("индивидуальный лимит дня" if effective["source"] == "override"
              else "месячный лимит")
    limit_label = "Лимит на сегодня" if effective["source"] == "override" else "Лимит"
    lines = [
        "📆 Дневной лимит", "", f"{limit_label}: {_format_amount(result['limit'])}",
        f"Источник: {source}",
        f"Потрачено сегодня: {_format_amount(result['spent'])}",
        f"Осталось: {_format_amount(max(Decimal('0'), result['remaining']))}",
        f"Использовано: {result['percent']:.1f}%", "", "Статус:", status,
    ]
    rollover = calculate_daily_rollover(result["limit"], result["spent"], now=now)
    if rollover["unused_today"] > 0:
        lines.extend([
            "",
            "🔄 Перенос остатка:",
            f"Неиспользовано сегодня: {_format_amount(rollover['unused_today'])}",
            f"Осталось дней в месяце: {rollover['remaining_days']}",
            f"Добавка к лимиту: {_format_amount(rollover['rollover_per_day'])} /день",
            f"Рекомендованный лимит: {_format_amount(rollover['recommended_daily_limit'])} /день",
        ])
    else:
        lines.extend(["", "🔄 Перенос остатка: нет"])
    return "\n".join(lines)


def build_debt_report_block(
    rows: list[dict[str, str]], debts: list[dict[str, str]],
    debt_payments: list[dict[str, str]], startup_state: dict[str, str] | None = None,
    *, now: datetime | None = None,
) -> str:
    """Build the debt section for the monthly report."""
    active_debts = [debt for debt in debts if debt.get("status") == "active"]
    if not active_debts:
        return "💳 Долги: активных долгов нет"

    current_time = (now or datetime.now(timezone.utc)).astimezone(timezone.utc)
    startup_at = _parse_timestamp(startup_state.get("created_at", "")) if startup_state else None
    snapshot_is_current_month = startup_at is not None and (
        startup_at.year, startup_at.month
    ) == (current_time.year, current_time.month)
    income = _decimal_value(startup_state, "month_income_so_far") if snapshot_is_current_month else Decimal("0")
    for row in rows:
        created_at = _parse_timestamp(row.get("created_at", ""))
        if (
            created_at is None or (startup_at is not None and created_at <= startup_at)
            or (created_at.year, created_at.month) != (current_time.year, current_time.month)
            or row.get("type") != "income"
        ):
            continue
        income += _decimal_value(row, "amount")

    paid_this_month = _decimal_value(startup_state, "month_debt_paid_so_far") if snapshot_is_current_month else Decimal("0")
    for payment in debt_payments:
        created_at = _parse_timestamp(payment.get("created_at", ""))
        if (
            created_at is not None and (startup_at is None or created_at > startup_at)
            and (created_at.year, created_at.month) == (current_time.year, current_time.month)
        ):
            paid_this_month += _decimal_value(payment, "amount")

    total_remaining = sum((_decimal_value(debt, "current_amount") for debt in active_debts), Decimal("0"))
    monthly_payments = sum((_decimal_value(debt, "monthly_payment") for debt in active_debts), Decimal("0"))
    lines = [
        "💳 Долги",
        f"Остаток долгов: {_format_amount(total_remaining)}",
        f"Платежи в месяц: {_format_amount(monthly_payments)}",
        f"Погашено в этом месяце: {_format_amount(paid_this_month)}",
    ]
    if income:
        burden = (monthly_payments / income * 100).quantize(Decimal("0.1"))
        lines.append(f"Нагрузка: {burden:.1f}%")
    else:
        lines.append("Долговая нагрузка: нет данных по доходам")
    return "\n".join(lines)


def _percent(actual: object, planned: object) -> Decimal:
    return percentage(
        actual if isinstance(actual, (Decimal, int, float, str)) else 0,
        planned if isinstance(planned, (Decimal, int, float, str)) else 0,
    )


def _format_amount(value: object) -> str:
    amount = value if isinstance(value, Decimal) else Decimal("0")
    rendered = f"{amount:,.2f}" if amount % 1 else f"{amount:,.0f}"
    return f"{rendered.replace(',', ' ')} ₽"


def _decimal_value(row: dict[str, str] | None, field: str) -> Decimal:
    if not row:
        return Decimal("0")
    try:
        return Decimal(str(row.get(field, "0")))
    except (InvalidOperation, ValueError):
        return Decimal("0")


def _parse_timestamp(value: str) -> datetime | None:
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except (TypeError, ValueError):
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


def financial_metrics(rows: list[dict[str, str]], startup: dict[str, str] | None,
                      debts: list[dict[str, str]], goals: list[dict[str, str]],
                      *, now: datetime | None = None) -> dict[str, Decimal]:
    """Return current-month cash-flow inputs shared by forecast and health."""
    current = (now or datetime.now(timezone.utc)).astimezone(timezone.utc)
    startup_at = _parse_timestamp(startup.get("created_at", "")) if startup else None
    current_startup = startup_at and (startup_at.year, startup_at.month) == (current.year, current.month)
    income = _decimal_value(startup, "month_income_so_far") if current_startup else Decimal("0")
    expense = _decimal_value(startup, "month_expense_so_far") if current_startup else Decimal("0")
    balance = _decimal_value(startup, "total_balance")
    for row in rows:
        date = _parse_timestamp(row.get("created_at", ""))
        if date is None or (startup_at and date <= startup_at):
            continue
        amount = _decimal_value(row, "amount")
        if row.get("type") == "income":
            balance += amount
        elif row.get("type") == "expense":
            balance -= amount
        else:
            continue
        if (date.year, date.month) != (current.year, current.month):
            continue
        if row.get("type") == "income":
            income += amount
        # Debt payments and goal funding are forecast separately.  Excluding
        # their operational entries prevents counting them twice.
        elif str(row.get("category", "")).casefold() not in {"долги", "накопления"}:
            expense += amount
    active_debts = [d for d in debts if d.get("status") == "active"]
    active_goals = [g for g in goals if g.get("status") == "active"]
    debt_payments = sum((_decimal_value(d, "monthly_payment") for d in active_debts), Decimal("0"))
    goal_targets = sum((_decimal_value(g, "monthly_target") for g in active_goals), Decimal("0"))
    return {"income": income, "expense": expense, "debt_payments": debt_payments,
            "goal_targets": goal_targets, "free_cashflow": income - expense - debt_payments - goal_targets,
            "balance": balance}


def build_business_insights(rows, startup, plans, debts, goals, *, now=None, daily_limit=None,
                            daily_limit_override=None) -> list[str]:
    """Return up to seven concise business conclusions for the current month."""
    metrics = financial_metrics(rows, startup, debts, goals, now=now)
    budget = calculate_budget(rows, plans, startup, now=now)
    income, expense = metrics["income"], metrics["expense"]
    insights: list[str] = []

    effective = resolve_daily_limit(
        daily_limit.get("daily_limit") if daily_limit else None, daily_limit_override
    )
    daily = calculate_daily_limit(rows, effective["limit"], now=now)
    if effective["source"] == "disabled":
        insights.append("Дневной лимит сегодня отключён.")
    elif not daily["is_set"]:
        insights.append("Дневной лимит не установлен.")
    elif daily["status"] == "ok":
        insights.append("Сегодняшние траты в пределах дневного лимита.")
    elif daily["status"] == "warning":
        insights.append("🟡 Сегодня близко к дневному лимиту.")
    else:
        insights.append(f"🔴 Сегодня дневной лимит превышен на {_format_amount(abs(daily['remaining']))}.")
    rollover = calculate_daily_rollover(daily["limit"], daily["spent"], now=now)
    if daily["is_set"] and daily["status"] == "exceeded":
        insights.append("🔴 Переноса нет, дневной лимит превышен.")
    elif rollover["unused_today"] > 0 and rollover["remaining_days"] > 0:
        insights.append(
            f"Сегодня осталось {_format_amount(rollover['unused_today'])}. "
            f"Можно распределить по {_format_amount(rollover['rollover_per_day'])} "
            "на каждый оставшийся день месяца."
        )

    if expense > income:
        insights.append("🔴 Расходы выше доходов. Месяц уходит в минус.")
    elif income > 0:
        ratio = expense / income * 100
        if ratio <= 70:
            insights.append("Расходы в комфортной зоне.")
        elif ratio <= 90:
            insights.append("Расходы высоковаты, но ещё контролируемы.")
        else:
            insights.append("🔴 Расходы почти съели доход.")

    overruns = [item for item in budget["categories"] if item["planned"] > 0 and item["remaining"] < 0]
    if overruns:
        details = "\n".join(
            f"- {item['category']}: +{_format_amount(abs(item['remaining']))}"
            for item in overruns[:3]
        )
        insights.append(f"🔴 Перерасход по категориям:\n{details}")

    at_risk = [
        item for item in budget["categories"]
        if item["planned"] > 0 and Decimal("80") <= _percent(item["actual"], item["planned"]) <= Decimal("100")
    ]
    if at_risk:
        details = "\n".join(
            f"- {item['category']}: использовано {_percent(item['actual'], item['planned']):.0f}%"
            for item in at_risk[:3]
        )
        insights.append(f"🟡 Категории в зоне риска:\n{details}")

    debt_payments = metrics["debt_payments"]
    if income > 0:
        burden = debt_payments / income * 100
        if burden > 50:
            insights.append(f"🔴 Долговая нагрузка опасная: {burden:.0f}%")
        elif burden >= 30:
            insights.append(f"Долговая нагрузка высокая: {burden:.0f}%")
        else:
            insights.append(f"Долговая нагрузка приемлемая: {burden:.0f}%")

    free_cashflow = metrics["free_cashflow"]
    if free_cashflow < 0:
        insights.append("🔴 Свободный денежный поток отрицательный.")
    elif free_cashflow > 0:
        insights.append(f"Свободный денежный поток положительный: {_format_amount(free_cashflow)}")

    active_goals = [goal for goal in goals if goal.get("status") == "active"]
    if active_goals:
        insights.append(f"Есть активные цели: {len(active_goals)}")
    else:
        insights.append("Нет активных целей. Деньги не направлены в конкретные накопления.")
    return insights[:7]


def goal_forecast_date(goal: dict[str, str], *, now: datetime | None = None) -> str:
    remaining = max(Decimal("0"), _decimal_value(goal, "target_amount") - _decimal_value(goal, "current_amount"))
    monthly = _decimal_value(goal, "monthly_target")
    if remaining == 0: return "достигнута"
    if monthly <= 0: return "не определён"
    months = int((remaining / monthly).to_integral_value(rounding="ROUND_CEILING"))
    current = now or datetime.now(timezone.utc)
    month_index = current.month - 1 + months
    return f"{month_index % 12 + 1:02d}.{current.year + month_index // 12}"


def build_forecast_report(rows, startup, plans, debts, goals, *, now=None) -> str:
    metrics = financial_metrics(rows, startup, debts, goals, now=now)
    lines = ["📈 Финансовый прогноз", "──────────"]
    if not rows and not startup:
        lines.append("Прогноз приблизительный: пока мало данных.")
    for months in (1, 3, 6, 12):
        lines.extend(["", f"На {months} мес.", f"Ожидаемые доходы: {_format_amount(metrics['income'] * months)}",
                      f"Ожидаемые расходы: {_format_amount(metrics['expense'] * months)}",
                      f"Платежи по долгам: {_format_amount(metrics['debt_payments'] * months)}",
                      f"Пополнения целей: {_format_amount(metrics['goal_targets'] * months)}",
                      f"Свободный денежный поток: {_format_amount(metrics['free_cashflow'] * months)}",
                      f"Прогнозный остаток: {_format_amount(metrics['balance'] + metrics['free_cashflow'] * months)}"])
    return "\n".join(lines)


def calculate_health(rows, startup, plans, debts, goals, *, now=None) -> dict[str, object]:
    metrics = financial_metrics(rows, startup, debts, goals, now=now)
    budget = calculate_budget(rows, plans, startup, now=now)
    active_goals = [g for g in goals if g.get("status") == "active"]
    health = calculate_financial_health(
        income=metrics["income"],
        expense=metrics["expense"],
        debt_payments=metrics["debt_payments"],
        goal_targets=metrics["goal_targets"],
        has_active_goals=bool(active_goals),
        has_budget=bool(plans),
        has_budget_overrun=any(
            item["planned"] > 0 and item["remaining"] < 0 for item in budget["categories"]
        ),
        has_startup_state=bool(startup),
        has_debt_progress=any(
            _decimal_value(d, "current_amount") < _decimal_value(d, "initial_amount")
            for d in debts
        ),
    )
    return {
        "score": health["score"], "pluses": health["pluses"],
        "minuses": health["minuses"], "metrics": metrics,
    }


def build_health_report(rows, startup, plans, debts, goals, *, now=None) -> str:
    health = calculate_health(rows, startup, plans, debts, goals, now=now)
    lines = [f"💚 Финансовое здоровье: {health['score']}/100", "──────────", "Плюсы:"]
    lines.extend(f"• {value}" for value in health["pluses"] or ["пока не выявлены"])
    lines.extend(["", "Минусы:"])
    lines.extend(f"• {value}" for value in health["minuses"] or ["критичных рисков нет"])
    recommendation = "Снизить расходы в категориях с перерасходом и не увеличивать долговую нагрузку." if health["minuses"] else "Продолжайте соблюдать бюджет и пополнять цели."
    return "\n".join(lines + ["", f"Рекомендация:\n{recommendation}"])
