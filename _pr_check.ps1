$ErrorActionPreference = "Continue"
Write-Host "=== OPEN PR STATES ===" -ForegroundColor Cyan
gh pr list --repo BirdoVPN/Mobile-Client --state open --limit 30 --json number,title,mergeStateStatus,statusCheckRollup --template '{{range .}}#{{.number}} [{{.mergeStateStatus}}] {{.title}}{{"\n"}}{{end}}'

Write-Host ""
Write-Host "=== MERGED (last 25) ===" -ForegroundColor Green
gh pr list --repo BirdoVPN/Mobile-Client --state merged --limit 25 --json number,title,mergedAt --template '{{range .}}#{{.number}} {{.mergedAt}} {{.title}}{{"\n"}}{{end}}'

Write-Host ""
Write-Host "=== Failure Investigation ===" -ForegroundColor Yellow
$openPrs = gh pr list --repo BirdoVPN/Mobile-Client --state open --limit 50 --json number --template '{{range .}}{{.number}} {{end}}'
$failingPrs = @()
foreach ($n in ($openPrs -split ' ')) {
  if ($n -match '^\d+$') {
    $rollup = gh pr view $n --repo BirdoVPN/Mobile-Client --json statusCheckRollup | ConvertFrom-Json
    $fails = @($rollup.statusCheckRollup | Where-Object { $_.conclusion -eq "FAILURE" })
    if ($fails.Count -gt 0) {
      $failingPrs += $n
      $title = gh pr view $n --repo BirdoVPN/Mobile-Client --json title --template '{{.title}}'
      Write-Host ""
      Write-Host "--- PR #$n : $title ---" -ForegroundColor Red
      foreach ($f in $fails) { Write-Host ("  {0} | {1}" -f $f.name, $f.detailsUrl) }
      $url = $fails[0].detailsUrl
      $rid = ($url -split '/runs/')[1] -split '/' | Select-Object -First 1
      if ($rid) {
        Write-Host "  Run $rid log excerpt:" -ForegroundColor DarkYellow
        $log = gh run view $rid --repo BirdoVPN/Mobile-Client --log-failed 2>&1
        $log | Select-String -Pattern 'error|FAILED|What went wrong|Exception' -SimpleMatch | Select-Object -First 12 | ForEach-Object { Write-Host "    $_" }
      }
    }
  }
}
Write-Host ""
Write-Host "Failing PRs: $($failingPrs -join ', ')" -ForegroundColor Red
