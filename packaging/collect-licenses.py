#!/usr/bin/env python3
"""Sesbírá licenční texty všech běhových závislostí do jednoho souboru.

Proč generátor a ne ručně udržovaný seznam: závislostí je přes sto, mění se
s každým povýšením DSS a ručně vedený výčet by po prvním povýšení lhal. Tenhle
skript čte skutečný obsah jarů, které se opravdu balí — tedy totéž, co dostane
uživatel.

Vytváří ``packaging/windows/licenses/THIRD-PARTY-LICENSES.txt``: pro každý
artefakt jeho vlastní ``META-INF/LICENSE*`` a ``META-INF/NOTICE*``. Právě ty se
při slévání do jednoho JARu ztrácejí (soubory téhož jména se přepisují), a přitom
je Apache License 2.0 čl. 4(d) žádá předat dál.

Použití::

    mvn -q -pl app dependency:list -DincludeScope=runtime -DoutputFile=deps.txt
    python packaging/collect-licenses.py deps.txt

Bez argumentu si seznam obstará sám voláním Mavenu.
"""

from __future__ import annotations

import io
import os
import re
import subprocess
import sys
import zipfile

M2 = os.path.join(os.path.expanduser('~'), '.m2', 'repository')
OUT = os.path.join('packaging', 'windows', 'licenses', 'THIRD-PARTY-LICENSES.txt')
RULE = '=' * 78

# Soubory uvnitř JARu, které nesou licenci nebo výslovné uvedení autorství.
WANTED = re.compile(
    r'^META-INF/(LICENSE|LICENCE|NOTICE|DEPENDENCIES|COPYRIGHT)'
    r'(\.txt|\.md)?$', re.IGNORECASE)


def maven_dependency_list(target: str) -> None:
    subprocess.run(
        ['mvn', '-q', '-pl', 'app', 'dependency:list',
         '-DincludeScope=runtime', f'-DoutputFile={os.path.abspath(target)}'],
        check=True)


def parse(path: str):
    """Řádky ``g:a:jar[:classifier]:v:scope`` na (groupId, artifactId, verze, cesta)."""
    seen, out = set(), []
    for raw in io.open(path, encoding='utf-8', errors='replace'):
        ln = re.sub(r'\x1b\[[0-9;]*m', '', raw).strip()
        ln = re.sub(r'\s+--.*$', '', ln)
        if ':jar:' not in ln:
            continue
        p = ln.split(':')
        if len(p) == 5:
            g, a, _, v, _ = p
            cls = None
        elif len(p) == 6:
            g, a, _, cls, v, _ = p
        else:
            continue
        if (g, a, v, cls) in seen:
            continue
        seen.add((g, a, v, cls))
        name = f'{a}-{v}' + (f'-{cls}' if cls else '') + '.jar'
        out.append((g, a, v, os.path.join(M2, *g.split('.'), a, v, name)))
    return sorted(out)


def texts(jar: str):
    """Licenční soubory uvnitř JARu jako [(jméno, obsah)]."""
    found = []
    try:
        with zipfile.ZipFile(jar) as z:
            for n in sorted(z.namelist()):
                if WANTED.match(n):
                    try:
                        found.append((n, z.read(n).decode('utf-8', 'replace')))
                    except Exception:
                        pass
    except Exception:
        pass
    return found


def main() -> int:
    listing = sys.argv[1] if len(sys.argv) > 1 else 'target-deps.txt'
    if len(sys.argv) <= 1:
        maven_dependency_list(listing)

    deps = parse(listing)
    if not deps:
        print('Seznam závislostí je prázdný — nic k sesbírání.', file=sys.stderr)
        return 1

    body, with_text, without_text, missing = [], 0, [], []
    for g, a, v, jar in deps:
        if not os.path.exists(jar):
            missing.append(f'{g}:{a}:{v}')
            continue
        found = texts(jar)
        if not found:
            without_text.append(f'{g}:{a}:{v}')
            continue
        with_text += 1
        body.append(RULE)
        body.append(f'{g}:{a}:{v}')
        body.append(RULE)
        for name, content in found:
            body.append('')
            body.append(f'--- {name} ---')
            body.append('')
            body.append(content.rstrip())
        body.append('')

    head = [
        'LICENČNÍ TEXTY POUŽITÝCH KNIHOVEN',
        '',
        'Vygenerováno skriptem packaging/collect-licenses.py z JARů, které se',
        'skutečně balí do distribuce. Obsahuje soubory META-INF/LICENSE',
        'a META-INF/NOTICE tak, jak je dodávají jejich autoři.',
        '',
        f'Artefaktů celkem: {len(deps)}',
        f'Z toho s vlastním licenčním textem: {with_text}',
        '',
        'Artefakty, které vlastní licenční text v JARu nenesou, jsou vypsané na',
        'konci. Jejich licence uvádí NOTICE.txt a plná znění jsou v tomto adresáři',
        'jako samostatné soubory.',
        '',
    ]
    tail = ['', RULE, 'BEZ VLASTNÍHO LICENČNÍHO TEXTU V JARU', RULE, '']
    tail += [f'  {x}' for x in without_text]
    if missing:
        tail += ['', 'Nenalezeno v místním repozitáři Mavenu:']
        tail += [f'  {x}' for x in missing]

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    io.open(OUT, 'w', encoding='utf-8', newline='\n').write(
        '\n'.join(head + body + tail) + '\n')

    print(f'{OUT}: {len(deps)} artefaktů, {with_text} s vlastním textem, '
          f'{len(without_text)} bez, {len(missing)} nenalezeno')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
