# Telegram Finance Bot

Telegram-бот для ведения личных финансов с хранением данных в Google Sheets. Бот объединяет оперативный учёт, планирование бюджета и понятную финансовую аналитику в интерфейсе Telegram.

## Возможности

- учёт доходов и расходов;
- фиксация стартового состояния счетов и уже накопленных итогов месяца;
- бюджет по категориям в формате план/факт;
- учёт долгов, платежей и срока выхода из долгов;
- финансовые цели и пополнение накоплений;
- прогноз денежных потоков на 1, 3, 6 и 12 месяцев;
- оценка финансового здоровья от 0 до 100;
- история, фильтрация и удаление операций;
- диаграммы расходов, cash flow, бюджета, долгов и целей;
- автоматические бизнес-выводы;
- пользовательские категории, избранное, архивирование и перенос операций.
- переводы между наличными, картой и накопительным счётом без влияния на доходы и расходы.

## Стек

- Python 3.10+
- python-telegram-bot 21
- Google Sheets API
- gspread
- matplotlib
- python-dotenv (dotenv)
- Git

## Архитектура

```text
Пользователь → Telegram → app/main.py → app/handlers.py
                                      ├→ app/sheets.py → Google Sheets API
                                      ├→ app/reports.py
                                      └→ app/charts.py → PNG → Telegram
```

`main.py` собирает приложение и маршруты, `handlers.py` реализует команды и сценарии ввода, `sheets.py` изолирует доступ к данным, `reports.py` содержит расчёты и текстовые отчёты, `charts.py` строит временные PNG, а `config.py` валидирует конфигурацию. Подробнее: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) и [PROJECT_STRUCTURE.md](PROJECT_STRUCTURE.md).

## Локальный запуск

1. Установите Python 3.10+ и Git.
2. Клонируйте репозиторий и перейдите в его каталог.
3. Создайте виртуальное окружение:

   ```powershell
   python -m venv .venv
   .\.venv\Scripts\Activate.ps1
   pip install -r requirements.txt
   ```

4. Создайте `.env` в корне проекта и настройте Google Sheets.
5. Запустите бот:

   ```powershell
   python -m app.main
   ```

## Проверка

После установки зависимостей запустите автотесты:

```powershell
python -m pytest
```

Проверка синтаксиса приложения:

```powershell
python -m compileall -q app
```

## Настройка `.env`

```dotenv
TELEGRAM_BOT_TOKEN=token_from_botfather
GOOGLE_SHEETS_SPREADSHEET_ID=spreadsheet_id_from_url
GOOGLE_SERVICE_ACCOUNT_FILE=credentials/service-account.json
GOOGLE_SHEETS_WORKSHEET=Transactions
```

Вместо `GOOGLE_SERVICE_ACCOUNT_FILE` можно передать весь JSON одной строкой в `GOOGLE_SERVICE_ACCOUNT_JSON`. Если заданы оба варианта, используется JSON. Не коммитьте `.env` и ключ сервисного аккаунта.

## Подключение Google Sheets

1. Создайте проект в Google Cloud и включите Google Sheets API.
2. Создайте service account и JSON-ключ.
3. Создайте таблицу Google Sheets.
4. Дайте email сервисного аккаунта права редактора таблицы.
5. Скопируйте ID таблицы из URL в `GOOGLE_SHEETS_SPREADSHEET_ID`.
6. Укажите путь к JSON-ключу либо его содержимое в `.env`.

Листы `Transactions`, `StartupState`, `BudgetPlan`, `Debts`, `DebtPayments`, `Goals` и `Categories` создаются при первом обращении. Не изменяйте их заголовки вручную.

## Запуск через `start_bot.bat`

Настройте `.env`, установите зависимости и дважды щёлкните `start_bot.bat` либо выполните:

```powershell
.\start_bot.bat
```

BAT-файл переходит в корень проекта и запускает `python -m app.main`. Если используется виртуальное окружение, сначала активируйте его или обеспечьте доступ к нужному `python` через `PATH`.

## Windows Autostart

Новые Windows-скрипты лежат в `scripts/windows/` и не требуют запуска из Git Bash.

1. Включить простой автозапуск через папку Startup:
   - нажмите `Win + R`, выполните `shell:startup`;
   - создайте ярлык на `scripts/windows/start_bot.bat`.

2. Включить надёжный автозапуск через Task Scheduler:

   ```powershell
   .\scripts\windows\install_autostart_task.bat
   ```

   Задача `FinanceOS Bot` запускается при входе в Windows и настроена на перезапуск при сбое.

