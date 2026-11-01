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

## Source layout

| Directory | Purpose |
| --- | --- |
| `src/main/java/financeos/core` | Money calculations, plans, debts, goals, schedules, undo |
| `src/main/java/financeos/vk` | Commands, menus, dialog state, VK clients, image rendering |
| `src/main/java/financeos/data` | Legacy sheet schema, adapters, projection and synchronization |
| `src/main/java/financeos/cloud` | Cloud handlers, IAM metadata token, conditional storage writes |
| `src/test/java/financeos` | Executable test classes with synthetic data |

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
