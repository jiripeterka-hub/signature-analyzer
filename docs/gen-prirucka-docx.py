# -*- coding: utf-8 -*-
"""Vygeneruje `docs/AnEP-prirucka.docx` — uživatelskou příručku (rychlý start) ve Wordu.

Zdrojem je `prirucka-src.html`, tentýž, ze kterého `gen-prirucka.py` dělá `prirucka.html`,
a snímky z `prirucka-img/`. Proto generátor, a ne jednorázový převod: příručka se mění
v HTML a wordová podoba se z něj má dát kdykoli obnovit, aniž by se obě rozešly.

Sazbu drží `docx_kit.py` — táž úprava jako manuál pro pokročilé a ilustrovaný přehled
schopností, se kterými příručka tvoří řadu (rychlý start → schopnosti → manuál). Na rozdíl od
nich nemá rámečky pro ruční vložení obrázků: snímky příručky existují, takže se vloží rovnou.
Převádí se struktura zdroje — nadpisy, odstavce, výčty, kroky postupu, vysvětlivky, tři
podoby výsledku, obrázky s popiskami a slovníček —, ne jeho obrazovkový vzhled.

    python docs/gen-prirucka-docx.py

Vzniklo 14. 10. 2026 na pokyn uživatele („převeď příručku do formátu DOCX“).

⚠ Přepíše `docs/AnEP-prirucka.docx`. Kdo do wordové podoby psal vlastní úpravy, přijde o ně —
zdrojem zůstává `prirucka-src.html`.

Potřebuje `python-docx` a `lxml`.
"""

import os
import re
import sys

import lxml.html
from docx import Document
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.image.image import Image
from docx.shared import Cm, Pt, RGBColor

from docx_kit import (AKCENT, AKCENT_TMAVY, INKOUST, INKOUST_SVET, PISMO_LEHKE, SAZBA_CM,
                      cislovane, nadpis1, nadpis2, odrazky, odstavec, prostrkani, psat,
                      spodni_linka, tabulka, vypln_bunky, vysvetlivka, zaklad, zalom)

ZDE = os.path.dirname(os.path.abspath(__file__))
ZDROJ = os.path.join(ZDE, 'prirucka-src.html')
OBRAZKY = os.path.join(ZDE, 'prirucka-img')
CIL = os.path.join(ZDE, 'AnEP-prirucka.docx')
TITULEK = 'AnEP — uživatelská příručka'

# Nejvyšší obrázek (Rozšířený přehled) by na šířku sazby zabral skoro celou stránku.
MAX_VYSKA_CM = 17.0

# Podklad buňky u tří podob výsledku — tlumené barvy pruhů z aplikace.
BARVA_VYSLEDKU = {'ok': 'E3F0D6', 'bad': 'F5DCDA', 'unk': 'F0E5D4'}

_cislo_obrazku = [0]


# ── text uvnitř odstavce ─────────────────────────────────────────────────────────────────

def mezery(text):
    return re.sub(r'\s+', ' ', text or '')


def trida(el):
    return el.get('class') or ''


def znacky(el, tucne=False, mono=False):
    """Vnitřek prvku jako text se značkováním docx_kit: **tučně** a `neproporcionálně`.

    Názvy prvků rozhraní (`span.ui`) jsou v HTML odlišené vzhledem; v tisku se z nich stává
    tučné písmo, jinak by „Nastavení“ v textu splynulo s obyčejným slovem.
    """
    kusy = []

    def obal(text, b, m):
        if not text:
            return
        if m:
            kusy.append('`' + text + '`')
        elif b and text.strip():
            okraj_l = text[:len(text) - len(text.lstrip())]
            okraj_p = text[len(text.rstrip()):]
            kusy.append(okraj_l + '**' + text.strip() + '**' + okraj_p)
        else:
            kusy.append(text)

    obal(mezery(el.text), tucne, mono)
    for dite in el:
        if isinstance(dite.tag, str):
            tag = dite.tag.lower()
            b = tucne or tag in ('strong', 'b') or 'ui' in trida(dite).split()
            m = mono or tag in ('code', 'kbd', 'samp')
            if tag == 'br':
                kusy.append(' ')
            elif b != tucne or m != mono:
                # Vnořené značky se neslévají: vnitřek se zploští a obalí jednou.
                obal(mezery(''.join(dite.itertext())), b, m)
            else:
                kusy.append(znacky(dite, b, m))
        obal(mezery(dite.tail), tucne, mono)
    return re.sub(r' {2,}', ' ', ''.join(kusy)).strip()


