param([int]$Seconds = 900, [int]$Users = 50, [int]$Port = 8791, [switch]$WithImages)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot/..
& ./run.ps1 -BuildOnly
if ($LASTEXITCODE -ne 0) { throw 'Build failed' }
$folder = Join-Path $PWD ('target/load-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $folder | Out-Null
$env:COUPON_DATA_DIR = $folder
$env:COUPON_DAILY_TASK_BUDGET = '2000'
$env:COUPON_TEST_API = "http://127.0.0.1:$Port/api/v1"
$env:COUPON_LOAD_SECONDS = "$Seconds"
$env:COUPON_LOAD_USERS = "$Users"
$env:COUPON_LOAD_REPORT = Join-Path $folder 'report.json'
$env:COUPON_LOAD_IMAGE = ''
if ($WithImages) {
  $env:COUPON_LOAD_IMAGE = Join-Path $folder 'sample.png'
  & python tests/sample_image.py $env:COUPON_LOAD_IMAGE
  if ($LASTEXITCODE -ne 0) { throw 'Image fixture generation failed' }
}
$env:COUPON_BIND = '127.0.0.1'
$server = Start-Process -FilePath (Get-Command java.exe).Source -ArgumentList '-cp', 'target/classes;target/lib/*', 'mall.Server', $Port, 'demo' -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $folder 'stdout.log') -RedirectStandardError (Join-Path $folder 'stderr.log')
try {
  for ($i=0; $i -lt 30; $i++) {
    try { $null = Invoke-RestMethod "$env:COUPON_TEST_API/health"; break } catch { Start-Sleep -Milliseconds 200 }
  }
  & node tests/load.mjs
  if ($LASTEXITCODE -ne 0) { throw "Load test failed; report: $env:COUPON_LOAD_REPORT" }
  Write-Output "Report: $env:COUPON_LOAD_REPORT"
} finally { if (!$server.HasExited) { Stop-Process -Id $server.Id } }
