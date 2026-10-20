#!/usr/bin/env python3
"""Převede licenci z prostého textu na RTF pro dialog instalátoru.

Proč to vůbec je: jpackage předává soubor z --license-file do WiX jako proměnnou
``WixUILicenseRtf``, tedy jako **RTF**. Prostý text se v tom dialogu vykreslí
v kódové stránce ANSI a všechno mimo ni se změní v otazník — v anglickém znění
EUPL to bylo 71 znaků: typografické uvozovky, pomlčky a odrážky. V českém znění
by zmizela celá diakritika.

RTF to řeší tím, že se každý znak mimo ASCII zapíše jako ``\\uNNNN?`` (číslo je
kódový bod jako 16bitové číslo se znaménkem, otazník je náhrada pro čtečky, které
Unicode neumějí). Text se tím nemění, jen zapisuje.

Volitelný třetí argument je soubor s upozorněním, které se vysází **tučně**
před licenci a oddělí čarou. Slouží k tomu, aby na obrazovce, kde uživatel
aktivně zaškrtává souhlas, stálo i to, k čemu aplikace je a že se výsledky
poskytují bez záruky — v samotném textu licence to říct nelze, ten se nemění.

Použití::

    python packaging/txt-to-rtf.py LICENSE out.rtf [upozorneni.txt]
"""

import io
import sys

# Segoe UI je písmo dialogů Windows; fcharset0 = ANSI, na kódování nezáleží,
# protože všechno mimo ASCII se stejně zapisuje přes \uNNNN.
HEADER = (r'{\rtf1\ansi\ansicpg1252\deff0\nouicompat'
          r'{\fonttbl{\f0\fnil\fcharset0 Segoe UI;}}'
          '\n' r'\viewkind4\uc1\pard\f0\fs18 ')


def escape(text: str) -> str:
    out = []
    for ch in text:
        o = ord(ch)
        if ch in '\\{}':
            out.append('\\' + ch)
        elif ch == '\n':
            out.append('\\par\n')
        elif ch == '\r':
            continue
        elif ch == '\t':
            out.append('\\tab ')
        elif o < 128:
            out.append(ch)
        else:
            # RTF chce 16bitové číslo se znaménkem; nad 32767 se odečte 65536.
            n = o if o < 32768 else o - 65536
            out.append(f'\\u{n}?')
    return ''.join(out)


def main() -> int:
    if len(sys.argv) not in (3, 4):
        print(__doc__)
        return 2
    src, dst = sys.argv[1], sys.argv[2]
    text = io.open(src, encoding='utf-8').read()

    notice = ''
    if len(sys.argv) > 3:
        n = io.open(sys.argv[3], encoding='utf-8').read().strip()
        # Tucne, a pod tim vodorovna cara (odstavec s dolnim rameckem). Cara je
        # tam proto, aby bylo videt, kde konci nase upozorneni a kde zacina
        # licence - text licence se nemeni a nesmi s nim splynout.
        notice = (r'\b ' + escape(n) + r'\b0\par' + '\n'
                  r'\pard\brdrb\brdrs\brdrw10\brsp20\par\pard' + '\n'
                  r'\par' + '\n')

    rtf = HEADER + notice + escape(text) + '\n}\n'
    io.open(dst, 'w', encoding='ascii', newline='\r\n').write(rtf)

    non_ascii = sum(1 for c in text if ord(c) > 127)
    print(f'{dst}: {len(text)} znaku, z toho {non_ascii} mimo ASCII '
          f'(ty by v prostem textu vysly jako otazniky)'
          + (', s upozornenim' if len(sys.argv) > 3 else ''))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