def text_z(el):
    return mezery(''.join(el.itertext())).strip()


# ── bloky ────────────────────────────────────────────────────────────────────────────────

def obalka(doc, mast):
    mezera = doc.add_paragraph()
    mezera.paragraph_format.space_after = Pt(30)

    eyebrow = mast.find('.//p[@class="eyebrow"]')
    if eyebrow is not None:
        par = doc.add_paragraph()
        par.paragraph_format.space_after = Pt(4)
        run = par.add_run(text_z(eyebrow).upper())
        run.bold = True
        run.font.size = Pt(9)
        run.font.color.rgb = RGBColor.from_string(AKCENT)
        prostrkani(run, 60)

    h1 = mast.find('.//h1')
    nazev, _, podnazev = text_z(h1).partition(' — ') if h1 is not None else ('AnEP', '', '')
    par = doc.add_paragraph()
    par.paragraph_format.space_after = Pt(0)
    run = par.add_run(nazev)
    run.font.name = PISMO_LEHKE
    run.font.size = Pt(48)
    run.font.color.rgb = RGBColor.from_string(INKOUST)
    if podnazev:
        par = doc.add_paragraph()
        par.paragraph_format.space_after = Pt(10)
        run = par.add_run(podnazev)
        run.font.size = Pt(15)
        run.font.color.rgb = RGBColor.from_string(INKOUST_SVET)
        spodni_linka(par, AKCENT, sz=12, space=8)

    standfirst = mast.find('.//p[@class="standfirst"]')
    if standfirst is not None:
        odstavec(doc, znacky(standfirst), velikost=12, po=14)

    par = doc.add_paragraph()
    par.paragraph_format.space_before = Pt(10)
    psat(par, 'Wordová podoba příručky vygenerovaná z jejího zdroje. Text je sázen wordovými '
              'styly, takže se dá přeformátovat změnou stylu.', velikost=8.5, barva=INKOUST_SVET)
    zalom(doc)


def obsah(doc, nav):
    par = doc.add_paragraph(style='Heading 1')
    psat(par, 'Obsah')
    spodni_linka(par)
    for i, li in enumerate(nav.findall('.//li'), 1):
        p = doc.add_paragraph()
        p.paragraph_format.space_after = Pt(3)
        p.paragraph_format.left_indent = Cm(0.9)
        p.paragraph_format.first_line_indent = Cm(-0.9)
        psat(p, str(i) + ' ', tucne=True, barva=AKCENT_TMAVY)
        psat(p, text_z(li), tucne=True)
    zalom(doc)


def kroky(doc, ol):
    """Postup: číslovaný výčet, v každém kroku jeho nadpis tučně a pod ním vysvětlení."""
    polozky = []
    for li in ol.findall('./li'):
        h3 = li.find('.//h3')
        p = li.find('.//p')
        hlava = '**' + text_z(h3) + '**' if h3 is not None else ''
        polozky.append(hlava + (' — ' + znacky(p) if p is not None else ''))
    cislovane(doc, polozky)


def poznamka(doc, el):
    popiska = el.find('./span[@class="lab"]')
    text = ' '.join(znacky(p) for p in el.findall('./p'))
    vysvetlivka(doc, text_z(popiska) if popiska is not None else 'Pozor', text)


def vysledky(doc, el):
    """Tři podoby výsledku jako tabulka; buňka s pruhem nese tlumenou barvu výsledku."""
    radky, barvy = [], []
    for v in el.findall('./div'):
        bar = v.find('./div[@class="bar"]')
        popis = ' '.join(znacky(p) for p in v.findall('./p'))
        radky.append([text_z(bar) if bar is not None else '', popis])
        druh = [c for c in trida(v).split() if c in BARVA_VYSLEDKU]
        barvy.append(BARVA_VYSLEDKU[druh[0]] if druh else None)
    tab = tabulka(doc, ['Pruh s výsledkem', 'Co znamená'], radky, [5.6, SAZBA_CM - 5.6])
    for i, barva in enumerate(barvy, 1):
        if barva and tab is not None and i < len(tab.rows):
            vypln_bunky(tab.rows[i].cells[0], barva)


