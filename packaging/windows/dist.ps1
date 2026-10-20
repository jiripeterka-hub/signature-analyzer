<#
.NOTES
  KODOVANI: tento soubor je UTF-8 SE ZNACKOU BOM a musi tak zustat.
  Windows PowerShell 5.1 cte soubor bez BOM jako ANSI, takze by se diakritika
  v hodnotach --vendor a --copyright dostala do balicku prekroucena. Diakritika
  je tu prave a jen v techto retezcich; hlaseni do konzole zustavaji bez ni,
  aby nezalezela na kodove strance terminalu.

.SYNOPSIS
  Builds a portable Windows app-image and zips it.
  Called by `mvn -pl app -am package -P dist` or run manually from repo root.

.PARAMETER AppVersion  Ciselna verze do app-image a instalatoru. Windows Installer
                       prijima jen major.minor.build, takze tudy nesmi projit slovo.
.PARAMETER Release     Jmeno vydani do NAZVU balicku ("1.0"). Nevyplnene znamena
                       totez co AppVersion, tedy chovani pred 22. 9. 2026. Co vidi
                       uzivatel v okne "O aplikaci", rika <anep.release> v POMu.
.PARAMETER Console     If set, adds --win-console so a terminal window shows
                       on launch - useful for debugging startup errors.
.PARAMETER Installer   If set, builds an .msi installer as well. Vyzaduje WiX
                       Toolset v3 na PATH (jpackage jej pro --type msi potrebuje).

  INSTALATOR SE STAVI Z HOTOVEHO APP-IMAGE, ne zvlast.

  Drive na to byl samostatny skript jpackage.ps1 a rozesel se: nemel module-path
  pro JavaFX (JVM by odmitl start hlaskou "JavaFX runtime components are missing"),
  nemel jdk.crypto.mscapi (bez nej neni uloziste Windows-ROOT a nelze navazat TLS
  s tuzemskymi razitkovacimi autoritami), nekopiroval licenses ani relink
  a jako vydavatele uvadel "YourOrg". Dva skripty se stejnym ukolem se rozejdou
  vzdycky; tenhle proto stavi oboje z tehoz app-image a instalator jen zabali,
  co uz je hotove a overene.
#>
param(
    [string]$AppVersion = "1.0.0",
    [string]$Release = "",
    [switch]$Console,
    [switch]$Installer
)

$ErrorActionPreference = "Stop"

$AppName   = "AnEP"
$MainClass = "cz.digitalnikontinuita.anep.Main"
$AppJar    = "signature-analyzer.jar"   # thin jar: only app classes
# Resolve absolute paths so Maven -DoutputDirectory doesn't resolve relative
# to the module basedir (app/) instead of the repo root.
$Root      = (Get-Location).Path
$BuildDir  = Join-Path $Root "app\target"
$ClassDir  = Join-Path $BuildDir "classes"
$InputDir  = Join-Path $BuildDir "dist-input"
$ImageDir  = Join-Path $BuildDir "dist-image"
# Nazev balicku nese jmeno vydani, ne ciselnou verzi - to je to, cim ho uzivatel
# pozna. Prazdny -Release znamena, ze se pouzije cislo jako dosud.
$Label     = if ([string]::IsNullOrWhiteSpace($Release)) { $AppVersion }
             else { ($Release -replace '\s+', '-') }
$ZipOut    = Join-Path $BuildDir "$AppName-$Label-windows-portable.zip"
$LicDir    = Join-Path $ImageDir "$AppName\licenses"
$Jar       = Join-Path $env:JAVA_HOME "bin\jar.exe"

# 1. Ensure compiled classes exist
if (-not (Test-Path $ClassDir)) {
    Write-Host "Kompiluji..." -ForegroundColor Cyan
    mvn -pl app -am compile -q
    if ($LASTEXITCODE -ne 0) { Write-Error "Kompilace selhala."; exit 1 }
}

