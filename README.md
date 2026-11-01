# FinanceOS — Java / VK

Personal finance bot for VK, implemented in Java 21 and deployed with Yandex Cloud Functions.

## Features

- Income and expenses, monthly plans, daily limits, debts and savings goals.
- Reports for a date or period, operation history, financial forecasts and PNG charts.
- Monthly salary/payment rules requiring confirmation, navigation and persistent undo history.
- `/help` and `/commands` show the command catalogue; `/start` opens the menu.
- Durable VK event inbox, ordered message processing, saved replies and idempotent retries.
- Visible Google Sheets mirrors of financial state, conflict detection and readback verification.

## Architecture

`VK → CloudVkIngress → Object Storage inbox → trigger → CloudVkProcessor → state + Google Sheets → VK`

Object Storage holds the authoritative application state. The processor projects it into the original Google spreadsheet and its copy before replying. Each spreadsheet is updated atomically; cross-spreadsheet recovery uses retryable version markers. Manual edits to mirrored sheets are detected and require reconciliation; automatic bidirectional editing is not supported.

`CloudReminders` handles scheduled reminders. Secrets are supplied through Lockbox environment mappings, not committed files.

## Структура проекта

`src` — исходники; `main/java/financeos` — рабочий Java-код; `test/java/financeos` — проверки.

| Папка | Назначение |
| --- | --- |
| `src/main/java/financeos/cloud` | Запуск в Яндексе: приём VK, обработка событий, напоминания, Object Storage и синхронизация таблиц |
| `src/main/java/financeos/core` | Финансовые расчёты: баланс, планы, лимиты, долги, цели, прогнозы, регулярные операции и отмена |
| `src/main/java/financeos/data` | Чтение и запись Google Sheets, схема колонок, преобразование старых данных и резервные копии |
| `src/main/java/financeos/vk` | Команды, кнопки, диалог, тексты ответов, отправка сообщений и диаграмм |
| `src/test/java/financeos/cloud` | Проверки облачных событий и сохранения |
| `src/test/java/financeos/core` | Проверки финансовых расчётов и расписаний |
| `src/test/java/financeos/data` | Проверки таблиц и совместимости данных |
| `src/test/java/financeos/vk` | Проверки меню, диалога, отмены и доставки VK |

| Файл в корне | Назначение |
| --- | --- |
| `README.md` | Описание проекта, структура и запуск |
| `pom.xml` | Версия проекта, Java 21 и зависимости Maven |
| `check.ps1` | Сборка и запуск автоматических проверок |
| `SHEET_SCHEMA.md` | Названия листов и колонок Google Sheets |
| `.env.example` | Пример настроек без настоящих секретов |
| `.gitignore` | Исключения Git: ключи, журналы, локальные данные и сборка |

После сборки появляется `target/`; Maven хранит библиотеки в своём кэше. В рабочей локальной копии также могут быть `.local-state/` и `.local-output/` с состоянием и резервными копиями — они не публикуются.

Ключевые файлы: `MenuCatalog.java` — кнопки и список команд; `LocalBotEngine.java` — диалог (используется и в облаке); `GoogleSheetsLedgerSync.java` — запись таблиц. `Legacy` в имени означает совместимость со старой таблицей, а не неиспользуемый код.

## Build and checks

Install JDK 21 and Maven, then run:

```sh
mvn test-compile dependency:build-classpath -Dmdep.outputFile=target/test-classpath.txt
```

Tests are standalone Java programs rather than JUnit tests. Run `check.ps1` from PowerShell with Java and Maven on PATH to compile and execute them. `mvn test` alone does not execute these checks.

## Configuration

See `.env.example` for variable names. Supply actual values through your deployment environment. Numeric identifiers in the public sources are examples. The copy-only probe is intentionally restricted: replace its example copy ID only for your designated disposable test spreadsheet.

To deploy Java functions, package `pom.xml` and `src/` at the ZIP root. Entry points are `financeos.cloud.CloudVkIngress`, `financeos.cloud.CloudVkProcessor` and `financeos.cloud.CloudReminders`. Only the VK ingress needs public invocation; it validates the callback secret and community ID.

## Versions and branches

- `main`: current Java code.
- `legacy/telegram`: preserved Python Telegram implementation and its history.
- `codex/...`: feature and fix branches.
- `v0.9.0`: Java release with original/copy Sheets synchronization.

The public edition removes personal deployment identifiers. It is not a byte-for-byte export of a running deployment. Financial snapshots, logs, credentials and local state are excluded.
