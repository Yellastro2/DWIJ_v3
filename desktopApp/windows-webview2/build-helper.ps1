param(
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '..\build\generated\webview2-resources\windows-webview2')
)

$ErrorActionPreference = 'Stop'
$sdkVersion = '1.0.4258.31'
$sdkCache = Join-Path $PSScriptRoot "..\build\webview2-sdk\$sdkVersion"
$packagePath = Join-Path $sdkCache 'sdk.nupkg'
$sdkDirectory = Join-Path $sdkCache 'package'
$compilerPath = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (-not (Test-Path -LiteralPath $compilerPath)) {
    throw '[buildVkWebView2] Не найден компилятор .NET Framework для Windows x64'
}

New-Item -ItemType Directory -Path $sdkCache, $OutputDirectory -Force | Out-Null
$sdkFiles = @('lib/net462/Microsoft.Web.WebView2.Core.dll',
    'lib/net462/Microsoft.Web.WebView2.WinForms.dll', 'runtimes/win-x64/native/WebView2Loader.dll')
$missingSdkFiles = @($sdkFiles | Where-Object { -not (Test-Path -LiteralPath (Join-Path $sdkDirectory $_)) })
if ($missingSdkFiles.Count -gt 0) {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $packageUrl = "https://api.nuget.org/v3-flatcontainer/microsoft.web.webview2/$sdkVersion/microsoft.web.webview2.$sdkVersion.nupkg"
    Write-Host '[buildVkWebView2] Получаем закреплённую версию Microsoft WebView2 SDK из NuGet'
    if (-not (Test-Path -LiteralPath $packagePath)) {
        Invoke-WebRequest -Uri $packageUrl -OutFile "$packagePath.download" -UseBasicParsing
        Move-Item -LiteralPath "$packagePath.download" -Destination $packagePath -Force
    }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $sdkArchive = [IO.Compression.ZipFile]::OpenRead($packagePath)
    try {
        foreach ($sdkFile in $sdkFiles) {
            $entry = $sdkArchive.GetEntry($sdkFile)
            if ($null -eq $entry) { throw '[buildVkWebView2] В пакете SDK отсутствует необходимый файл' }
            $destination = Join-Path $sdkDirectory $sdkFile
            New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($destination)) -Force | Out-Null
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $destination, $true)
        }
    } finally { $sdkArchive.Dispose() }
}

$coreDll = Join-Path $sdkDirectory 'lib\net462\Microsoft.Web.WebView2.Core.dll'
$formsDll = Join-Path $sdkDirectory 'lib\net462\Microsoft.Web.WebView2.WinForms.dll'
$loaderDll = Join-Path $sdkDirectory 'runtimes\win-x64\native\WebView2Loader.dll'
$helperExe = Join-Path $OutputDirectory 'Dwij.VkWebView2.exe'
$sourceFile = Join-Path $PSScriptRoot 'VkWebView2Host.cs'

& $compilerPath /nologo /target:winexe /platform:x64 /optimize+ /codepage:65001 "/out:$helperExe" `
    /reference:System.dll /reference:System.Core.dll /reference:System.Drawing.dll `
    /reference:System.Windows.Forms.dll /reference:System.Web.Extensions.dll `
    "/reference:$coreDll" "/reference:$formsDll" $sourceFile
if ($LASTEXITCODE -ne 0) { throw '[buildVkWebView2] Не удалось собрать окно входа WebView2' }
Copy-Item -LiteralPath $coreDll, $formsDll, $loaderDll -Destination $OutputDirectory -Force
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'Dwij.VkWebView2.exe.config') -Destination $OutputDirectory -Force
Write-Host '[buildVkWebView2] Окно входа собрано; браузерный Runtime в дистрибутив не включается'