def obrazek(doc, figure):
    img = figure.find('.//img')
    if img is None:
        return
    m = re.match(r'\{\{IMG:(.+?)\}\}', img.get('src') or '')
    if not m:
        return
    cesta = os.path.join(OBRAZKY, m.group(1))
    if not os.path.exists(cesta):
        odstavec(doc, '[chybí obrázek ' + m.group(1) + ']', barva=INKOUST_SVET)
        return
    info = Image.from_file(cesta)
    sirka = SAZBA_CM
    if info.px_height * sirka / info.px_width > MAX_VYSKA_CM:
        sirka = MAX_VYSKA_CM * info.px_width / info.px_height
    par = doc.add_paragraph()
    par.alignment = WD_ALIGN_PARAGRAPH.CENTER
    par.paragraph_format.keep_with_next = True
    par.paragraph_format.space_after = Pt(2)
    par.add_run().add_picture(cesta, width=Cm(sirka))
    _cislo_obrazku[0] += 1
    popiska = doc.add_paragraph(style='Caption')
    psat(popiska, 'Obrázek ' + str(_cislo_obrazku[0]) + ' ', tucne=True, barva=AKCENT_TMAVY)
    cap = figure.find('.//figcaption')
    psat(popiska, znacky(cap) if cap is not None else (img.get('alt') or ''))


def slovnicek(doc, dl):
    radky = []
    for div in dl.findall('./div'):
        dt, dd = div.find('./dt'), div.find('./dd')
        if dt is not None and dd is not None:
            radky.append([text_z(dt), znacky(dd)])
    tabulka(doc, ['Pojem', 'Význam'], radky, [4.4, SAZBA_CM - 4.4])


def zpracuj(doc, el, kapitola):
    if not isinstance(el.tag, str):
        return
    tag = el.tag.lower()
    cls = trida(el).split()
    if tag in ('script', 'style'):
        return
    if tag == 'h2':
        kapitola[0] += 1
        nadpis1(doc, str(kapitola[0]), text_z(el))
    elif tag == 'h3':
        nadpis2(doc, '', text_z(el))
    elif tag == 'p':
        odstavec(doc, znacky(el))
    elif tag == 'ul':
        odrazky(doc, [znacky(li) for li in el.findall('./li')])
    elif tag == 'ol' and 'steps' in cls:
        kroky(doc, el)
    elif tag == 'ol':
        cislovane(doc, [znacky(li) for li in el.findall('./li')])
    elif tag == 'figure':
        obrazek(doc, el)
    elif tag == 'dl':
        slovnicek(doc, el)
    elif 'note' in cls:
        poznamka(doc, el)
    elif 'verdicts' in cls:
        vysledky(doc, el)
    else:
        for dite in el:
            zpracuj(doc, dite, kapitola)


def main():
    if not os.path.exists(ZDROJ):
        print('nenalezeno: ' + ZDROJ)
        return 1
    # Jako text, ne bajty: z bajtů lxml kódování odhaduje, a u tohoto zdroje četl češtinu
    # jako Latin-1 (zjištěno 14. 10. 2026 na prvním výstupu).
    with open(ZDROJ, encoding='utf-8') as f:
        strom = lxml.html.fromstring(f.read())

    doc = Document()
    zaklad(doc, TITULEK)

    mast = strom.find('.//header')
    if mast is not None:
        obalka(doc, mast)
    nav = strom.find('.//nav')
    if nav is not None:
        obsah(doc, nav)

    kapitola = [0]
    for sekce in strom.findall('.//section'):
        zpracuj(doc, sekce, kapitola)

    paticka = strom.find('.//footer')
    if paticka is not None:
        for p in paticka.findall('./p'):
            odstavec(doc, znacky(p), velikost=8.5, barva=INKOUST_SVET, pred=14)

    doc.core_properties.title = TITULEK
    doc.core_properties.subject = 'Analyzátor elektronických podpisů — rychlý start'
    doc.core_properties.language = 'cs-CZ'
    doc.core_properties.comments = ('Vygenerováno z docs/prirucka-src.html skriptem '
                                    'docs/gen-prirucka-docx.py.')
    doc.save(CIL)
    print('hotovo: ' + CIL + ' (' + str(kapitola[0]) + ' kapitol, '
          + str(_cislo_obrazku[0]) + ' obrázků)')
    return 0


if __name__ == '__main__':
    sys.exit(main())
