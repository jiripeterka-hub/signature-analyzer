# -*- coding: utf-8 -*-
"""Vygeneruje `docs/schopnosti-detailne.html` ze `docs/schopnosti-detailne.md`.

Do 3. 11. 2026 se HTML podoba převáděla ručně a rozešla se s markdownem: chyběly v ní čtyři
odstavce o záznamu o úkonu a starší znění u přerazítkování. Zdrojem je proto jen markdown
a tenhle skript z něj HTML vysází. Nad týmž markdownem dává totéž co dosavadní ruční převod
(porovnáno 3. 11. 2026: lišila se jen ta místa, kde HTML zaostávalo).

Rozumí jen té podmnožině markdownu, kterou dokument používá:

    # Titul / ## Oddíl            <h1> / <h2>
    * odrážka                      <ul><li>; vnoření odsazením mezerami
    **tučně**, `kód`               <strong>, <code>; <mark> projde beze změny
    jiný řádek                     <p>

    python docs/gen-schopnosti-detailne.py

⚠ Přepíše `docs/schopnosti-detailne.html`.
"""

import os
import re

ZDE = os.path.dirname(os.path.abspath(__file__))
ZDROJ = os.path.join(ZDE, 'schopnosti-detailne.md')
CIL = os.path.join(ZDE, 'schopnosti-detailne.html')


def inline(t):
    t = re.sub(r'`([^`]+)`', lambda m: '<code>' + m.group(1) + '</code>', t)
    return re.sub(r'\*\*(.+?)\*\*', r'<strong>\1</strong>', t)


def seznam(polozky, hloubka):
    """polozky: [(text, podpolozky)]; odsazení dvojí mezerou na úroveň, jako dosud."""
    odsaz = '  ' * (2 * hloubka)
    out = [odsaz + '<ul>']
    for text, deti in polozky:
        if deti:
            out.append(odsaz + '  <li>' + inline(text))
            out.extend(seznam(deti, hloubka + 1))
            out.append(odsaz + '  </li>')
        else:
            out.append(odsaz + '  <li>' + inline(text) + '</li>')
    out.append(odsaz + '</ul>')
    return out


def preved(md):
    out = []
    koren = None
    zasobnik = []  # (odsazení, seznam)

    def uzavri():
        nonlocal koren, zasobnik
        if koren is not None:
            out.extend(seznam(koren, 0))
        koren, zasobnik = None, []

    for radek in md.split('\n'):
        m = re.match(r'^( *)\* (.*)$', radek)
        if m:
            odsazeni = len(m.group(1))
            if koren is None:
                koren = []
                zasobnik = [(odsazeni, koren)]
            while zasobnik and odsazeni < zasobnik[-1][0]:
                zasobnik.pop()
            if odsazeni > zasobnik[-1][0]:
                zasobnik.append((odsazeni, zasobnik[-1][1][-1][1]))
            zasobnik[-1][1].append((m.group(2), []))
            continue
        if not radek.strip():
            continue
        uzavri()
        if radek.startswith('# '):
            out.append('<h1>' + inline(radek[2:]) + '</h1>')
        elif radek.startswith('## '):
            out.append('<h2>' + inline(radek[3:]) + '</h2>')
        else:
            out.append('<p>' + inline(radek) + '</p>')
    uzavri()
    return '\n'.join(out) + '\n'


def main():
    with open(ZDROJ, encoding='utf-8') as f:
        md = f.read().replace('\r\n', '\n')
    with open(CIL, 'w', encoding='utf-8', newline='\n') as f:
        f.write(preved(md))
    print('zapsáno:', CIL)


if __name__ == '__main__':
    main()
