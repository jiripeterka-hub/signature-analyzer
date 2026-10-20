"""Vygeneruje docs/situace-tisk.html z výpisu, který ReasonDump vytáhl přímo z kódu.

Sesterský skript ke gen-duvody-tisk.py a ze stejného důvodu: dokud se list psal ručně,
tiše se rozešel s aplikací. Když vznikl, chyběly v něm situace 46 a 47 od jejich zavedení
a texty situací 2, 20 a 21 v něm zůstaly v podobě, kterou kód už neříkal — tvrdily, že podpis
na revokovaném certifikátu je neplatný, ačkoli aplikace mezitím přešla na neznámou platnost.

Popisy pocházejí z `ResultView.SITUATION_LABELS`, tedy z téhož místa, ze kterého je bere blok
„Zjištěné příčiny“. Ručně se udržuje jen EXTRA níže: situace, které v číselníku popis nemají —
buď byly zrušeny, nebo je aplikace hlásí bez textu a vysvětlení se dá dohledat jen v komentáři
u jejich detekce v ReportMapper.

Postup:

    mvn -o -q -DskipTests test-compile
    mvn -o -q -pl app dependency:build-classpath -Dmdep.outputFile=../cp.txt -Dmdep.includeScope=test
    java -cp "app/target/test-classes;app/target/classes;$(cat cp.txt)" \
         -Dstdout.encoding=UTF-8 cz.digitalnikontinuita.anep.ui.ReasonDump > situace.txt
    python3 docs/gen-situace-tisk.py situace.txt

Bez argumentu se čte ze standardního vstupu.
"""
import html
import os
import sys
from datetime import date

DOCS = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(DOCS, "situace-tisk.html")

# Situace bez popisu v ResultView.SITUATION_LABELS. „gap“ = zrušená, do počtu položek se nepočítá;
# „derived“ = aplikace ji hlásí, ale jen číslem, takže text je opsán z komentáře u její detekce.
EXTRA = {
    28: ("gap", "nepoužívá se",
         "Zrušena 28. 7. 2026. Snižovala verdikt vždy, když vložená revokační informace pocházela"
         " od vydavatele, který mezitím expiroval. Sken celého vzorku (1740 souborů) ji zachytil"
         " osmkrát a pokaždé šlo o planý poplach."),
    29: ("derived", "bez popisu v číselníku",
         "Ověření skončilo jako platné v režimu dle standardů, ale jediná vložená revokační"
         " informace k podpisovému certifikátu byla vydána vydavatelem, jehož certifikát byl v"
         " okamžiku jejího vydání již po expiraci (konec platnosti řetězce je dříve než"
         " thisUpdate). Liší se od situace 5: tam respondér expiroval až po vydání odpovědi, a"
         " takový historický doklad obstojí."),
    34: ("derived", "bez popisu v číselníku",
         "Ověření proběhlo bez ústupků: nebyla potřeba žádná z tolerancí, nebyl zadán vlastní čas"
         " ověření a v žádném z posuzovaných řetězců nefiguruje certifikát z vlastního úložiště"
         " důvěryhodných certifikátů."),
    35: ("derived", "bez popisu v číselníku",
         "Ověření dle standardů nenašlo použitelnou revokační informaci, ale potřebná data už v"
         " dokumentu jsou — chybí jen důkaz, že existovala v době, kdy byla ještě ověřitelná."
         " Zachování ověřitelnosti (archivní časové razítko) ten důkaz doplní a ověřovatel dle"
         " standardů pak dojde k platnému výsledku."),
    42: ("gap", "nepoužívá se",
         "Zrušena. Co měla hlídat, pokrývá situace 6."),
}

sit = {}
src = open(sys.argv[1], encoding="utf-8") if len(sys.argv) > 1 else sys.stdin
for raw in src:
    line = raw.rstrip("\n").rstrip("\r")
    if line.startswith("@SIT\t"):
        _, num, txt = line.split("\t", 2)
        sit[int(num)] = txt.strip()

numbers = sorted(set(sit) | set(EXTRA))
items = sum(1 for n in numbers if EXTRA.get(n, ("", ))[0] != "gap")

rows = []
for n in numbers:
    if n in EXTRA:
        cls, tag, txt = EXTRA[n]
        body = f'<span class="tag">{html.escape(tag)}</span> {html.escape(txt)}'
        rows.append(f'<div class="row {cls}"><div class="num">{n}</div>'
                    f'<div class="txt">{body}</div></div>')
    else:
        rows.append(f'<div class="row"><div class="num">{n}</div>'
                    f'<div class="txt">{html.escape(sit[n])}</div></div>')

doc = f"""<!doctype html>
<html lang="cs">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Číselník situací AnEP</title>
<style>
  @page {{ size: A4 portrait; margin: 15mm 16mm 14mm 16mm; }}
  :root {{ --ink:#111; --muted:#666; --rule:#c9d2db; }}
  body {{ font-family: "Segoe UI", Calibri, system-ui, sans-serif; color: var(--ink);
         font-size: 10pt; line-height: 1.38; background: #fff; margin: 0; }}
  header {{ border-bottom: 2px solid var(--ink); padding-bottom: 6px; margin-bottom: 14px; }}
  h1 {{ font-size: 15pt; margin: 0 0 3px 0; letter-spacing: .01em; }}
  .sub {{ font-size: 8.5pt; color: var(--muted); }}
  .row {{ display: grid; grid-template-columns: 11mm 1fr; gap: 0 4mm;
         padding: 4.5px 0; border-bottom: 1px solid var(--rule);
         break-inside: avoid; page-break-inside: avoid; }}
  .num {{ font-weight: 700; text-align: right; font-variant-numeric: tabular-nums; }}
  .txt {{ text-align: left; hyphens: auto; }}
  .derived .txt, .gap .txt {{ color: #444; }}
  .tag {{ display: inline-block; font-size: 7.5pt; letter-spacing: .02em; text-transform: uppercase;
         color: #555; border: 1px solid var(--rule); border-radius: 3px; padding: 0 3px;
         margin-right: 4px; vertical-align: 1px; }}
  footer {{ margin-top: 12px; padding-top: 6px; border-top: 1px solid var(--rule);
           font-size: 8pt; color: var(--muted); break-inside: avoid; }}
  @media screen {{ body {{ max-width: 190mm; margin: 12mm auto; padding: 0 6mm; }} }}
</style>
</head>
<body>
<header>
  <h1>Číselník situací &mdash; AnEP</h1>
  <div class="sub">Analyzátor elektronických podpisů &middot; {items} položek &middot;
     stav ke dni {date.today().strftime('%d. %m. %Y')} &middot; vygenerováno ze zdrojového kódu</div>
</header>
{chr(10).join(rows)}
<footer>
  Vygenerováno z <code>ResultView.SITUATION_LABELS</code>; položky označené „bez popisu
  v číselníku“ a „nepoužívá se“ jsou ručně vedeny v <code>docs/gen-situace-tisk.py</code>,
  protože popis v kódu nemají. Situace, která popis v číselníku nemá, se v bloku
  „Zjištěné příčiny“ vypíše jen jako číslo. Číselník důvodů je v samostatném listu
  <code>docs/duvody-tisk.html</code>.
</footer>
</body>
</html>
"""
open(OUT, "w", encoding="utf-8").write(doc)
print(f"zapsáno: {OUT}")
print(f"situací: {len(numbers)}, z toho položek: {items}, ručně vedených: {len(EXTRA)}")
missing = [n for n in numbers if n not in sit and n not in EXTRA]
if missing:
    print("POZOR — situace bez textu:", missing)
