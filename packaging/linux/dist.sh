#!/usr/bin/env bash
# Sestaví přenosnou (portable) verzi AnEP pro Linux: app-image z jpackage zabalený do .tar.gz.
#
# Linuxová obdoba packaging/windows/dist.ps1 (19. 10. 2026), bez instalátoru — stačí archiv,
# který se rozbalí a spustí. Kroky drží stejné jako na Windows, aby se obě verze nerozešly:
# jeden shaded JAR, JavaFX mimo classpath v lib/app/fx, licence, sada pro výměnu DSS.
#
# jpackage neumí sestavovat napříč systémy ani procesory: linuxový balík vzniká na Linuxu,
# a to na tom procesoru, pro který je. Proto běží v GitHub Actions dvakrát (x64 a aarch64),
# viz .github/workflows/linux-portable.yml. Spouští se z kořene repozitáře:
#
#   bash packaging/linux/dist.sh [verze] [jméno-vydání]
#
# Vyžaduje JDK 21+ s jpackage v JAVA_HOME a Maven.
set -euo pipefail

APP_VERSION="${1:-1.0.0}"
RELEASE="${2:-1.0}"
APP_NAME="AnEP"
MAIN_CLASS="cz.digitalnikontinuita.anep.Main"
APP_JAR="signature-analyzer.jar"

# JavaFX 21 (verze celé aplikace, pom.xml) nemá na Maven Central sestavení pro linux-aarch64 —
# chybí u všech 21.0.x, objevuje se až od JavaFX 22. Pro ARM64 se proto bere JavaFX 25, a jen pro
# něj (rozhodnutí uživatele 19. 10. 2026); Windows i Linux x64 zůstávají na 21. Aplikace se proti
# JavaFX 25 přeloží beze změny i s --release 21 (ověřeno) a běhové prostředí je JDK 25, které
# JavaFX 25 vyžaduje (od 23).
FX_VERSION_ARGS=()
case "$(uname -m)" in
    x86_64|amd64)  ARCH="x64";     FX_PLATFORM="linux" ;;
    aarch64|arm64) ARCH="aarch64"; FX_PLATFORM="linux-aarch64"; FX_VERSION_ARGS=("-Djavafx.version=25") ;;
    *) echo "Nepodporovaný procesor: $(uname -m)" >&2; exit 1 ;;
esac

ROOT="$(pwd)"
BUILD_DIR="$ROOT/app/target"
INPUT_DIR="$BUILD_DIR/dist-input"
FX_STAGE="$BUILD_DIR/dist-fx"
IMAGE_DIR="$BUILD_DIR/dist-image"
LABEL="${RELEASE// /-}"
ARCHIVE="$BUILD_DIR/$APP_NAME-$LABEL-linux-$ARCH-portable.tar.gz"
JPACKAGE="${JAVA_HOME:+$JAVA_HOME/bin/}jpackage"

echo "== AnEP $RELEASE ($APP_VERSION), Linux $ARCH, JavaFX $FX_PLATFORM ${FX_VERSION_ARGS[*]:-(verze z pom.xml)}"

# 1. Shaded JAR aplikace (JavaFX v něm není — viz app/pom.xml). Bez jakékoli vlastnosti
#    JavaFX: pro překlad si knihovnu podle systému a procesoru vybere POM JavaFX sám, a
#    -Djavafx.platform by ho v Mavenu 3.10 rozbilo (viz pom.xml, anep.fx.classifier).
mvn -B -q -pl app -am package -DskipTests "${FX_VERSION_ARGS[@]}"

# 2. Závislosti pro běh; z nich se ponechá jen JavaFX, zbytek je v shaded JARu.
rm -rf "$INPUT_DIR" "$FX_STAGE"
mkdir -p "$INPUT_DIR" "$FX_STAGE"
mvn -B -q -pl app dependency:copy-dependencies -P dist \
    "-DoutputDirectory=$INPUT_DIR" -DincludeScope=runtime "-Danep.fx.classifier=$FX_PLATFORM" \
    "${FX_VERSION_ARGS[@]}"