# 2. Copy all runtime dependency JARs to dist-input
Write-Host "Kopiruji zavislosti..." -ForegroundColor Cyan
if (Test-Path $InputDir) { Remove-Item -Recurse -Force $InputDir }
New-Item -ItemType Directory -Force $InputDir | Out-Null

mvn -pl app dependency:copy-dependencies `
    "-DoutputDirectory=$InputDir" `
    "-DincludeScope=runtime" `
    -P dist `
    -q
if ($LASTEXITCODE -ne 0) { Write-Error "Kopirovani zavislosti selhalo."; exit 1 }

# 2b. Move JavaFX JARs to a separate staging dir OUTSIDE --input so that
#     jpackage does NOT add them to app.classpath.  They will be copied into
#     app/fx/ after jpackage runs, and referenced only via --module-path.
$FxStageDir = Join-Path $BuildDir "dist-fx"
if (Test-Path $FxStageDir) { Remove-Item -Recurse -Force $FxStageDir }
New-Item -ItemType Directory -Force $FxStageDir | Out-Null
Get-ChildItem $InputDir -Filter "javafx-*.jar" | Move-Item -Destination $FxStageDir

# 3. Ship ONE shaded JAR instead of ~200 separate ones.
#    DSS resolves its format validators, CRL parsers and CMS implementation through
#    ServiceLoader, which opens every JAR on the classpath looking for META-INF/services.
#    With the dependencies exploded that scan alone cost ~0.8 s of every launch; from a
#    single JAR it is one file. maven-shade already merges the service files correctly
#    (ServicesResourceTransformer) and leaves JavaFX out (see app/pom.xml).
#    JAR se prebaluje, kdyz chybi NEBO kdyz je starsi nez nejnovejsi zdroj. Puha existence
#    nestaci a stalo to sest dnu prace: skript ji dosud bral jako "hotovo", takze kazde
#    manualni baleni od 9. 8. zabalilo tentyz sest dnu stary JAR. Aplikace se pak chovala
#    jinak nez tataz aplikace spustena pres run.bat - forenzni balicek treba nedostal do
#    jmena datum - a vypadalo to jako rozdil mezi zabalenou a nezabalenou verzi, ackoli
#    slo o rozdil mezi novym a starym kodem.
#
#    Podminka soucasne brani rekurzi. Kdyz skript bezi z `mvn -pl app -am package -P dist`,
#    shade prave dobehl a JAR je cerstvy, takze se dalsi Maven nespousti.
#
#    Korenovy pom.xml patri mezi zdroje taky: nese <anep.release>, ktere jde do manifestu
#    JARu a ukazuje ho okno "O aplikaci". Bez nej by zmena jen jmena vydani (Beta 01 -> 1.0,
#    15. 10. 2026) JAR neprebalila a aplikace by dal hlasila stare jmeno.
$FatJar = Join-Path $BuildDir $AppJar
$NewestSource = Get-ChildItem (Join-Path $Root "app\src"), (Join-Path $Root "app\pom.xml"), `
        (Join-Path $Root "pom.xml") `
        -Recurse -File | Sort-Object LastWriteTime | Select-Object -Last 1
$StaleJar = (Test-Path $FatJar) -and $NewestSource `
        -and (Get-Item $FatJar).LastWriteTime -lt $NewestSource.LastWriteTime
if ($StaleJar) {
    Write-Host ("JAR je starsi nez zdroje ({0:d.M.yyyy H:mm} < {1:d.M.yyyy H:mm}) - prebaluji." `
            -f (Get-Item $FatJar).LastWriteTime, $NewestSource.LastWriteTime) -ForegroundColor Yellow
}
if (-not (Test-Path $FatJar) -or $StaleJar) {
    Write-Host "Sestavuji shaded JAR aplikace..." -ForegroundColor Cyan
    mvn -pl app -am package -DskipTests -q
    if ($LASTEXITCODE -ne 0) { Write-Error "Sestaveni JARu selhalo."; exit 1 }
}
if (-not (Test-Path $FatJar)) { Write-Error "Chybi $FatJar - spustte 'mvn -pl app -am package'."; exit 1 }

