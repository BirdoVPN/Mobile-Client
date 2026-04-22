$ErrorActionPreference = 'Continue'
# Rebase BEHIND PRs
foreach ($n in @(2,3,5)) {
  Write-Host "Rebase #$n" -ForegroundColor Yellow
  gh pr comment $n --repo BirdoVPN/Mobile-Client --body "@dependabot rebase" 2>&1 | Select-Object -First 1
}
# Approve+auto-merge new PR #20
Write-Host "Setup #20"
gh pr review 20 --repo BirdoVPN/Mobile-Client --approve --body "auto" 2>&1 | Select-Object -First 1
gh pr merge 20 --repo BirdoVPN/Mobile-Client --squash --auto --delete-branch 2>&1 | Select-Object -First 1

# Investigate failures on smaller bumps: #10 (okhttp), #13 (turbine), #8 (compose-bom)
foreach ($n in @(10, 13, 8)) {
  Write-Host ""
  Write-Host "=== PR #$n failures ===" -ForegroundColor Red
  $rollup = gh pr view $n --repo BirdoVPN/Mobile-Client --json statusCheckRollup | ConvertFrom-Json
  $failed = @($rollup.statusCheckRollup | Where-Object { $_.conclusion -eq "FAILURE" })
  foreach ($f in $failed) {
    Write-Host "Job: $($f.name)"
    $rid = ($f.detailsUrl -split '/runs/')[1] -split '/' | Select-Object -First 1
    if ($rid) {
      gh run view $rid --repo BirdoVPN/Mobile-Client --log-failed 2>&1 | Select-String -Pattern "error:|FAIL|##\[error\]|What went wrong|Could not|incompatible" | Select-Object -First 6 | ForEach-Object { Write-Host ("  " + $_.Line.Substring(0, [Math]::Min(200, $_.Line.Length))) }
    }
  }
}
