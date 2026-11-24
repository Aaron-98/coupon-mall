param([int]$Port = 8789)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
& "$PSScriptRoot/run.ps1" -BuildOnly
if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed' }
& javac -encoding UTF-8 -cp 'target/classes;target/lib/*' -d target/classes tests/PricingTest.java tests/LifecycleTest.java
if ($LASTEXITCODE -ne 0) { throw 'Pricing test compilation failed' }
& java -cp 'target/classes;target/lib/*' mall.PricingTest
if ($LASTEXITCODE -ne 0) { throw 'Pricing tests failed' }
& java -cp 'target/classes;target/lib/*' mall.LifecycleTest
if ($LASTEXITCODE -ne 0) { throw 'Lifecycle tests failed' }
& python tests/test_images.py
if ($LASTEXITCODE -ne 0) { throw 'Image validation tests failed' }

$folder = Join-Path $PSScriptRoot ("target/test-" + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $folder | Out-Null
$env:COUPON_DATA_DIR = $folder
$env:COUPON_ADMIN_KEY = [Guid]::NewGuid().ToString('N')
$env:COUPON_TEST_API = "http://127.0.0.1:$Port/api/v1"
$env:COUPON_BIND = '127.0.0.1'
$sample = Join-Path $folder 'ocr-sample.png'
& python tests/sample_image.py $sample
if ($LASTEXITCODE -ne 0) { throw 'Failed to generate the OCR fixture' }

$server = Start-Process -FilePath (Get-Command java.exe).Source -ArgumentList '-cp', 'target/classes;target/lib/*', 'mall.Server', $Port, 'demo' -WorkingDirectory $PSScriptRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $folder 'stdout.log') -RedirectStandardError (Join-Path $folder 'stderr.log')
try {
  $ready = $false
  for ($i = 0; $i -lt 20; $i++) {
    $server.Refresh()
    if ($server.HasExited) { throw (Get-Content (Join-Path $folder 'stderr.log') -Raw) }
    try {
      $health = Invoke-RestMethod "$env:COUPON_TEST_API/health" -TimeoutSec 1
      if ($health.data.demo) { $ready = $true; break }
    } catch { Start-Sleep -Milliseconds 250 }
  }
  if (!$ready) { throw 'The test server did not start' }
  & node tests/smoke.mjs $sample
  if ($LASTEXITCODE -ne 0) { throw 'API smoke tests failed' }
  & node tests/client-smoke.mjs
  if ($LASTEXITCODE -ne 0) { throw 'Mini program page tests failed' }
} finally {
  if (!$server.HasExited) { Stop-Process -Id $server.Id }
}
