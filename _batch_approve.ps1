$ErrorActionPreference = 'Continue'
$prs = @(22,23,24,25,26,27,28,29,30,31,32,33,34,35,36)
foreach ($n in $prs) {
  Write-Host "=== #$n ===" -ForegroundColor Cyan
  gh pr review $n --repo BirdoVPN/Mobile-Client --approve --body "auto" 2>&1 | Select-Object -First 1
  gh pr merge $n --repo BirdoVPN/Mobile-Client --squash --auto --delete-branch 2>&1 | Select-Object -First 1
}
