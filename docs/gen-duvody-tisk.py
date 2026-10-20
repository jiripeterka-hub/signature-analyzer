"""Vygeneruje docs/duvody-tisk.html z výpisu, který ReasonDump vytáhl přímo z kódu.

Nic se tu neopisuje ručně: názvy, texty i vazby mezi důvody pocházejí ze tříd, které o nich
rozhodují, takže list nemůže tvrdit něco jiného než aplikace. Postup:

    mvn -o -q -DskipTests test-compile
    mvn -o -q -pl app dependency:build-classpath -Dmdep.outputFile=../cp.txt -Dmdep.includeScope=test
    java -Dstdout.encoding=UTF-8 \
         -cp "app/target/test-classes;app/target/classes;$(cat cp.txt)" \
         cz.digitalnikontinuita.anep.ui.ReasonDump > duvody.txt
    python3 docs/gen-duvody-tisk.py duvody.txt
    python3 docs/gen-duvody-docx.py duvody.txt     # wordová podoba bez situací (AnEP-duvody.docx)

Bez -Dstdout.encoding=UTF-8 píše JVM na Windows do roury v CP1250 a v listu
skončí místo diakritiky rozsypaný čaj. Týmž výpisem se plní i číselník situací,
takže po jeho pořízení má smysl spustit rovnou oba generátory.

Bez argumentu se čte ze standardního vstupu.
"""
import html
import os
import re
import sys
from datetime import date

DOCS = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(DOCS, "duvody-tisk.html")

sit = {}
reasons = {}
cur = None
src = open(sys.argv[1], encoding="utf-8") if len(sys.argv) > 1 else sys.stdin
for raw in src:
    line = raw.rstrip("\n").rstrip("\r")
    if line.startswith("@SIT\t"):
        _, num, txt = line.split("\t", 2)
        sit[int(num)] = txt
        continue
    m = re.match(r"### DUVOD (\d+)$", line)
    if m:
        cur = int(m.group(1))
        reasons[cur] = {}
        continue
    if cur and "\t" in line:
        k, v = line.split("\t", 1)
        reasons[cur][k] = v

def nums(field):
    return [int(x) for x in re.findall(r"R(\d+)", field or "")]

def trig(field):
    out = []
    for part in (field or "").split(", "):
        part = part.strip()
        if not part:
            continue
        bits = part.split("|")
        cislo = re.sub(r"[^0-9]", "", bits[0])
        # „(nezjištěno)" — ReasonDump nenašel situaci, ze které důvod vzniká. Přeskočit, ne
        # spadnout: důvod bez zjištěného spouštěče na listu být má, jen se u něj nedá vypsat,
        # z čeho vzniká.
        if not cislo:
            continue
        out.append((int(cislo), bits[1] if len(bits) > 1 else None))
    return out

# Situace, které důvod umlčí, aniž by to plynulo z tabulky potlačení — tedy podmínky schované
# přímo v detectReasons(). Ty jsou na papíře nejcennější, protože je z tabulky nikdo nevyčte.
sit_to_reason = {}
for r, d in reasons.items():
    for n, _ in trig(d.get("TRIGGER", "")):
        sit_to_reason.setdefault(n, []).append(r)

for r, d in reasons.items():
    suppressed_by = set(nums(d.get("POTLACEN", "")))
    hidden = []
    for part in (d.get("BLOKUJI") or "").split(", "):
        part = part.strip()
        if not part:
            continue
        cislo = re.sub(r"[^0-9]", "", part)
        if not cislo:
            continue
        s = int(cislo)
        if not any(x in suppressed_by for x in sit_to_reason.get(s, [])):
            hidden.append(s)
    d["_hidden"] = hidden

E = html.escape

def sit_ref(n, note=None):
    txt = sit.get(n, "")
    short = txt if len(txt) <= 155 else txt[:152].rsplit(" ", 1)[0] + "…"
    extra = f' <em>({E(note)})</em>' if note else ""
    return f'<span class="sit"><b>S{n}</b> {E(short)}{extra}</span>'

def rlink(ns):
    return ", ".join(f'<b>R{n}</b>&nbsp;{E(reasons.get(n, {}).get("NAZEV", ""))}' for n in ns)

