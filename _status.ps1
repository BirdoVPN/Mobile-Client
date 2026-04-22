$ErrorActionPreference = 'Continue'
Write-Host "=== MERGED ===" -ForegroundColor Green
$merged = gh pr list --repo BirdoVPN/Mobile-Client --state merged --limit 30 --json number,title,mergedAt | ConvertFrom-Json
foreach ($p in $merged) { Write-Host ("#{0} {1} {2}" -f $p.number, $p.mergedAt, $p.title) }
Write-Host ""
Write-Host "=== OPEN ===" -ForegroundColor Cyan
$open = gh pr list --repo BirdoVPN/Mobile-Client --state open --limit 30 --json number,title,mergeStateStatus | ConvertFrom-Json
foreach ($p in $open) { Write-Host ("#{0} [{1}] {2}" -f $p.number, $p.mergeStateStatus, $p.title) }
Write-Host ""
Write-Host "=== FAILURES ===" -ForegroundColor Red
foreach ($p in $open) {
  $rollup = gh pr view $p.number --repo BirdoVPN/Mobile-Client --json statusCheckRollup | ConvertFrom-Json
  $failed = @($rollup.statusCheckRollup | Where-Object { $_.conclusion -eq "FAILURE" })
  if ($failed.Count -gt 0) {
    $names = ($failed | ForEach-Object { $_.name }) -join ", "
    Write-Host ("#{0}: {1}" -f $p.number, $names)
  }
}
