$ErrorActionPreference = 'Continue'
foreach ($n in @(27,28,29,34,35,36)) {
  Write-Host ""
  Write-Host "=== #$n ===" -ForegroundColor Yellow
  $rollup = gh pr view $n --repo BirdoVPN/Mobile-Client --json statusCheckRollup,title | ConvertFrom-Json
  Write-Host $rollup.title
  $failed = @($rollup.statusCheckRollup | Where-Object { $_.conclusion -eq "FAILURE" })
  foreach ($f in $failed) {
    $rid = ($f.detailsUrl -split '/runs/')[1] -split '/' | Select-Object -First 1
    Write-Host "[$($f.name)] run $rid"
    if ($rid) {
      gh run view $rid --repo BirdoVPN/Mobile-Client --log-failed 2>&1 | Select-String -Pattern "error:|Could not|incompatible|What went wrong|FAILED" | Select-Object -First 5 | ForEach-Object { Write-Host ("  " + $_.Line.Substring(0, [Math]::Min(180, $_.Line.Length))) }
      break  # one error per PR is enough
    }
  }
}