Get-ChildItem $InputDir -Filter "*.jar" | Remove-Item -Force
Copy-Item $FatJar $InputDir
$fatMB = [math]::Round((Get-Item $FatJar).Length / 1MB, 1)
Write-Host "dist-input: 1 JAR ($fatMB MB)" -ForegroundColor Green

# 4. jpackage
if (Test-Path "$ImageDir\$AppName") { Remove-Item -Recurse -Force "$ImageDir\$AppName" }
New-Item -ItemType Directory -Force $ImageDir | Out-Null

# Moduly behoveho prostredi.
#
# Bez --add-modules si jpackage sestavi vychozi sadu, a ta z principu vynechava moduly, ktere
# nic neexportuji a slouzi jen jako poskytovatele sluzeb. Jednim z nich je jdk.crypto.mscapi,
# tedy poskytovatel SunMSCAPI, a s nim mizi uloziste "Windows-ROOT".
#
# To neni drobnost: korenove certifikaty ceskych autorit (I.CA) v cacerts JDK nejsou, jsou jen
# ve Windows. Zabalena aplikace proto neumela navazat TLS s tsabase.ica.cz a razitko skoncilo
# na "PKIX path building failed: unable to find valid certification path". Pod Mavenem se to
# nikdy neprojevilo, protoze tam bezi plne JDK a Windows-ROOT je k dispozici.
#
# Seznam je vychozi sada jpackage (java.se plus java.smartcardio, ktere java.se nezahrnuje,
# plus jdk.* moduly) doplnena o jdk.crypto.mscapi. Overeno tak, ze vysledny image obsahuje
# presne dosavadni moduly a nic navic krome techto dvou. Vypis dosavadni sady:
#   (Select-String app\target\dist-image\AnEP\runtime\release -Pattern '^MODULES=').Line
$Modules = @(
    "java.se", "java.smartcardio",
    "jdk.accessibility", "jdk.attach", "jdk.compiler", "jdk.crypto.mscapi", "jdk.dynalink",
    "jdk.httpserver", "jdk.incubator.vector", "jdk.internal.ed", "jdk.internal.jvmstat",
    "jdk.internal.le", "jdk.internal.md", "jdk.internal.opt", "jdk.jartool", "jdk.javadoc",
    "jdk.jconsole", "jdk.jdi", "jdk.jdwp.agent", "jdk.jfr", "jdk.jshell", "jdk.jsobject",
    "jdk.management", "jdk.management.agent", "jdk.management.jfr", "jdk.net",
    "jdk.nio.mapmode", "jdk.sctp", "jdk.security.auth", "jdk.security.jgss", "jdk.unsupported",
    "jdk.unsupported.desktop", "jdk.xml.dom", "jdk.zipfs"
) -join ","

$jArgs = @(
    "--type",         "app-image",
    "--add-modules",  $Modules,
    "--name",         $AppName,
    "--app-version",  $AppVersion,
    "--vendor",       "Jiří Peterka",
    "--copyright",    "Copyright (c) 2024-2026 Jiří Peterka, licence EUPL-1.2",
    "--description",  "Analyzátor elektronických podpisů",
    "--input",        $InputDir,
    "--main-jar",     $AppJar,
    "--main-class",   $MainClass,
    "--dest",         $ImageDir,
    "--java-options", "--enable-native-access=ALL-UNNAMED",
    "--java-options", "-Dfile.encoding=UTF-8",
    "--java-options", "-Dstdout.encoding=UTF-8",
    # Note on class-data sharing (-XX:+AutoCreateSharedArchive -XX:SharedArchiveFile=...):
    # measured with a plain JDK launch it is worth ~0.4 s to the first window, but inside the
    # packaged image on a machine with on-access virus scanning it never managed to write the
    # ~50 MB archive and startup rose to 17-23 s. Left out until it can be verified on the
    # target machines.
    "--java-options", '--module-path=$APPDIR/fx',
    "--java-options", '--add-modules=javafx.base,javafx.graphics,javafx.controls,javafx.fxml,javafx.web,javafx.media,javafx.swing'
)
if ($Console) { $jArgs += "--win-console" }

