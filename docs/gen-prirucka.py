"""Vygeneruje docs/prirucka.html — uživatelskou příručku (rychlý start) — ze zdroje a snímků.

Sesterský skript ke gen-duvody-tisk.py a gen-situace-tisk.py, ale řeší jiný problém. Tam jde
o to, aby se ručně psaný list nerozešel s kódem; tady o to, aby se hotová stránka dala vůbec
upravovat. Výsledek má přes 400 kB, protože snímky jsou v něm zapečené jako data: URI — jeden
soubor, který se otevře poklepáním a nemá co ztratit po cestě e-mailem. Editovat se ale takový
soubor nedá, a proto se text drží zvlášť v prirucka-src.html a obrázky v prirucka-img/.

Zdroj je obyčejné HTML se zástupkami {{IMG:jméno.png}} v atributu src. Skript je nahradí
obsahem odpovídajícího souboru z prirucka-img/. Nic víc nedělá.

Postup při změně textu:

    (upravit docs/prirucka-src.html)
    python3 docs/gen-prirucka.py
    python3 docs/gen-prirucka-docx.py     # wordová podoba z téhož zdroje (AnEP-prirucka.docx)

Postup při obnově snímků — sonda staví celé okno aplikace, ne jen obsah záložky:

    mvn -o -q -pl app test-compile
    mvn -o -q -pl app exec:java -Dexec.classpathScope=test \\
        -Dexec.mainClass=cz.digitalnikontinuita.anep.tools.WindowSnapshot \\
        -Dexec.args="-out docs/prirucka-img/02-vysledek.png -trim cesta/k/dokumentu.pdf"

Snímky vznikly nad dokumenty ze sbírky autora; jména souborů v nich jsou vidět, takže se při
obnově vyplatí použít stejné vstupy, jinak se popisky pod obrázky rozejdou s tím, co je na nich.
"""
import base64
import os
import re
import sys

DOCS = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(DOCS, "prirucka-src.html")
IMG = os.path.join(DOCS, "prirucka-img")
OUT = os.path.join(DOCS, "prirucka.html")


def embed(match):
    name = match.group(1)
    path = os.path.join(IMG, name)
    if not os.path.exists(path):
        sys.exit(f"chybí obrázek: {path}")
    with open(path, "rb") as f:
        return "data:image/png;base64," + base64.b64encode(f.read()).decode("ascii")


def main():
    with open(SRC, encoding="utf-8") as f:
        source = f.read()
    out, count = re.subn(r"\{\{IMG:([^}]+)\}\}", embed, source)
    if count == 0:
        sys.exit("ve zdroji nejsou žádné zástupky {{IMG:...}} — je to opravdu prirucka-src.html?")
    with open(OUT, "w", encoding="utf-8") as f:
        f.write(out)
    print(f"{OUT}: {count} obrázků, {len(out.encode('utf-8')):,} B")


if __name__ == "__main__":
    main()