3. Выключить автозапуск через Task Scheduler:

   ```powershell
   .\scripts\windows\uninstall_autostart_task.bat
   ```

   Скрипт удаляет только задачу планировщика. Код, `.env`, `credentials` и Google Sheets не трогаются.

4. Запустить вручную:

   ```powershell
   .\scripts\windows\start_bot.bat
   ```

   Повторный запуск не создаёт второй экземпляр: скрипт проверяет процессы
   `python.exe`/`pythonw.exe` с `app.main` в командной строке и выводит
   `FinanceOS уже запущен.`, если бот уже работает.

5. Остановить вручную:

   ```powershell
   .\scripts\windows\stop_bot.bat
   ```

   Остановка завершает только процессы Python, в командной строке которых есть
   `app.main`; остальные `python.exe` не трогаются.

6. Перезапустить вручную:

   ```powershell
   .\scripts\windows\restart_bot.bat
   ```

Логи Windows-запуска пишутся в `logs/windows_stdout.log` и `logs/windows_stderr.log`.
Если бот не стартует, проверьте эти файлы, наличие `.env`, доступность
`credentials`, установленный Python в `PATH` и зависимости из `requirements.txt`.

## Основные команды

| Команда | Назначение |
|---|---|
| `/start`, `/add` | главное меню и добавление операции |
| `/income`, `/expense`, `/transfer` | доход, расход или перевод между счетами |
| `/report`, `/plan`, `/insights` | отчёт, план/факт и выводы |
| `/setup_current_state` | стартовое финансовое состояние |
| `/debt_menu`, `/debts`, `/pay_debt` | управление долгами |
| `/goal_menu`, `/goals`, `/fund_goal` | управление целями |
| `/forecast`, `/freedom`, `/health` | прогноз, срок выплаты долгов, здоровье |
| `/operations`, `/expenses`, `/incomes` | история операций |
| `/charts` | меню диаграмм |
| `/settings`, `/cancel` | настройки и отмена ввода |

Полный набор прямых команд доступен через inline-меню соответствующих разделов.

### Переводы между счетами (V5.3)

Команда `/transfer` пошагово запрашивает счёт списания, счёт зачисления, сумму и
комментарий. Операция сохраняется с типом `transfer` и отображается в
`/operations`, но исключается из `/expenses`, `/incomes`, план/факта и расчётов
финансового здоровья. При наличии `StartupState` меняются только балансы двух
счетов; `total_balance` остаётся прежним. Удаление перевода откатывает эти
изменения. Без стартового состояния операция сохраняется с предупреждением.

## Roadmap

Текущая версия — **v5.3: переводы между счетами**. Следующие направления: Docker, GitHub Actions, web dashboard, OCR чеков и AI financial advisor. История и планы: [docs/ROADMAP.md](docs/ROADMAP.md), [CHANGELOG.md](CHANGELOG.md).

## Статус проекта

**Portfolio / QA-ready, active development.** Основные пользовательские сценарии реализованы; облачная упаковка и автоматизированный CI/CD находятся в roadmap. Перед релизом рекомендуется пройти ручной набор из [docs/TEST_CASES.md](docs/TEST_CASES.md).
# V5.4 — дневной лимит

Команды `/set_daily_limit`, `/daily_limit` и `/delete_daily_limit` управляют дневным
лимитом текущего месяца. Значения хранятся в листе `DailyLimits`; удаление мягкое
(`status = deleted`). В расчёт входят только сегодняшние операции `type = expense`.
Текущий статус также показывается в `/report` и `/insights`.

V5.5 добавляет утреннее согласование лимита. Команды
`/daily_limit_reminder` и `/set_daily_limit_reminder` управляют ежедневной задачей
APScheduler в часовом поясе `Europe/Moscow`. Настройки хранятся в `UserSettings`,
а индивидуальный лимит дня или отключение на сегодня — в `DailyLimitOverrides`.

V5.6 добавляет рекомендательный перенос неиспользованного дневного лимита до конца
месяца. Если сегодня потрачено меньше эффективного дневного лимита, остаток
равномерно распределяется на оставшиеся дни месяца и показывается в `/daily_limit`,
`/report` и `/insights`. Базовый `DailyLimits.daily_limit` автоматически не
изменяется; при `DailyLimitOverrides.status = disabled` перенос не считается.
