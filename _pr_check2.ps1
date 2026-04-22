$ErrorActionPreference = "Continue"
Write-Host "=== OPEN PR STATES ===" -ForegroundColor Cyan
$open = gh pr list --repo BirdoVPN/Mobile-Client --state open --limit 30 --json number,title,mergeStateStatus | ConvertFrom-Json
foreach ($p in $open) { Write-Host ("#{0} [{1}] {2}" -f $p.number, $p.mergeStateStatus, $p.title) }

Write-Host ""
Write-Host "=== MERGED (last 25) ===" -ForegroundColor Green
$merged = gh pr list --repo BirdoVPN/Mobile-Client --state merged --limit 25 --json number,title,mergedAt | ConvertFrom-Json
foreach ($p in $merged) { Write-Host ("#{0} {1} {2}" -f $p.number, $p.mergedAt, $p.title) }

Write-Host ""
Write-Host "=== Failure Investigation ===" -ForegroundColor Yellow
$failingPrs = @()
foreach ($p in $open) {
  $n = $p.number
  $rollup = gh pr view $n --repo BirdoVPN/Mobile-Client --json statusCheckRollup,title | ConvertFrom-Json
  $fails = @($rollup.statusCheckRollup | Where-Object { $_.conclusion -eq "FAILURE" })
  if ($fails.Count -gt 0) {
    $failingPrs += $n
    Write-Host ""
    Write-Host ("--- PR #{0} : {1} ---" -f $n, $rollup.title) -ForegroundColor Red
    foreach ($f in $fails) { Write-Host ("  {0} | {1}" -f $f.name, $f.detailsUrl) }
    $url = $fails[0].detailsUrl
    $rid = ($url -split '/runs/')[1] -split '/' | Select-Object -First 1
    if ($rid) {
      Write-Host ("  Run {0} log excerpt:" -f $rid) -ForegroundColor DarkYellow
      $log = gh run view $rid --repo BirdoVPN/Mobile-Client --log-failed 2>&1
      $log | Select-String -Pattern 'error','FAILED','went wrong','Exception','warning' -SimpleMatch | Select-Object -First 15 | ForEach-Object { Write-Host ("    " + $_.ToString().Substring(0,[Math]::Min(220,$_.ToString().Length))) }
    }
  }
}
Write-Host ""
Write-Host ("Failing PRs: " + ($failingPrs -join ', ')) -ForegroundColor Red
