# FinanceOS · Java + VK

Java 21 finance bot for VK, running on Yandex Cloud Functions. Current source baseline: v0.18.15. The original repository history is preserved.

The authoritative budget state lives in private Object Storage. VK callbacks are validated by an ingress function; a processor handles durable state, responses, and duplicate-event protection. A reminder function shares the persistent outbox. Lockbox supplies runtime secrets. Google Sheets adapters remain for legacy compatibility; the current runtime does not require Google Sheets.

## Source layout

| Directory | Responsibility |
|---|---|
| src/main/java/financeos/core | Financial calculations, plans, debts, goals, schedules |
| src/main/java/financeos/vk | Conversation, buttons, input validation, VK API, charts, receipt flow |
| src/main/java/financeos/cloud | Callback ingress, ordered processing, durable outbox, storage, reminders |
| src/main/java/financeos/data | Legacy data mapping and optional Sheets adapters |
| src/test/java/financeos | Executable checks using synthetic data and local HTTP servers |
| scripts | Portable build/test runner and manual OIDC deployment |
| .github/workflows | Pull request checks and main-only manual deployment |

## Build and checks

Install Java 21, Maven and Python 3.12+, then run `python scripts/ci.py`. This compiles and runs all registered executable tests from build.ps1 (Maven alone does not execute those test mains), then creates target/ci with a deployment ZIP, checksum and source commit. No cloud credentials are needed for tests.

Deployment configuration tests: `python -m unittest discover -s scripts -p 'test_*.py' -v`.

## Deployment

[Setup and operation](SETUP_GITHUB_DEPLOY.md). Java checks run on pull requests and main/codex branches. Deployment is manual from main only, dry-run by default, with an exact version confirmation. It uses GitHub OIDC credentials; no permanent cloud key is committed. Production access must be connected once before the workflow is usable.

Local state, keys, tokens, personal backups, logs and build outputs must never be committed. Button layouts and report formatting require agreement before redesign; adding charts preserves existing report text and controls.
