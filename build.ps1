param([switch]$SkipTests)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$env:JAVA_HOME = (Resolve-Path (Join-Path $root '..\toolchain\jdk21\jdk21.0.12_12')).Path
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
$maven = Join-Path $root '..\toolchain\maven\apache-maven-3.10.0\bin\mvn.cmd'
if (-not (Test-Path -LiteralPath $java) -or -not (Test-Path -LiteralPath $maven)) {
    throw 'Local Java toolchain not found'
}
Push-Location $root
try {
    & $maven -q '-Dmaven.repo.local=.m2' test-compile
    if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed' }
    if ($SkipTests) {
        Write-Host 'Local compilation passed.'
        return
    }
    $jars = @(Get-ChildItem -Path (Join-Path $root '.m2\com\fasterxml\jackson') -Filter '*.jar' -Recurse | ForEach-Object FullName)
    $classpath = (@((Join-Path $root 'target\test-classes'), (Join-Path $root 'target\classes')) + $jars) -join ';'
    & $java -cp $classpath financeos.vk.Release18Test
    if ($LASTEXITCODE -ne 0) { throw "Release18 checks failed" }
    & $java -cp $classpath financeos.vk.OperationCategoryMoveTest
    if ($LASTEXITCODE -ne 0) { throw "Operation category move checks failed" }
    & $java -cp $classpath financeos.vk.InterfaceAgreementTest
    if ($LASTEXITCODE -ne 0) { throw "Interface agreement checks failed" }
    & $java -cp $classpath financeos.vk.ReceiptTotalRegressionTest
    if ($LASTEXITCODE -ne 0) { throw "Receipt total regression failed" }
    & $java -cp $classpath financeos.cloud.ReceiptSharedTest
    if ($LASTEXITCODE -ne 0) { throw "Shared receipt test failed" }
    & $java -cp $classpath financeos.vk.ReceiptOcrTest
    if ($LASTEXITCODE -ne 0) { throw "Receipt OCR test failed" }
    & $java -cp $classpath financeos.vk.ReceiptFlowTest
    if ($LASTEXITCODE -ne 0) { throw "Receipt flow test failed" }
    & $java -cp $classpath financeos.vk.AuditRegressionTest
    if ($LASTEXITCODE -ne 0) { throw "Audit regression test failed" }
    & $java -cp $classpath financeos.vk.NewUnplannedExpenseTest
    if ($LASTEXITCODE -ne 0) { throw "Unplanned expense test failed" }
    & $java -cp $classpath financeos.vk.NewExpensePlanTest
    if ($LASTEXITCODE -ne 0) { throw "New expense plan test failed" }
    & $java -cp $classpath financeos.vk.CategoryExpenseHistoryTest
    if ($LASTEXITCODE -ne 0) { throw "Category expense history tests failed" }
    & $java -cp $classpath financeos.core.QuickOperationParserTest
    if ($LASTEXITCODE -ne 0) { throw "Quick parser tests failed" }
    & $java -cp $classpath financeos.vk.QuickEntryTest
    if ($LASTEXITCODE -ne 0) { throw "Quick entry tests failed" }
    & $java -cp $classpath financeos.core.MonthEndBalanceServiceTest
    if ($LASTEXITCODE -ne 0) { throw "Month-end forecast tests failed" }
    & $java -cp $classpath financeos.cloud.FinanceSnapshotsTest
    if ($LASTEXITCODE -ne 0) { throw "Financial recovery checks failed" }
    & $java -cp $classpath financeos.cloud.StorageRetentionTest
    if ($LASTEXITCODE -ne 0) { throw "Storage retention checks failed" }
    & $java -cp $classpath financeos.vk.HistoryNavigationTest
    if ($LASTEXITCODE -ne 0) { throw "History navigation checks failed" }
    & $java -cp $classpath financeos.cloud.SectionPlanFactSharedTest
    if ($LASTEXITCODE -ne 0) { throw "Shared section plan/fact test failed" }
    & $java -cp $classpath financeos.vk.SectionPlanFactDetailTest
    if ($LASTEXITCODE -ne 0) { throw "Section plan/fact detail test failed" }
    & $java -cp $classpath financeos.core.MonthlyPlanReleaseTest
    if ($LASTEXITCODE -ne 0) { throw 'MonthlyPlanReleaseTest failed' }
    & $java -cp $classpath financeos.vk.PlanFactReadabilityTest
    if ($LASTEXITCODE -ne 0) { throw "Plan/fact readability checks failed" }
    & $java -cp $classpath financeos.vk.CategoryMaintenanceTest
    if ($LASTEXITCODE -ne 0) { throw "Category cleanup/setup checks failed" }
    & $java -cp $classpath financeos.vk.IncomeMenuTest
    if ($LASTEXITCODE -ne 0) { throw "Income menu checks failed" }
    & $java -cp $classpath financeos.cloud.SharedChatStateTest
    if ($LASTEXITCODE -ne 0) { throw "Shared chat checks failed" }
    & $java -cp $classpath financeos.vk.CategorySectionsTest
    if ($LASTEXITCODE -ne 0) { throw "Section management checks failed" }
    & $java -cp $classpath financeos.vk.ExistingPlanEditTest
    if ($LASTEXITCODE -ne 0) { throw "Existing plan edit checks failed" }
    & $java -cp $classpath financeos.cloud.CloudChatCleanupTest
    if ($LASTEXITCODE -ne 0) { throw "Cloud cleanup checks failed" }
    & $java -cp $classpath financeos.vk.ChatCleanupFlowTest
    if ($LASTEXITCODE -ne 0) { throw "Cleanup UI checks failed" }
    & $java -cp $classpath financeos.vk.VkChatCleanerTest
    if ($LASTEXITCODE -ne 0) { throw "Cleanup API checks failed" }
    & $java -cp $classpath financeos.core.DebtBudgetServiceTest
    if ($LASTEXITCODE -ne 0) { throw "Debt reserve checks failed" }
    & $java -cp $classpath financeos.vk.PlanningExperienceTest
    if ($LASTEXITCODE -ne 0) { throw "Planning experience checks failed" }
    & $java -cp $classpath financeos.learning.FirstFinanceTest
    if ($LASTEXITCODE -ne 0) { throw "Learning example checks failed" }
    & $java -cp $classpath financeos.cloud.CloudMemoryCacheTest
    if ($LASTEXITCODE -ne 0) { throw "Memory cache checks failed" }
    & $java -cp $classpath financeos.cloud.CachedObjectStoreTest
    if ($LASTEXITCODE -ne 0) { throw "Validated object cache checks failed" }
    & $java -cp $classpath financeos.vk.DeleteOperationsTest
    if ($LASTEXITCODE -ne 0) { throw "Operation deletion checks failed" }
    & $java -cp $classpath financeos.vk.NavigationRefreshTest
    if ($LASTEXITCODE -ne 0) { throw "Navigation refresh checks failed" }
    & $java -cp $classpath financeos.core.PlanCopyServiceTest
    if ($LASTEXITCODE -ne 0) { throw 'Plan copying calculations failed' }
    & $java -cp $classpath financeos.vk.PlanCopyWorkflowTest
    if ($LASTEXITCODE -ne 0) { throw 'Plan copying workflow failed' }
    & $java -cp $classpath financeos.core.OperationEditorTest
    if ($LASTEXITCODE -ne 0) { throw 'Operation edit calculation checks failed' }
    & $java -cp $classpath financeos.vk.EditOperationsTest
    if ($LASTEXITCODE -ne 0) { throw 'Operation edit workflow checks failed' }
    & $java -cp $classpath financeos.core.ReportServiceTest
    if ($LASTEXITCODE -ne 0) { throw 'Finance checks failed' }
    & $java -cp $classpath financeos.cloud.ExpenseChartSharedTest
    if ($LASTEXITCODE -ne 0) { throw 'ExpenseChartSharedTest failed' }
    & $java -cp $classpath financeos.vk.ExpenseChartPagesTest
    if ($LASTEXITCODE -ne 0) { throw 'ExpenseChartPagesTest failed' }
    & $java -cp $classpath financeos.core.ChartServiceTest
    if ($LASTEXITCODE -ne 0) { throw 'Chart checks failed' }
    & $java -cp $classpath financeos.core.RecurringBookTest
    if ($LASTEXITCODE -ne 0) { throw 'Recurring payment checks failed' }
    & $java -cp $classpath financeos.vk.RecurringReminderServiceTest
    if ($LASTEXITCODE -ne 0) { throw 'Recurring reminder checks failed' }
    & $java -cp $classpath financeos.vk.DailyReminderSettingsTest
    if ($LASTEXITCODE -ne 0) { throw 'Morning reminder checks failed' }
    & $java -cp $classpath financeos.vk.LocalVkWebhookServerTest
    if ($LASTEXITCODE -ne 0) { throw 'VK checks failed' }
    & $java -cp $classpath financeos.vk.DurableEventInboxTest
    if ($LASTEXITCODE -ne 0) { throw 'VK inbox checks failed' }
    & $java -cp $classpath financeos.vk.VkGroupLongPollClientTest
    if ($LASTEXITCODE -ne 0) { throw 'VK Long Poll checks failed' }
    & $java -cp $classpath financeos.vk.VkConversationMembersClientTest
    if ($LASTEXITCODE -ne 0) { throw 'VK private chat checks failed' }
    & $java -cp $classpath financeos.vk.VkMessagesClientTest
    if ($LASTEXITCODE -ne 0) { throw 'VK delivery checks failed' }
    & $java -cp $classpath financeos.vk.VkHistoryClientTest
    if ($LASTEXITCODE -ne 0) { throw 'VK history ordering checks failed' }
    & $java -cp $classpath financeos.cloud.ObjectStorageStateStoreTest
    if ($LASTEXITCODE -ne 0) { throw 'Cloud state storage checks failed' }
    & $java -cp $classpath financeos.cloud.CloudVkIngressTest
    if ($LASTEXITCODE -ne 0) { throw 'Cloud callback checks failed' }
    & $java -cp $classpath financeos.cloud.CloudVkProcessorTest
    if ($LASTEXITCODE -ne 0) { throw 'Cloud ordered processing checks failed' }
    & $java -cp $classpath financeos.vk.MenuCatalogTest
    if ($LASTEXITCODE -ne 0) { throw 'VK menu checks failed' }
    & $java -cp $classpath financeos.vk.LocalBotEngineTest
    if ($LASTEXITCODE -ne 0) { throw 'Local bot action checks failed' }
    & $java -cp $classpath financeos.vk.LocalStateFileTest
    if ($LASTEXITCODE -ne 0) { throw 'Local state restoration checks failed' }
    & $java -cp $classpath financeos.vk.CopyStateMirrorTest
    if ($LASTEXITCODE -ne 0) { throw 'Test-copy state sync checks failed' }
    & $java -cp $classpath financeos.vk.SingleInstanceGuardTest
    if ($LASTEXITCODE -ne 0) { throw 'Local VK single-instance checks failed' }
    & $java -cp $classpath financeos.vk.LocalVkRestartInputTest
    if ($LASTEXITCODE -ne 0) { throw 'Local VK restart input checks failed' }
    & $java -cp $classpath financeos.data.GoogleSheetsReadOnlyTest
    if ($LASTEXITCODE -ne 0) { throw 'Google Sheets checks failed' }
    & $java -cp $classpath financeos.data.GoogleSheetsCopyProbeTest
    if ($LASTEXITCODE -ne 0) { throw 'Google Sheets copy-only guard failed' }
    & $java -cp $classpath financeos.data.LegacyDataMapperTest
} finally {
    Pop-Location
}
if ($LASTEXITCODE -ne 0) { throw 'Local checks failed' }
Write-Host 'Local build and checks passed.'