blocks = []
for r in sorted(reasons):
    d = reasons[r]
    t = trig(d.get("TRIGGER", ""))
    parts = [f'<article>',
             f'<h2><span class="rn">R{r}</span> {E(d.get("NAZEV", ""))}</h2>']
    parts.append('<div class="from">Vzniká ze situace: '
                 + " ".join(sit_ref(n, note) for n, note in t) + "</div>")
    if d.get("PROBLEM"):
        parts.append(f'<p class="prob">{E(d["PROBLEM"])}</p>')
    if d.get("DETAIL"):
        parts.append(f'<p class="det"><span class="lbl">Technicky</span>{E(d["DETAIL"])}</p>')
    if d.get("RESENI"):
        parts.append(f'<p class="sol"><span class="lbl">Co pomůže</span>{E(d["RESENI"])}</p>')
    # Rada pro zkušenější uživatele stojí v aplikaci vlastním řádkem pod spodním z důvodů,
    # kterých se týká; na papíře ji nese každý z nich, ať list nikde nekončí bez doporučení.
    if d.get("RADA"):
        parts.append(f'<p class="sol"><span class="lbl">Pro zkušenější</span>{E(d["RADA"])}</p>')

    links = []
    if nums(d.get("POTLACI", "")):
        links.append('<li><span class="k">Potlačí</span>' + rlink(nums(d["POTLACI"])) + "</li>")
    if nums(d.get("POTLACEN", "")):
        links.append('<li><span class="k">Je potlačen</span>' + rlink(nums(d["POTLACEN"])) + "</li>")
    if d["_hidden"]:
        links.append('<li><span class="k">Nevznikne, je-li</span>'
                     + ", ".join(f'<b>S{n}</b>' for n in d["_hidden"])
                     + ' <em>— podmínka přímo v detectReasons(), není v tabulce potlačení</em></li>')
    if not links:
        links.append('<li><span class="k">Vazby</span>žádné — nepotlačuje nic '
                     'a nic ho nepotlačuje</li>')
    parts.append('<ul class="links">' + "".join(links) + "</ul>")
    parts.append("</article>")
    blocks.append("\n".join(parts))

# Žebříček přednosti: kdo potlačí kolik.
ladder = sorted(reasons, key=lambda r: (-len(nums(reasons[r].get("POTLACI", ""))), r))
ladder_rows = []
for r in ladder:
    n = len(nums(reasons[r].get("POTLACI", "")))
    if n == 0:
        continue
    ladder_rows.append(
        f'<tr><td class="c"><b>R{r}</b></td><td>{E(reasons[r].get("NAZEV",""))}</td>'
        f'<td class="c">{n}</td><td class="sm">'
        + ", ".join(f"R{x}" for x in nums(reasons[r]["POTLACI"])) + "</td></tr>")

nobody = [r for r in sorted(reasons)
          if not nums(reasons[r].get("POTLACI", "")) and not nums(reasons[r].get("POTLACEN", ""))]