# Ikona .exe. Bez ni dava Windows spustitelnemu souboru vychozi javovskou ikonu, a to je
# to, co uvidi uzivatel na plose i v zastupci. Cesta je absolutni: relativni fungovala jen
# pri spusteni z korene repozitare a jinde se podminka tise minula ucinkem.
# Ikonu sestavi packaging\windows\make-icon.ps1 z newicon2.png.
$IconFile = Join-Path $Root "packaging\windows\app-icon.ico"
if (Test-Path $IconFile) {
    $jArgs += @("--icon", $IconFile)
} else {
    Write-Warning "Chybi $IconFile - aplikace dostane vychozi javovskou ikonu. Spustte packaging\windows\make-icon.ps1."
}

Write-Host "Spoustim jpackage..." -ForegroundColor Cyan
jpackage @jArgs
if ($LASTEXITCODE -ne 0) { Write-Error "jpackage selhal."; exit 1 }
Write-Host "App-image: $ImageDir\$AppName" -ForegroundColor Green

# jpackage necha na spustitelnem launcheru atribut ReadOnly. Krok 4 vyse si s tim poradi
# (Remove-Item -Force read-only soubory maze), ale `mvn clean` ne: maven-clean-plugin
# atribut nezhasne a skonci na "Failed to delete ... AnEP.exe". Zustane-li po prerusenem
# baleni v dist-image jen ten launcher, je od te chvile rozbity kazdy clean, dokud si toho
# nekdo nevsimne - u nas to trvalo devet dni. Zhasnuti atributu je jednodussi nez ucit
# Maven mazat read-only soubory.
Get-ChildItem $ImageDir -Recurse -File -Force |
    Set-ItemProperty -Name IsReadOnly -Value $false

# 4b. Copy JavaFX JARs into app/fx/ inside the image.
#     Done AFTER jpackage so they are not on the classpath (jpackage only
#     scans --input, which no longer contains javafx-*.jar).
$FxAppDir = Join-Path $ImageDir "$AppName\app\fx"
New-Item -ItemType Directory -Force $FxAppDir | Out-Null
Get-ChildItem $FxStageDir -Filter "*.jar" | Copy-Item -Destination $FxAppDir
Write-Host "fx/: $((Get-ChildItem $FxAppDir).Count) JARu" -ForegroundColor Green

# 4c. Diagnostic wrapper — captures stdout/stderr + JVM crash log to anep.log
@'
@echo off
cd /d "%~dp0"
echo Spoustim AnEP... > anep.log
AnEP.exe >> anep.log 2>&1
echo. >> anep.log
echo Exit code: %ERRORLEVEL% >> anep.log
for %%f in ("hs_err_pid*.log") do (
    if exist "%%f" (
        echo. >> anep.log
        echo === JVM crash log === >> anep.log
        type "%%f" >> anep.log
    )
)
echo Vystup zapsan do anep.log - poslete tento soubor.
pause
'@ | Out-File (Join-Path "$ImageDir\$AppName" "run-debug.bat") -Encoding ascii

# 5. Licence
#
# Texty se kopiruji z repozitare, nestahuji se. Drive je skript tahal ze site a pri
# neuspechu zapsal misto licence pouhy odkaz - takze build bez internetu vyrobil balicek,
# ktery nesplnoval ani LGPL, ani Apache. Licence je soucast produktu, ne neco, co se
# obstarava az pri baleni.
if (-not (Test-Path "packaging\windows\licenses\LGPL-2.1.txt")) {
    Write-Error "Chybi packaging\windows\licenses - bez licencnich textu se balit nesmi."
    exit 1
}
New-Item -ItemType Directory -Force $LicDir | Out-Null
Copy-Item "packaging\windows\licenses\*" $LicDir -Recurse -Force
Copy-Item "packaging\windows\NOTICE.txt" $LicDir
Copy-Item "LICENSE" (Join-Path "$ImageDir\$AppName" "LICENSE.txt")
Write-Host "licenses\: $((Get-ChildItem $LicDir).Count) souboru" -ForegroundColor Green

