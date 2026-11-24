param([int]$Port = 8793)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot/..
& ./run.ps1 -BuildOnly
if ($LASTEXITCODE -ne 0) { throw 'Build failed' }
$folder = Join-Path $PWD ('target/private-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $folder | Out-Null
$env:COUPON_DATA_DIR = $folder
$env:COUPON_AUTHORIZED_FEED = Join-Path $folder 'feed.json'
$env:COUPON_FEED_URL = ''
$env:COUPON_ADMIN_KEY = [Guid]::NewGuid().ToString('N')
$env:COUPON_OPERATOR_KEY = [Guid]::NewGuid().ToString('N')
$env:COUPON_AUDITOR_KEY = [Guid]::NewGuid().ToString('N')
$env:COUPON_APPROVED_TARGET_APPID = 'wx0123456789abcdef'
$env:COUPON_TEST_API = "http://127.0.0.1:$Port/api/v1"
$env:COUPON_PRIVATE_REPORT = Join-Path $folder 'report.json'
$env:COUPON_BIND = '127.0.0.1'
$server = Start-Process -FilePath (Get-Command java.exe).Source -ArgumentList '-cp', 'target/classes;target/lib/*', 'mall.Server', $Port, 'private' -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $folder 'stdout.log') -RedirectStandardError (Join-Path $folder 'stderr.log')
try {
  for ($i=0; $i -lt 30; $i++) {
    try { $null = Invoke-RestMethod "$env:COUPON_TEST_API/health"; break } catch { Start-Sleep -Milliseconds 200 }
  }
  & node tests/private-api.mjs
  if ($LASTEXITCODE -ne 0) { throw 'Private API contract failed' }
  Write-Output "Report: $env:COUPON_PRIVATE_REPORT"
} finally { if (!$server.HasExited) { Stop-Process -Id $server.Id } }
