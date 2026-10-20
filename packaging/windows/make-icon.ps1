<#
.SYNOPSIS
  Sestavi app-icon.ico z ikony okna aplikace (glóbus s podpisem).

.DESCRIPTION
  Windows si pro plochu, zastupce, hlavni panel a Prukumnika vybira z ikony ruzne
  velikosti podle kontextu a zvetseni displeje. Jedna velikost proto nestaci: kdyby
  .ico neslo o 256 px, Prukumnik ji ve velkych ikonach roztahne a kresba se rozmaze.
  Odtud cely zebricek 16-256.

  Zdrojem je app-icon.png, tedy tataz kresba, jakou nosi okno aplikace. Nikoli
  newicon2.png v koreni: ta ma pozadi vyplnene bilou, a ikona z ni pak sedi na plose
  v bilem ctverci misto na podklade, ktery ma uzivatel za oknem. Rozdil je pouze
  v pruhlednosti, kresba je totozna - porovnani obou pres bily podklad je nerozezna,
  odchylka 0,5 z 255. Prave proto stoji za to mit ho napsany: hledat se to bude spatne.

  Ikona se do .exe zapece pri baleni (jpackage --icon v dist.ps1), takze po zmene
  kresby je potreba spustit tento skript a pak prebalit.

  Vysledek je verzovany, aby balit slo bez tohoto kroku.
#>
param(
    [string]$Source = "app\src\main\resources\app-icon.png",
    [string]$Target = "packaging\windows\app-icon.ico"
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing

if (-not (Test-Path $Source)) { Write-Error "Nenalezen zdroj $Source"; exit 1 }

# Pruhledne pozadi je pozadavek, ne detail, a nepruhledny zdroj se pozna az na plose.
$probe = New-Object System.Drawing.Bitmap -ArgumentList ((Resolve-Path $Source).Path)
$cornerAlpha = $probe.GetPixel(0, 0).A
$probe.Dispose()
if ($cornerAlpha -gt 8) {
    Write-Warning ("Zdroj {0} ma neprusvitny roh (alfa {1}) - ikona bude na plose v barevnem ctverci." -f $Source, $cornerAlpha)
}

$sizes = 16, 24, 32, 48, 64, 128, 256
$src   = [System.Drawing.Image]::FromFile((Resolve-Path $Source))

# Kazda velikost jako samostatny PNG. Windows umi PNG uvnitr .ico od Visty a u 256 px
# je to jedina rozumna moznost - nekomprimovana bitmapa te velikosti ma 256 kB.
$payloads = @()
foreach ($size in $sizes) {
    $bmp = New-Object System.Drawing.Bitmap $size, $size
    $g   = [System.Drawing.Graphics]::FromImage($bmp)
    $g.InterpolationMode  = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.SmoothingMode      = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    $g.PixelOffsetMode    = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
    $g.Clear([System.Drawing.Color]::Transparent)
    $g.DrawImage($src, 0, 0, $size, $size)
    $g.Dispose()
    $ms = New-Object System.IO.MemoryStream
    $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    $payloads += ,@($size, $ms.ToArray())
    $ms.Dispose()
}
$src.Dispose()

$out    = New-Object System.IO.MemoryStream
$writer = New-Object System.IO.BinaryWriter $out

# ICONDIR
$writer.Write([UInt16]0)                 # reserved
$writer.Write([UInt16]1)                 # typ 1 = ikona
$writer.Write([UInt16]$payloads.Count)

# ICONDIRENTRY, 16 bajtu na polozku; data zacinaji za celym adresarem
$offset = 6 + 16 * $payloads.Count
foreach ($p in $payloads) {
    $size = $p[0]; $bytes = $p[1]
    $dim = 0                             # 0 v adresari znamena 256 px
    if ($size -lt 256) { $dim = $size }
    $writer.Write([Byte]$dim)
    $writer.Write([Byte]$dim)
    $writer.Write([Byte]0)               # pocet barev palety (0 = plnobarevna)
    $writer.Write([Byte]0)               # reserved
    $writer.Write([UInt16]1)             # roviny
    $writer.Write([UInt16]32)            # bitu na pixel
    $writer.Write([UInt32]$bytes.Length)
    $writer.Write([UInt32]$offset)
    $offset += $bytes.Length
}
foreach ($p in $payloads) { $writer.Write($p[1]) }

$writer.Flush()
$dir = Split-Path $Target -Parent
if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Force $dir | Out-Null }
[System.IO.File]::WriteAllBytes((Join-Path (Get-Location) $Target), $out.ToArray())
$writer.Dispose(); $out.Dispose()

Write-Host ("Hotovo: {0} ({1} velikosti, {2:N0} B)" -f $Target, $payloads.Count,
        (Get-Item $Target).Length) -ForegroundColor Green
