param(
    [string]$Jdk = 'D:\Program Files\Android\Android Studio\jbr',
    [string]$AndroidJar = 'D:\ProgramData\Android\Sdk\platforms\android-33\android.jar'
)
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$taskOutput = Join-Path $env:TEMP ('skb-session-tests-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $taskOutput | Out-Null
$taskSources = @(
    Get-ChildItem -LiteralPath (Join-Path $taskRoot 'src\main\java') -Recurse -Filter '*.java'
    Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot '..\app\src\main\java') -Recurse -Filter '*.java'
    Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src\main\java') -Recurse -Filter '*.java'
    Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src\test\java') -Recurse -Filter '*.java'
)
& (Join-Path $Jdk 'bin\javac.exe') '-J-Duser.language=en' -encoding UTF-8 --release 8 -classpath $AndroidJar -d $taskOutput @($taskSources.FullName)
if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed' }
& (Join-Path $Jdk 'bin\java.exe') -cp $taskOutput com.wx.skb.hostapp.ManagedSerialSessionTest
if ($LASTEXITCODE -ne 0) { throw 'Session tests failed' }
Write-Output "Test classes: $taskOutput"