mv "$INPUT_DIR"/javafx-*.jar "$FX_STAGE"/
rm -f "$INPUT_DIR"/*.jar
cp "$BUILD_DIR/$APP_JAR" "$INPUT_DIR/"
echo "dist-input: $(du -h "$INPUT_DIR/$APP_JAR" | cut -f1) ($APP_JAR)"

# 3. jpackage — moduly jako na Windows, bez jdk.crypto.mscapi (úložiště Windows-ROOT je jen
#    na Windows; kořeny tuzemských autorit aplikace nese sama, viz TrustConfig).
MODULES="java.se,java.smartcardio,jdk.accessibility,jdk.attach,jdk.compiler,jdk.dynalink,\
jdk.httpserver,jdk.incubator.vector,jdk.internal.ed,jdk.internal.jvmstat,jdk.internal.le,\
jdk.internal.md,jdk.internal.opt,jdk.jartool,jdk.javadoc,jdk.jconsole,jdk.jdi,jdk.jdwp.agent,\
jdk.jfr,jdk.jshell,jdk.jsobject,jdk.management,jdk.management.agent,jdk.management.jfr,jdk.net,\
jdk.nio.mapmode,jdk.sctp,jdk.security.auth,jdk.security.jgss,jdk.unsupported,\
jdk.unsupported.desktop,jdk.xml.dom,jdk.zipfs"

rm -rf "$IMAGE_DIR/$APP_NAME"
mkdir -p "$IMAGE_DIR"
"$JPACKAGE" \
    --type app-image \
    --add-modules "$MODULES" \
    --name "$APP_NAME" \
    --app-version "$APP_VERSION" \
    --vendor "Jiří Peterka" \
    --copyright "Copyright (c) 2024-2026 Jiří Peterka, licence EUPL-1.2" \
    --description "Analyzátor elektronických podpisů" \
    --input "$INPUT_DIR" \
    --main-jar "$APP_JAR" \
    --main-class "$MAIN_CLASS" \
    --dest "$IMAGE_DIR" \
    --icon "$ROOT/app/src/main/resources/app-icon.png" \
    --java-options "--enable-native-access=ALL-UNNAMED" \
    --java-options "-Dfile.encoding=UTF-8" \
    --java-options "-Dstdout.encoding=UTF-8" \
    --java-options '--module-path=$APPDIR/fx' \
    --java-options '--add-modules=javafx.base,javafx.graphics,javafx.controls,javafx.fxml,javafx.web,javafx.media,javafx.swing'
APP_HOME="$IMAGE_DIR/$APP_NAME"
echo "App-image: $APP_HOME"

# 4. JavaFX do lib/app/fx — až po jpackage, aby nebyla na classpath ($APPDIR = lib/app).
mkdir -p "$APP_HOME/lib/app/fx"
cp "$FX_STAGE"/*.jar "$APP_HOME/lib/app/fx/"
echo "fx/: $(ls "$APP_HOME/lib/app/fx" | wc -l) JARů"

# 5. Licence — z repozitáře, nestahují se (viz dist.ps1).
mkdir -p "$APP_HOME/licenses"
cp -r "$ROOT/packaging/windows/licenses/." "$APP_HOME/licenses/"
cp "$ROOT/packaging/windows/NOTICE.txt" "$APP_HOME/licenses/"
cp "$ROOT/LICENSE" "$APP_HOME/LICENSE.txt"

# 5b. Sada pro výměnu DSS (LGPL 2.1, čl. 6) — jen verze, se kterou je aplikace sestavena.
RELINK="$APP_HOME/relink"
mkdir -p "$RELINK/dss"
cp "$BUILD_DIR/original-signature-analyzer.jar" "$RELINK/anep-classes.jar"
DSS_VERSION="$(sed -n 's:.*<dss.version>\(.*\)</dss.version>.*:\1:p' "$ROOT/pom.xml" | head -n1)"
[ -n "$DSS_VERSION" ] || { echo "Nelze přečíst dss.version z pom.xml" >&2; exit 1; }
find "$HOME/.m2/repository/eu/europa/ec/joinup/sd-dss" -name "*-$DSS_VERSION.jar" \
    ! -name "*sources*" ! -name "*javadoc*" -exec cp {} "$RELINK/dss/" \;
cat > "$RELINK/README.txt" <<'EOF'
Sada pro výměnu knihovny DSS
============================

Aplikace AnEP používá knihovnu DSS (Digital Signature Services) Evropské komise,
šířenou pod licencí GNU LGPL verze 2.1 nebo pozdější. V této distribuci je DSS
zabalena spolu s aplikací v jediném souboru lib/app/signature-analyzer.jar.

Licence LGPL žádá, aby bylo možné DSS vyměnit za jinou verzi a aplikaci znovu
sestavit. Tento adresář k tomu obsahuje vše potřebné:

  anep-classes.jar   třídy samotné aplikace, bez knihoven
  dss/               nezměněné archivy DSS ve verzi, se kterou byla aplikace
                     sestavena (viz jména souborů)

Postup (z adresáře AnEP):

  1. Nahraďte archivy v dss/ jinou verzí DSS.
  2. Spusťte aplikaci s vlastní třídou cesty místo přiloženého fat JARu:

     lib/runtime/bin/java \
        --module-path lib/app/fx \
        --add-modules javafx.base,javafx.graphics,javafx.controls,javafx.fxml,javafx.web,javafx.media,javafx.swing \
        -cp "relink/anep-classes.jar:relink/dss/*:lib/app/signature-analyzer.jar" \
        cz.digitalnikontinuita.anep.Main

     Pořadí je podstatné: co je na cestě dřív, to vyhrává, takže vyměněné DSS
     zastíní kopii uvnitř fat JARu.

Zdrojový kód DSS: https://github.com/esig/dss
Zdrojový kód AnEP: https://github.com/jiripeterka-hub/signature-analyzer
EOF

# 6. Návod ke spuštění.
cat > "$APP_HOME/README-linux.txt" <<EOF
AnEP $RELEASE — přenosná verze pro Linux ($ARCH)
=================================================

Spuštění: rozbalte archiv a spusťte

  ./AnEP/bin/AnEP

Položku v nabídce aplikací přidá

  ./AnEP/pridat-do-nabidky.sh

(jen pro přihlášeného uživatele, bez sudo; po přesunu adresáře AnEP skript spusťte znovu).

Instalace není potřeba; prostředí Java i JavaFX jsou součástí archivu. Aplikace potřebuje
grafické prostředí s knihovnou GTK 3 a glibc, což mají běžné desktopové distribuce
(Ubuntu, Debian, Mint, Fedora, openSUSE).

Písmo pro protokoly PDF: doporučeno Liberation Sans (Ubuntu/Debian: fonts-liberation,
Fedora: liberation-sans-fonts). Bez něj se použije DejaVu Sans, je-li v systému.

Grafická knihovna: JavaFX $(ls "$APP_HOME/lib/app/fx" | sed -n 's/^javafx-base-\([0-9.]*\)-.*\.jar$/\1/p' | head -n1).

Nastavení, vlastní úložiště certifikátů a CRL a mezipaměť důvěryhodných seznamů jsou
v ~/.config/SignatureAnalyzer (nebo v \$XDG_CONFIG_HOME/SignatureAnalyzer).
EOF

# 6b. Položka v nabídce aplikací (23. 10. 2026). Skript pro živé USB (rozbalení do ~/AnEP,
#     písmo přes sudo apt-get) se pro vydání nehodil: měl napevno jméno archivu i cíl, cíl
#     předem mazal a sudo funguje jen na Debianu/Ubuntu. Tento jen zapíše spouštěč .desktop
#     pro místo, kde AnEP právě leží — nic nemaže, nic neinstaluje, běží na každé distribuci.
cat > "$APP_HOME/pridat-do-nabidky.sh" <<'SCRIPT'
#!/usr/bin/env bash
# Přidá AnEP do nabídky aplikací přihlášeného uživatele (bez sudo).
# Spouštěč ukazuje na místo, kde AnEP právě leží; po přesunu adresáře skript spusťte znovu.
set -e
APP="$(cd "$(dirname "$0")" && pwd)"
DIR="${XDG_DATA_HOME:-$HOME/.local/share}/applications"
mkdir -p "$DIR"
cat > "$DIR/anep.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Name=AnEP
Comment=Analyzátor elektronických podpisů
Exec="$APP/bin/AnEP"
Icon=$APP/lib/AnEP.png
Terminal=false
Categories=Office;
DESKTOP
echo "AnEP je v nabídce aplikací ($DIR/anep.desktop)."
echo "Odebrání: rm \"$DIR/anep.desktop\""
SCRIPT
chmod +x "$APP_HOME/pridat-do-nabidky.sh"

# 7. Archiv.
rm -f "$ARCHIVE"
tar -C "$IMAGE_DIR" -czf "$ARCHIVE" "$APP_NAME"
echo "Hotovo: $ARCHIVE ($(du -h "$ARCHIVE" | cut -f1))"
