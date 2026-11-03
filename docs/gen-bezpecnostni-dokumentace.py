# -*- coding: utf-8 -*-
"""Vygeneruje `docs/AnEP-bezpecnostni-dokumentace.docx` a `docs/AnEP-online-komunikace.docx`.

Zdrojem jsou `docs/bezpecnostni-dokumentace.md` a `docs/online-komunikace.md`. Oba dokumenty
vznikly 3. 11. 2026 jako podklad pro provoz v síti justice (zákon č. 264/2025 Sb., akt
o kybernetické odolnosti) a patří k sobě, proto jeden skript. Sazba je táž jako u politik
ověřování a uchovávání: volá se `gen-politika-overovani.py` s jiným zdrojem, cílem a štítkem
obálky, takže markdown smí používat jen podmnožinu, kterou ten generátor zná (viz jeho hlavička).

    python docs/gen-bezpecnostni-dokumentace.py

⚠ Přepíše oba soubory .docx.

Potřebuje `python-docx`.
"""

import importlib.util
import os

ZDE = os.path.dirname(os.path.abspath(__file__))

DOKUMENTY = [
    ('bezpecnostni-dokumentace.md', 'AnEP-bezpecnostni-dokumentace.docx',
     'AnEP — technická a bezpečnostní dokumentace', 'BEZPEČNOSTNÍ DOKUMENTACE'),
    ('online-komunikace.md', 'AnEP-online-komunikace.docx',
     'AnEP — přehled online komunikace', 'ONLINE KOMUNIKACE'),
]


def main():
    spec = importlib.util.spec_from_file_location(
        'gen_politika_overovani', os.path.join(ZDE, 'gen-politika-overovani.py'))
    gen = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(gen)
    for zdroj, cil, titulek, stitek in DOKUMENTY:
        gen.main(zdroj=os.path.join(ZDE, zdroj), cil=os.path.join(ZDE, cil),
                 titulek=titulek, stitek=stitek)


if __name__ == '__main__':
    main()
