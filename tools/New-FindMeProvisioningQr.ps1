[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateScript({ Test-Path -LiteralPath $_ -PathType Leaf })]
    [string]$ApkPath,

    [Parameter(Mandatory = $true)]
    [ValidatePattern("^https://")]
    [string]$ApkUrl,

    [Parameter(Mandatory = $true)]
    [ValidatePattern("^[A-Za-z0-9]{10}$")]
    [string]$ReceiverCode,

    [string]$WifiSsid,

    [ValidateSet("WPA", "WEP", "NONE")]
    [string]$WifiSecurity = "WPA",

    [string]$WifiPassword,

    [string]$OutputDirectory = "build/provisioning",

    [switch]$SkipQr
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if (-not $WifiSsid -and $WifiPassword) {
    throw "WifiPassword requires WifiSsid."
}
if ($WifiSsid -and $WifiSecurity -ne "NONE" -and -not $WifiPassword) {
    throw "WifiPassword is required for $WifiSecurity."
}

$resolvedApk = (Resolve-Path -LiteralPath $ApkPath).Path
$hashBytes = [System.Security.Cryptography.SHA256]::Create().ComputeHash(
    [System.IO.File]::ReadAllBytes($resolvedApk)
)
$checksum = [Convert]::ToBase64String($hashBytes).TrimEnd("=").Replace("+", "-").Replace("/", "_")

# Ordered keys keep generated payloads stable and easy to compare during setup.
$payload = [ordered]@{
    "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME" = "it.xcc.findme.transmitter/.FindMeDeviceAdminReceiver"
    "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION" = $ApkUrl
    "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM" = $checksum
    "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED" = $true
    "android.app.extra.PROVISIONING_SKIP_EDUCATION_SCREENS" = $true
    "android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE" = [ordered]@{
        "findme_receiver_code" = $ReceiverCode.Trim().ToUpperInvariant()
    }
}

if ($WifiSsid) {
    $payload["android.app.extra.PROVISIONING_WIFI_SSID"] = $WifiSsid
    $payload["android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE"] = $WifiSecurity
    if ($WifiSecurity -ne "NONE") {
        $payload["android.app.extra.PROVISIONING_WIFI_PASSWORD"] = $WifiPassword
    }
}

$json = $payload | ConvertTo-Json -Depth 4 -Compress
$outputPath = [System.IO.Path]::GetFullPath(
    (Join-Path (Get-Location) $OutputDirectory)
)
[System.IO.Directory]::CreateDirectory($outputPath) | Out-Null

$jsonPath = Join-Path $outputPath "findme-provisioning.json"
$qrPath = Join-Path $outputPath "findme-provisioning.png"
$utf8WithoutBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($jsonPath, $json, $utf8WithoutBom)

Write-Host "Provisioning payload: $jsonPath"
Write-Host "APK SHA-256 (URL-safe Base64): $checksum"

if (-not $SkipQr) {
    $npx = Get-Command "npx.cmd" -ErrorAction SilentlyContinue
    if (-not $npx) {
        $npx = Get-Command "npx" -ErrorAction SilentlyContinue
    }
    if (-not $npx) {
        throw "npx is required to render the QR image. Install Node.js or rerun with -SkipQr."
    }

    # The qrcode package runs locally; the provisioning payload is not sent to a QR web service.
    & $npx.Source --yes qrcode -o $qrPath -w 1000 -e M $json
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $qrPath)) {
        throw "QR generation failed with exit code $LASTEXITCODE."
    }
    Write-Host "Provisioning QR: $qrPath"
}
