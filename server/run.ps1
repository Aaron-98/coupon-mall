param([switch]$Demo, [int]$Port = 8787, [switch]$BuildOnly)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$mavenRepo = if ($env:COUPON_MAVEN_REPO) { $env:COUPON_MAVEN_REPO } else {
  Join-Path ([Environment]::GetFolderPath('UserProfile')) '.m2/repository'
}
$repo = Join-Path $mavenRepo 'com/fasterxml/jackson/core'
$jars = @('jackson-annotations/2.21/jackson-annotations-2.21.jar', 'jackson-core/2.21.2/jackson-core-2.21.2.jar', 'jackson-databind/2.21.2/jackson-databind-2.21.2.jar') |
  ForEach-Object { Join-Path $repo $_ }
if ($jars | Where-Object { !(Test-Path -LiteralPath $_) }) {
  & mvn.cmd "-Dmaven.repo.local=$mavenRepo" compile
  if ($LASTEXITCODE -ne 0) { throw 'Maven dependency resolution failed' }
}
New-Item -ItemType Directory -Force target/classes, target/lib | Out-Null
$localJars = $jars | ForEach-Object {
  $destination = Join-Path 'target/lib' ([IO.Path]::GetFileName($_))
  Copy-Item -LiteralPath $_ -Destination $destination -Force
  $destination
}
$sources = Get-ChildItem -LiteralPath src/main/java/mall -Filter '*.java' | ForEach-Object FullName
$classpath = $localJars -join [IO.Path]::PathSeparator
& javac -encoding UTF-8 -cp $classpath -d target/classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed' }
if ($BuildOnly) { return }
$setting = if ($Demo) { 'demo' } else { 'private' }
& java -cp ("target/classes" + [IO.Path]::PathSeparator + $classpath) mall.Server $Port $setting
exit $LASTEXITCODE
