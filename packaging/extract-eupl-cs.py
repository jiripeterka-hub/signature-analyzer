#!/usr/bin/env python3
"""Vytáhne z rozhodnutí Komise 2017/863 samotný text licence EUPL 1.2 v češtině.

Proč skript a ne ruční kopie: to, co se v Úředním věstníku zveřejnilo, je
**prováděcí rozhodnutí**, jehož přílohou licence teprve je. Uživateli instalátoru
se má dát ke schválení licence, ne rozhodnutí o ní — preambule, články
a podpis člena Komise tam nemají co dělat.

Druhý důvod: text z PDF má zalomení podle sazby, tedy tvrdé konce uprostřed vět,
rozdělovníky (měkké spojovníky) a průběžná záhlaví stránek. V dialogu instalátoru
by to vypadalo rozsypaně. HTML z EUR-Lexu má odstavce celé, takže se text
nemusí slepovat zpátky heuristikou.

Zdroj: https://eur-lex.europa.eu/legal-content/CS/TXT/HTML/?uri=CELEX:32017D0863

Použití::

    python packaging/extract-eupl-cs.py eupl.html packaging/windows/licenses/EUPL-1.2-cs.txt
"""

import html
import io
import re
import sys

# Nadpis přílohy; od něj dál je licence. Verze v kotvě je nutná: touž frází
# („veřejná licence Evropské unie EUPL") začíná i nadpis samotného rozhodnutí
# a bez ní by se opsalo i ono.
START = re.compile(r'VEŘEJNÁ\s+LICENCE\s+EVROPSKÉ\s+UNIE\s+v\.\s*1\.2', re.IGNORECASE)


def blocks(source: str):
    """Odstavce jako (text, je_nadpis). Značkování se zahazuje, text zůstává."""
    for m in re.finditer(r'<(p|h[1-6]|td)\b[^>]*>(.*?)</\1>', source,
                         re.DOTALL | re.IGNORECASE):
        raw = m.group(2)
        raw = re.sub(r'<br\s*/?>', '\n', raw, flags=re.IGNORECASE)
        text = re.sub(r'<[^>]+>', '', raw)
        text = html.unescape(text)
        text = text.replace('­', '')          # měkký spojovník ze sazby
        text = text.replace(' ', ' ')         # nezlomitelná mezera
        # Uvnitř odstavce sjednotit bílé znaky; odstavec je jedna řádka a zalomí
        # si ho až dialog podle své šířky.
        text = re.sub(r'\s+', ' ', text).strip()
        if not text:
            continue
        heading = bool(re.search(r'class="[^"]*oj-(doc-ti|ti-art|ti-grseq)', m.group(0)))
        yield text, heading


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    source = io.open(sys.argv[1], encoding='utf-8', errors='replace').read()

    # EUR-Lex sazi odrazku a jeji text do dvou bunek tabulky, takze z nich vyjdou
    # dva odstavce a v dialogu by osamela pomlcka stala na vlastni radce. Znacka
    # se proto pripoji k tomu, co po ni nasleduje.
    MARKER = re.compile(r'^(?:[—–•*-]|\(?\d+[.)]|[a-z]\)|\d+\.)$')

    out, started, skipped, pending = [], False, 0, None
    for text, heading in blocks(source):
        if not started:
            if START.search(text):
                started = True
            else:
                skipped += 1
                continue
        if MARKER.match(text):
            pending = text
            continue
        if pending:
            text = f'{pending} {text}'
            pending = None
        out.append(('\n' + text + '\n') if heading else text)

    if not started:
        print('Nenalezen začátek licence — zkontrolujte zdroj.', file=sys.stderr)
        return 1

    # Prázdná řádka mezi odstavci; víc než jedna za sebou nemá smysl.
    body = '\n\n'.join(out)
    body = re.sub(r'\n{3,}', '\n\n', body).strip() + '\n'

    io.open(sys.argv[2], 'w', encoding='utf-8', newline='\n').write(body)
    print(f'{sys.argv[2]}: {len(out)} odstavcu, '
          f'{skipped} odstavcu rozhodnuti pred licenci vynechano, '
          f'{len(body)} znaku')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
