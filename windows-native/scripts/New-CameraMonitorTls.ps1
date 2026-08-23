param(
    [string[]]$IpAddress = @('10.95.194.185', '10.95.194.233', '10.0.2.2', '127.0.0.1'),
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '..\dist\tls'),
    [string]$AndroidCertificate = (Join-Path $PSScriptRoot '..\..\android-app\app\src\main\res\raw\camera_monitor_ca.crt'),
    [switch]$Force
)

$ErrorActionPreference = 'Stop'

function Write-Utf8NoBom {
    param([string]$Path, [string]$Value)
    [System.IO.File]::WriteAllText($Path, $Value, [System.Text.UTF8Encoding]::new($false))
}

$output = [System.IO.Path]::GetFullPath($OutputDirectory)
$androidCert = [System.IO.Path]::GetFullPath($AndroidCertificate)
$targets = @(
    (Join-Path $output 'ca.crt'),
    (Join-Path $output 'ca.cer'),
    (Join-Path $output 'ca.key'),
    (Join-Path $output 'server.crt'),
    (Join-Path $output 'server.key')
)
if (-not $Force -and ($targets | Where-Object { Test-Path -LiteralPath $_ })) {
    throw "TLS files already exist in $output. Use -Force to rotate the private CA."
}

[System.IO.Directory]::CreateDirectory($output) | Out-Null
[System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($androidCert)) | Out-Null

$now = [DateTimeOffset]::UtcNow.AddMinutes(-5)
$caKey = [System.Security.Cryptography.RSA]::Create(4096)
$caRequest = [System.Security.Cryptography.X509Certificates.CertificateRequest]::new(
    'CN=Camera Monitor Private CA',
    $caKey,
    [System.Security.Cryptography.HashAlgorithmName]::SHA256,
    [System.Security.Cryptography.RSASignaturePadding]::Pkcs1
)
$caRequest.CertificateExtensions.Add(
    [System.Security.Cryptography.X509Certificates.X509BasicConstraintsExtension]::new($true, $false, 0, $true)
)
$caUsage = [System.Security.Cryptography.X509Certificates.X509KeyUsageFlags]::KeyCertSign -bor
    [System.Security.Cryptography.X509Certificates.X509KeyUsageFlags]::CrlSign
$caRequest.CertificateExtensions.Add(
    [System.Security.Cryptography.X509Certificates.X509KeyUsageExtension]::new($caUsage, $true)
)
$caRequest.CertificateExtensions.Add(
    [System.Security.Cryptography.X509Certificates.X509SubjectKeyIdentifierExtension]::new($caRequest.PublicKey, $false)
)
$caCertificate = $caRequest.CreateSelfSigned($now, $now.AddYears(10))

$serverKey = [System.Security.Cryptography.RSA]::Create(3072)
$serverRequest = [System.Security.Cryptography.X509Certificates.CertificateRequest]::new(
    'CN=Camera Monitor ZeroTier',
    $serverKey,
    [System.Security.Cryptography.HashAlgorithmName]::SHA256,
    [System.Security.Cryptography.RSASignaturePadding]::Pkcs1
)
$serverRequest.CertificateExtensions.Add(
    [System.Security.Cryptography.X509Certificates.X509BasicConstraintsExtension]::new($false, $false, 0, $true)
)
$serverUsage = [System.Security.Cryptography.X509Certificates.X509KeyUsageFlags]::DigitalSignature -bor
    [System.Security.Cryptography.X509Certificates.X509KeyUsageFlags]::KeyEncipherment
$serverRequest.CertificateExtensions.Add(
    [System.Security.Cryptography.X509Certificates.X509KeyUsageExtension]::new($serverUsage, $true)
)
$serverEku = [System.Security.Cryptography.OidCollection]::new()
$serverEku.Add([System.Security.Cryptography.Oid]::new('1.3.6.1.5.5.7.3.1')) | Out-Null
$serverRequest.CertificateExtensions.Add(
    [System.Security.Cryptography.X509Certificates.X509EnhancedKeyUsageExtension]::new($serverEku, $true)
)
$san = [System.Security.Cryptography.X509Certificates.SubjectAlternativeNameBuilder]::new()
$san.AddDnsName('localhost')
foreach ($ip in $IpAddress) {
    $parsed = $null
    if (-not [System.Net.IPAddress]::TryParse($ip, [ref]$parsed)) {
        throw "Invalid IP address: $ip"
    }
    $san.AddIpAddress($parsed)
}
$serverRequest.CertificateExtensions.Add($san.Build($true))
$serial = [byte[]]::new(16)
[System.Security.Cryptography.RandomNumberGenerator]::Fill($serial)
$serverCertificate = $serverRequest.Create($caCertificate, $now, $now.AddYears(3), $serial)

$caPem = $caCertificate.ExportCertificatePem()
Write-Utf8NoBom (Join-Path $output 'ca.crt') $caPem
[System.IO.File]::WriteAllBytes(
    (Join-Path $output 'ca.cer'),
    $caCertificate.Export([System.Security.Cryptography.X509Certificates.X509ContentType]::Cert)
)
Write-Utf8NoBom (Join-Path $output 'ca.key') $caKey.ExportPkcs8PrivateKeyPem()
Write-Utf8NoBom (Join-Path $output 'server.crt') $serverCertificate.ExportCertificatePem()
Write-Utf8NoBom (Join-Path $output 'server.key') $serverKey.ExportPkcs8PrivateKeyPem()
Write-Utf8NoBom $androidCert $caPem

$fingerprint = [Convert]::ToHexString([System.Security.Cryptography.SHA256]::HashData($caCertificate.RawData))
Write-Host "Camera Monitor private CA created."
Write-Host "Server IP SAN: $($IpAddress -join ', ')"
Write-Host "CA SHA-256: $fingerprint"
Write-Host "Private files: $output"
Write-Host "Android trust certificate: $androidCert"
