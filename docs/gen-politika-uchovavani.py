# -*- coding: utf-8 -*-
"""Vygeneruje `docs/AnEP-politika-uchovavani.docx` z `docs/politika-uchovavani.md`.

Sazba je táž jako u politiky ověřování — obě politiky vycházejí se shodnou verzí a mají
vypadat jako dvojice. Proto se nekopíruje, ale volá `gen-politika-overovani.py` s jiným
zdrojem, cílem a štítkem obálky. Markdown politiky smí používat jen podmnožinu, kterou
ten generátor zná (viz jeho hlavička).

    python docs/gen-politika-uchovavani.py

⚠ Přepíše `docs/AnEP-politika-uchovavani.docx`.

Potřebuje `python-docx`.
"""

import importlib.util
import os

ZDE = os.path.dirname(os.path.abspath(__file__))


def main():
    spec = importlib.util.spec_from_file_location(
        'gen_politika_overovani', os.path.join(ZDE, 'gen-politika-overovani.py'))
    gen = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(gen)
    gen.main(zdroj=os.path.join(ZDE, 'politika-uchovavani.md'),
             cil=os.path.join(ZDE, 'AnEP-politika-uchovavani.docx'),
             titulek='AnEP — politika uchovávání elektronických podpisů',
             stitek='POLITIKA UCHOVÁVÁNÍ PODPISU')


if __name__ == '__main__':
    main()