today = date.today().strftime("%d. %m. %Y")
doc = f"""<!doctype html>
<html lang="cs">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Číselník důvodů AnEP</title>
<style>
  @page {{ size: A4 portrait; margin: 15mm 16mm 14mm 16mm; }}
  :root {{ --ink:#111; --muted:#666; --rule:#c9d2db; --band:#eef2f7; }}
  body {{ font-family: "Segoe UI", Calibri, system-ui, sans-serif; color: var(--ink);
         font-size: 9.5pt; line-height: 1.36; background:#fff; margin:0; }}
  header {{ border-bottom: 2px solid var(--ink); padding-bottom: 6px; margin-bottom: 12px; }}
  h1 {{ font-size: 15pt; margin: 0 0 3px 0; }}
  .sub {{ font-size: 8.5pt; color: var(--muted); }}
  .intro {{ font-size: 8.8pt; color:#333; border:1px solid var(--rule); border-radius:3px;
           padding: 7px 9px; margin-bottom: 12px; break-inside: avoid; }}
  .intro p {{ margin: 0 0 4px 0; }}
  .intro p:last-child {{ margin-bottom: 0; }}
  h2 {{ font-size: 10.5pt; margin: 0 0 4px 0; }}
  .rn {{ display:inline-block; min-width: 9mm; background: var(--ink); color:#fff;
        border-radius: 2px; padding: 0 4px; margin-right: 5px;
        font-variant-numeric: tabular-nums; text-align:center; }}
  article {{ break-inside: avoid; page-break-inside: avoid;
            border-bottom: 1px solid var(--rule); padding: 7px 0 8px 0; }}
  .from {{ font-size: 8.6pt; color:#333; margin-bottom: 5px; }}
  .sit {{ display:block; padding-left: 10mm; text-indent: -10mm; }}
  p {{ margin: 0 0 4px 0; hyphens:auto; text-align: justify; }}
  .lbl {{ display:inline-block; font-size:7.5pt; text-transform:uppercase; letter-spacing:.04em;
         color: var(--muted); border:1px solid var(--rule); border-radius:3px;
         padding: 0 4px; margin-right: 5px; white-space:nowrap; }}
  .det, .sol {{ font-size: 8.8pt; color:#333; }}
  ul.links {{ margin: 5px 0 0 0; padding: 0; list-style:none; font-size:8.6pt; }}
  ul.links li {{ background: var(--band); border-radius:3px; padding: 2.5px 6px;
                margin-bottom: 2.5px; }}
  .k {{ display:inline-block; min-width: 30mm; color: var(--muted);
       text-transform: uppercase; font-size: 7.5pt; letter-spacing:.04em; }}
  h3 {{ font-size: 11pt; margin: 14px 0 6px 0; padding-top: 8px;
       border-top: 2px solid var(--ink); break-after: avoid; }}
  table {{ border-collapse: collapse; width:100%; font-size:8.6pt; }}
  th, td {{ border-bottom:1px solid var(--rule); padding: 3px 5px; text-align:left;
           vertical-align: top; }}
  th {{ font-size:7.5pt; text-transform:uppercase; letter-spacing:.04em; color:var(--muted); }}
  td.c {{ text-align:center; font-variant-numeric: tabular-nums; white-space:nowrap; }}
  .sm {{ font-size:8pt; color:#444; }}
  footer {{ margin-top: 12px; font-size: 8pt; color: var(--muted);
           border-top: 1px solid var(--rule); padding-top: 6px; break-inside: avoid; }}
  @media screen {{ body {{ max-width: 190mm; margin: 12mm auto; padding: 0 6mm; }} }}
</style>
</head>
<body>
<header>
  <h1>Číselník důvodů &mdash; AnEP</h1>
  <div class="sub">Analyzátor elektronických podpisů &middot; {len(reasons)} důvodů &middot;
     stav ke dni {today} &middot; vygenerováno ze zdrojového kódu</div>
</header>

<div class="intro">
  <p><b>Co je důvod.</b> Situace je pozorování o dokumentu; důvod je to, co se z něj řekne
     uživateli — proč ověření nedopadlo, a co s tím. Jeden důvod shrnuje jednu či více situací
     a číslování obou řad spolu nesouvisí.</p>
  <p><b>Proč na sobě důvody závisí.</b> Vypsat všechna pozorování naráz by čtenáře poslalo hledat
     příčinu tam, kde žádná není. Silnější nález proto ten slabší umlčí: u podpisu s porušenou
     integritou nemá smysl radit, ať se zaškrtne tolerance k revokačním datům. Vazby níž jsou
     přesně tahle pravidla.</p>
  <p><b>Jak číst vazby.</b> <i>Potlačí</i> — vyskytnou-li se oba, vypíše se jen tenhle.
     <i>Je potlačen</i> — opačný pohled na totéž. <i>Nevznikne, je-li</i> — podmínka, která
     nestojí v tabulce potlačení, ale přímo v kódu, jenž důvody odvozuje; z tabulky se nevyčte.</p>
</div>

{"".join(blocks)}

<h3>Žebříček přednosti</h3>
<p class="det">Kolik dalších důvodů který umlčí. Čím výš, tím spíš je právě on tím, co čtenář
   uvidí jako jediný.</p>
<table>
  <tr><th>Důvod</th><th>Název</th><th>Potlačí</th><th>Které</th></tr>
  {"".join(ladder_rows)}
</table>
<p class="det" style="margin-top:6px">Bez jakékoli vazby (nepotlačují nic a nic je nepotlačuje):
   {", ".join("R" + str(r) for r in nobody) if nobody else "žádné"}.</p>

<footer>
  Vygenerováno z <code>ResultView.REASONS</code>, <code>ResultView.REASON_WORKING_LABELS</code>,
  <code>ResultView.SITUATION_LABELS</code> a <code>ReportMapper.REASON_SUPPRESSIONS</code>;
  vazba situace&nbsp;&rarr;&nbsp;důvod je změřena voláním <code>ReportMapper.detectReasons()</code>,
  ne opsána z komentáře. Číselník situací je v samostatném listu
  <code>docs/situace-tisk.html</code>.
</footer>
</body>
</html>
"""
open(OUT, "w", encoding="utf-8").write(doc)
print(f"zapsáno: {OUT}")
print(f"důvodů: {len(reasons)}, situací v odkazech: {len(sit)}")
missing = [r for r in sorted(reasons) if not reasons[r].get("NAZEV")]
if missing:
    print("POZOR — důvody bez názvu:", missing)
