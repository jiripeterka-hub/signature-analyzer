<#
.SYNOPSIS
  Sestavi obrazky pro dialogy instalatoru z ikony aplikace.

.DESCRIPTION
  WiX bere do dialogu dva obrazky a jinak nez souborem se vymenit nedaji:

    WixUIBannerBmp   493 x 58   pruh nahore na vetsine dialogu; ikona vpravo
    WixUIDialogBmp   493 x 312  pozadi uvitaciho a zaverecneho dialogu

  Vychozi podoba je logo WiX Toolsetu. Predava se pres overrides.wxi
  v packaging\windows\jpackage-resources, ktere jpackage vlozi do main.wxs.

  Rozmery jsou dane WiXem a nesmi se menit - jiny rozmer se roztahne a rozmaze.
  Ulozeno jako 24bitovy BMP, protoze RichEdit dialogu prusvitnost neumi
  a 32bitovy obrazek by mel cerne pozadi.

.NOTES
  KODOVANI: UTF-8 s BOM, viz dist.ps1.
#>
param(
    [string]$Source = "app\src\main\resources\app-icon.png",
    [string]$OutDir = "packaging\windows\jpackage-resources"
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing

if (-not (Test-Path $Source)) { Write-Error "Nenalezen zdroj $Source"; exit 1 }
$icon = [System.Drawing.Image]::FromFile((Resolve-Path $Source).Path)

function New-Canvas([int]$w, [int]$h) {
    $bmp = New-Object System.Drawing.Bitmap($w, $h, [System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.Clear([System.Drawing.Color]::White)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    return @($bmp, $g)
}

# --- Banner 493 x 58: ikona vpravo nahore.
#
# Ikona je zamerne mala a posazena vys, nez by odpovidalo stredu pruhu. Pri prvnim
# pokusu byla 44 px a svisle na stredu a v dialogu se orizla: Windows Installer
# vykresli pruh podle vysky ovladaciho prvku (44 dialogovych jednotek), a kdyz je
# obrazek vyssi, cast se nevejde. 32 px na radcich 6 az 38 je videt tak jako tak.
$b, $bg = New-Canvas 493 58
$size = 32
$bg.DrawImage($icon, (493 - $size - 14), 6, $size, $size)
$bg.Dispose()
$bannerPath = Join-Path (Resolve-Path $OutDir) "installer-banner.bmp"
$b.Save($bannerPath, [System.Drawing.Imaging.ImageFormat]::Bmp)
$b.Dispose()
Write-Host "Banner : $bannerPath (493x58)" -ForegroundColor Green

# --- Dialog 493 x 312: ikona v levem pruhu, kde ji vychozi navrh WiX ma.
$d, $dg = New-Canvas 493 312
$size = 128
$dg.DrawImage($icon, [int]((164 - $size) / 2), [int]((312 - $size) / 2), $size, $size)
$dg.Dispose()
$dialogPath = Join-Path (Resolve-Path $OutDir) "installer-dialog.bmp"
$d.Save($dialogPath, [System.Drawing.Imaging.ImageFormat]::Bmp)
$d.Dispose()
Write-Host "Dialog : $dialogPath (493x312)" -ForegroundColor Green

$icon.Dispose()
