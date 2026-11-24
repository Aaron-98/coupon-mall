$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot/..
& ./run.ps1 -BuildOnly
if ($LASTEXITCODE -ne 0) { throw 'Build failed' }
$folder = Join-Path $PWD ('target/ocr-' + [Guid]::NewGuid().ToString('N'))
& python tests/ocr_dataset.py $folder
if ($LASTEXITCODE -ne 0) { throw 'Dataset generation failed' }
& javac -encoding UTF-8 -cp 'target/classes;target/lib/*' -d target/classes tests/OcrAcceptance.java
if ($LASTEXITCODE -ne 0) { throw 'Test build failed' }
& java -cp 'target/classes;target/lib/*' mall.OcrAcceptance $folder
if ($LASTEXITCODE -ne 0) { throw "OCR acceptance failed; report: $folder/report.json" }
Write-Output "Report: $folder/report.json"