# 5b. Sada pro vymenu DSS (LGPL 2.1, clanek 6)
#
# DSS je pod LGPL a v teto distribuci je slity do jednoho JARu spolu s aplikaci, ne
# pripojeny jako samostatny archiv. LGPL v takovem pripade zada, aby prijemce mohl DSS
# vymenit za jinou verzi a aplikaci znovu sestavit. K tomu potrebuje dve veci, ktere
# fat JAR sam o sobe nedava: tridy aplikace zvlast a nezmenene archivy DSS.
#
# Stoji to 6 MB proti 218 MB celku, takze varianta "pisemna nabidka na vyzadani" by
# usetrila malo a prijemci pridelala praci.
$RelinkDir = Join-Path "$ImageDir\$AppName" "relink"
New-Item -ItemType Directory -Force $RelinkDir | Out-Null
$ThinJar = Join-Path $BuildDir "original-signature-analyzer.jar"
if (Test-Path $ThinJar) {
    Copy-Item $ThinJar (Join-Path $RelinkDir "anep-classes.jar")
} else {
    Write-Warning "Chybi $ThinJar - sada pro vymenu DSS bude neuplna."
}
$DssDir = Join-Path $RelinkDir "dss"
New-Item -ItemType Directory -Force $DssDir | Out-Null
# Jen ta verze, se kterou je aplikace sestavena. Bez filtru nabral skript z .m2
# i starsi verze (potkano: 44 archivu 6.4 vedle 45 archivu 6.5) - prijemce by dostal
# smes dvou verzi a navod v README by na ni nesedel.
$DssVersion = ([xml](Get-Content "pom.xml")).project.properties.'dss.version'
if (-not $DssVersion) { Write-Error "Nelze precist dss.version z pom.xml"; exit 1 }
Write-Host "DSS pro vymenu: verze $DssVersion" -ForegroundColor Cyan
$m2 = Join-Path $env:USERPROFILE ".m2\repository\eu\europa\ec\joinup\sd-dss"
if (Test-Path $m2) {
    Get-ChildItem $m2 -Recurse -Filter "*-$DssVersion.jar" |
        Where-Object { $_.Name -notlike "*sources*" -and $_.Name -notlike "*javadoc*" } |
        Copy-Item -Destination $DssDir -Force
}
@'
Sada pro vymenu knihovny DSS
============================

Aplikace AnEP pouziva knihovnu DSS (Digital Signature Services) Evropske komise,
sirenou pod licenci GNU LGPL verze 2.1 nebo pozdejsi. V teto distribuci je DSS
zabalena spolu s aplikaci v jedinem souboru app\signature-analyzer.jar.

Licence LGPL zada, aby bylo mozne DSS vymenit za jinou verzi a aplikaci znovu
sestavit. Tento adresar k tomu obsahuje vse potrebne:

  anep-classes.jar   tridy samotne aplikace, bez knihoven
  dss\               nezmenene archivy DSS ve verzi, se kterou byla aplikace
                     sestavena (viz jmena souboru)

Postup:

  1. Nahradte archivy v dss\ jinou verzi DSS.
  2. Spustte aplikaci s vlastni tridou cesty misto prilozeneho fat JARu:

     runtime\bin\java.exe ^
        --module-path app\fx ^
        --add-modules javafx.base,javafx.graphics,javafx.controls,javafx.fxml,javafx.web,javafx.media,javafx.swing ^
        -cp "relink\anep-classes.jar;relink\dss\*;app\signature-analyzer.jar" ^
        cz.digitalnikontinuita.anep.Main

     Poradi je podstatne: co je na ceste driv, to vyhrava, takze vymenene DSS
     zastini kopii uvnitr fat JARu.

