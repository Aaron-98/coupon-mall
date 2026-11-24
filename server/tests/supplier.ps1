param([int]$Port = 8796)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot/..
& ./run.ps1 -BuildOnly
if ($LASTEXITCODE -ne 0) { throw 'Build failed' }
$folder = Join-Path $PWD ('target/supplier-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $folder | Out-Null
$keystore = Join-Path $folder 'fixture.p12'
# This key only authenticates an ephemeral localhost HTTPS fixture.
$fixturePassword = [Guid]::NewGuid().ToString('N')
$certificate = Start-Process -FilePath (Get-Command keytool.exe).Source -ArgumentList '-genkeypair', '-alias', 'fixture', '-keyalg', 'RSA', '-keysize', '2048', '-storetype', 'PKCS12', '-keystore', $keystore, '-storepass', $fixturePassword, '-keypass', $fixturePassword, '-dname', 'CN=localhost', '-ext', 'SAN=dns:localhost', '-validity', '1' -WindowStyle Hidden -Wait -PassThru -RedirectStandardOutput (Join-Path $folder 'keytool.stdout') -RedirectStandardError (Join-Path $folder 'keytool.stderr')
if ($certificate.ExitCode -ne 0) { throw 'Fixture certificate generation failed' }
$env:COUPON_FEED_URL = "https://localhost:$Port/feed"
$env:COUPON_FEED_TOKEN = 'fixture-token'
& javac -encoding UTF-8 -cp 'target/classes;target/lib/*' -d target/classes tests/SupplierTest.java
if ($LASTEXITCODE -ne 0) { throw 'Supplier test compilation failed' }
& java "-Djavax.net.ssl.trustStore=$keystore" "-Djavax.net.ssl.trustStorePassword=$fixturePassword" -cp 'target/classes;target/lib/*' mall.SupplierTest $keystore $fixturePassword
if ($LASTEXITCODE -ne 0) { throw 'Supplier transport tests failed' }