Zdrojovy kod DSS: https://github.com/esig/dss
Zdrojovy kod AnEP: https://github.com/jiripeterka-hub/signature-analyzer
'@ | Out-File (Join-Path $RelinkDir "README.txt") -Encoding utf8
$relinkMB = [math]::Round(((Get-ChildItem $RelinkDir -Recurse -File |
        Measure-Object Length -Sum).Sum) / 1MB, 1)
Write-Host "relink\: $relinkMB MB" -ForegroundColor Green

# 6. ZIP
if (Test-Path $ZipOut) { Remove-Item -Force $ZipOut }
Write-Host "Vytvarem ZIP..." -ForegroundColor Cyan
Compress-Archive -Path "$ImageDir\$AppName" -DestinationPath $ZipOut
$sizeMB = [math]::Round((Get-Item $ZipOut).Length / 1048576, 1)
$sizeStr = "$sizeMB MB"
Write-Host "Hotovo: $ZipOut ($sizeStr)" -ForegroundColor Green
# 7. Instalator (.msi) - jen na vyzadani
#
# Stavi se z hotoveho app-image (--app-image), takze dostane presne to, co je
# v portable verzi: JavaFX v app\fx, licenses\, relink\ i spravne moduly behoveho
# prostredi. Kdyby se stavel od zacatku, byla by to druha cesta, ktera se od te
# prvni casem lisi - a presne to se uz jednou stalo.
if ($Installer) {
    # jpackage umi WiX v3 (candle.exe + light.exe) i v4/v5 (wix.exe). Kontroluje se
    # oboje, a kdyz neni ani jedno, skonci se tady s citelnou vetou - jinak by to
    # spadlo az uvnitr jpackage hlaskou, ktera nerekne, co s tim.
    $wix = (Get-Command "candle.exe" -ErrorAction SilentlyContinue) -or
           (Get-Command "wix.exe"    -ErrorAction SilentlyContinue)
    # Neni-li WiX na PATH, hleda se na obvyklych mistech a nalezeny adresar se na PATH
    # prida jen pro tento beh. Duvod: WiX 3.14 byl na stroji rozbaleny do
    # %LOCALAPPDATA%\wix314, ale na PATH nebyl, a -Installer pak postavil jen zip
    # (25. 9. 2026). Poradi: promenna WIX (nastavuje ji instalator WiX 3, ukazuje na
    # adresar nad bin\), rozbalene kopie v %LOCALAPPDATA%, instalace v Program Files,
    # a dotnet tool (WiX 4/5).
    if (-not $wix) {
        $candidates = @()
        if ($env:WIX) { $candidates += (Join-Path $env:WIX "bin"), $env:WIX }
        foreach ($base in @($env:LOCALAPPDATA, ${env:ProgramFiles(x86)}, $env:ProgramFiles)) {
            if (-not $base -or -not (Test-Path $base)) { continue }
            Get-ChildItem $base -Directory -Filter "wix*" -ErrorAction SilentlyContinue |
                Sort-Object Name -Descending |
                ForEach-Object { $candidates += (Join-Path $_.FullName "bin"), $_.FullName }
        }
        $candidates += (Join-Path $env:USERPROFILE ".dotnet\tools")
        foreach ($dir in $candidates) {
            if ((Test-Path (Join-Path $dir "candle.exe")) -or
                (Test-Path (Join-Path $dir "wix.exe"))) {
                Write-Host "WiX nalezen mimo PATH: $dir" -ForegroundColor Cyan
                $env:Path = "$dir;$env:Path"
                $wix = $true
                break
            }
        }
    }
    if (-not $wix) {
        Write-Host ""
        Write-Error ("Portable verze je hotova, ale .msi sestavit nelze: na PATH neni WiX. " +
                     "Nainstalujte WiX Toolset v3 (https://github.com/wixtoolset/wix3/releases) " +
                     "nebo v4/v5 (dotnet tool install --global wix) a spustte skript znovu.")
        exit 1
    }
    # Zdroje pro WiX se sestavuji do target\, ne se beru primo z repozitare:
    # main.wxs potrebuje absolutni cesty k obrazkum (light.exe bezi v adresari
    # app-image, takze relativni by neplatily) a ty nelze v repozitari zafixovat.
    $ResSrc = Join-Path $Root "packaging\windows\jpackage-resources"
    $ResDir = Join-Path $BuildDir "jpackage-res"
    if (Test-Path $ResDir) { Remove-Item -Recurse -Force $ResDir }
    New-Item -ItemType Directory -Force $ResDir | Out-Null
    Copy-Item "$ResSrc\*" $ResDir -Force
    (Get-Content "$ResSrc\main.wxs" -Raw -Encoding UTF8).Replace('@IMAGEDIR@', $ResSrc) |
        Set-Content "$ResDir\main.wxs" -Encoding UTF8 -NoNewline

    # Hlidani driftu. main.wxs je kopie vychoziho souboru z JDK s dvema pridanymi
    # radky; kdyz jpackage v novejsim JDK svuj main.wxs zmeni, nas zustane stat
    # a nikdo by si toho nevsiml. Otisk toho, ze ktereho jsme vychazeli, je
    # v main.wxs.base-sha256; tady se porovna s tim, co ma prave pouzite JDK.
    $baseFile = Join-Path $ResSrc "main.wxs.base-sha256"
    if (Test-Path $baseFile) {
        $jimage = Join-Path $env:JAVA_HOME "bin\jimage.exe"
        $modules = Join-Path $env:JAVA_HOME "lib\modules"
        if ((Test-Path $jimage) -and (Test-Path $modules)) {
            $tmpx = Join-Path $BuildDir "jdk-wxs"
            if (Test-Path $tmpx) { Remove-Item -Recurse -Force $tmpx }
            & $jimage extract --include "regex:.*main\.wxs" --dir $tmpx $modules 2>&1 | Out-Null
            $jdkWxs = Get-ChildItem $tmpx -Recurse -Filter "main.wxs" -ErrorAction SilentlyContinue |
                      Select-Object -First 1
            if ($jdkWxs) {
                $now = (Get-FileHash $jdkWxs.FullName -Algorithm SHA256).Hash.ToLower()
                $was = (Get-Content $baseFile -Raw).Trim().ToLower()
                if ($now -ne $was) {
                    Write-Warning "main.wxs se v JDK zmenil od doby, kdy vznikla nase kopie."
                    Write-Warning "  JDK ted: $now"
                    Write-Warning "  vychozi:  $was"
                    Write-Warning "  Porovnejte $($jdkWxs.FullName) s $ResSrc\main.wxs a prenesta zmeny."
                }
            }
            Remove-Item -Recurse -Force $tmpx -ErrorAction SilentlyContinue
        }
    }

    $MsiDir = Join-Path $BuildDir "dist-msi"
    New-Item -ItemType Directory -Force $MsiDir | Out-Null
    # Maze se jen predchozi .msi, ne cely adresar. Do vystupu si clovek odklada
    # i vlastni soubory - a smazat nekomu otevreny PDF je horsi nez nechat tam
    # neco navic.
    Get-ChildItem $MsiDir -Filter "*.msi" -ErrorAction SilentlyContinue |
        Remove-Item -Force -ErrorAction SilentlyContinue

    $mArgs = @(
        "--type",           "msi",
        "--app-image",      "$ImageDir\$AppName",
        "--name",           $AppName,
        "--app-version",    $AppVersion,
        "--dest",           $MsiDir,
        "--vendor",         "Jiří Peterka",
        "--copyright",      "Copyright (c) 2024-2026 Jiří Peterka, licence EUPL-1.2",
        "--description",    "Analyzátor elektronických podpisů",
        # Licence, kterou instalator ukaze pred instalaci, je licence samotne
        # aplikace. Licence knihoven jsou v licenses\ uvnitr instalovane aplikace.
        #
        # CESKE zneni, protoze aplikace je cesky a ceske zneni EUPL ma podle
        # provadeciho rozhodnuti 2017/863 rovnocennou pravni silu. Anglicke je
        # v LICENSE v koreni repozitare, kde ho hledaji nastroje.
        #
        # A ve formatu RTF, ne prostem textu: jpackage predava soubor do WiX jako
        # promennou WixUILicenseRtf, takze dialog ho vykresli jako RTF. Prosty text
        # se v nem zobrazi v kodove strance ANSI a vsechno mimo ni se zmeni
        # v otazniky - v anglickem zneni jich bylo 71 (pomlcky a uvozovky),
        # v ceskem by zmizela cela diakritika (1701 znaku).
        # Prevod dela packaging\txt-to-rtf.py; spustit znovu, kdyz se text zmeni.
                # Nad licenci stoji jeste upozorneni z installer-notice.txt: k cemu
        # aplikace je a ze vysledky jsou bez zaruky. Do textu licence to napsat
        # nelze - ten se nemeni - a obrazovka se souhlasem je jedine misto, kde
        # uzivatel neco aktivne odklika. Totez je na zaverecne obrazovce
        # (WIXUI_EXITDIALOGOPTIONALTEXT v main.wxs).
        "--license-file",   (Join-Path $Root "packaging\windows\LICENSE-cs.rtf"),
        "--win-dir-chooser",
        "--win-menu",
        "--win-menu-group", $AppName,
        "--win-shortcut",
        # Pevne UUID je podminka toho, aby dalsi verze tu predchozi nahradila
        # misto toho, aby se nainstalovala vedle ni. Menit se nesmi.
        "--win-upgrade-uuid", "45849180-c894-4257-bfd4-b379f7180d88",
        # Vlastni lokalizace WiX jen kvuli jedinemu atributu: Codepage="65001"
        # misto vychozi 1252.
        #
        # WiX sazi retezce v kodove strance kultury, a en-us znamena 1252, ve ktere
        # ceske "r s hackem" neni. --vendor "Jiri Peterka" a --description
        # "Analyzator elektronickych podpisu" (obojí s diakritikou) proto shodily
        # light.exe s navratovym kodem 311. Zmereno: "i s carkou" projde (v 1252 je),
        # "r s hackem" ne; --copyright projde i tak, protoze nejde pres lokalizacni
        # tabulku. Bez teto slozky by v instalatoru muselo stat jmeno bez diakritiky.
        "--resource-dir", $ResDir
    )
    if (Test-Path $IconFile) { $mArgs += @("--icon", $IconFile) }

    Write-Host "Sestavuji instalator..." -ForegroundColor Cyan
    jpackage @mArgs
    if ($LASTEXITCODE -ne 0) { Write-Error "jpackage --type msi selhal."; exit 1 }
    $msi = Get-ChildItem $MsiDir -Filter "*.msi" | Select-Object -First 1
    if ($msi) {
        # jpackage pojmenuje soubor podle ciselne verze. Nese-li vydani jmeno,
        # prejmenuje se - aby se instalator poznal stejne jako portable ZIP.
        if ($Label -ne $AppVersion) {
            $target = Join-Path $MsiDir "$AppName-$Label.msi"
            if (Test-Path $target) { Remove-Item -Force $target }
            Rename-Item -Path $msi.FullName -NewName (Split-Path $target -Leaf)
            $msi = Get-Item $target
        }
        $msiMB = [math]::Round($msi.Length / 1MB, 1)
        Write-Host "Instalator: $($msi.FullName) ($msiMB MB)" -ForegroundColor Green
    }
}
