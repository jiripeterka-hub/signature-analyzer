# Soupis změn AnEP

Změny ve verzích aplikace AnEP (Analyzátor elektronických podpisů). Slouží jako podklad
pro soupis změn ke každé nové vydané verzi.

## Připravovaná verze (změny oproti 1.0.0)

### Výklad výsledku ověření

- **Druh podpisu či pečeti s porušenou integritou.** Když se podepsaný obsah po podpisu
  změnil, uváděla verze 1.0.0 u druhu prvku „autentizační prvek nesplňující požadavky pro
  zaručený ani kvalifikovaný podpis nebo pečeť“, a to i u podpisu s kvalifikovaným
  certifikátem a kvalifikovaným prostředkem (QSCD). Nově AnEP druh zachová a označí ho jako
  neplatný, například „neplatný kvalifikovaný elektronický podpis“, „neplatný uznávaný
  elektronický podpis“ nebo „neplatná kvalifikovaná elektronická pečeť“. Výklad „V terminologii
  práva“ nejprve uvede druh stejně jako u platného prvku a pak doplní, že se dokument po podpisu
  změnil, podpis jeho současný obsah nekryje, a proto se k podpisu na dokumentu v této podobě
  neváží účinky kvalifikovaného (uznávaného, zaručeného) podpisu či pečeti podle nařízení eIDAS
  ani zákona č. 297/2016 Sb. Verdikt „neplatný (porušení integrity)“ a důvod 7 zůstávají beze
  změny. U ostatních příčin neplatnosti (například odvolání certifikátu před podpisem) zůstává
  dosavadní znění.

### Prohlížeč kontejnerů

- **Jméno dokumentu vloženého v obálce .p7m.** Obálka CMS / PKCS #7 sama jméno podepsaného
  souboru neuchovává. Nese ho jen nepovinný podepsaný atribut content-hints, který některé
  podepisující nástroje vyplňují. Verze 1.0.0 vložený dokument vždy pojmenovala podle obálky
  (z `smlouva podepsana.p7m` udělala `smlouva podepsana.pdf`). Nově, když obálka content-hints
  se jménem souboru nese, ukáže prohlížeč a nabídne k uložení původní jméno dokumentu a uvede
  ho i v záhlaví (u odděleného podpisu .p7s také). Když ho nenese, jméno se odvodí jako dosud
  a karta prvku v řádku „Původ názvu“ řekne, že je odvozené z názvu obálky.
- **Odpovědi OCSP vložené v podpisech .p7s a .p7m.** Odpověď OCSP lze v objektu CMS uložit
  dvojím způsobem: samotnou vnitřní strukturu (BasicOCSPResponse), nebo celou odpověď podle
  RFC 5940. Verze 1.0.0 v prohlížeči kontejnerů i v Inspektoru četla jen první způsob, takže
  podpisy s odpověďmi uloženými celé (tak je ukládá například knihovna DSS u CAdES B-LT
  a B-LTA) vypadaly, jako by žádné revokační údaje nenesly („Vložené CRL / OCSP: 0 / 0“).
  Nově se ukážou oba způsoby a celou odpověď lze vyjmout beze změny. Prohlížeč kontejnerů
  navíc nově ukazuje certifikáty, seznamy CRL a odpovědi OCSP z nepodepsaných atributů
  certificate-values a revocation-values (rozšířená forma CAdES-X-L), které dosud přecházel.
  Údaj uložený na více místech se ukáže jednou a karta v řádku „Uloženo v“ uvede všechna
  místa. Ověřování samo se nemění, tyto údaje četlo vždy.

### Instalační balíčky

- **Linuxová verze: položka v nabídce aplikací.** Přenosný archiv pro Linux nově obsahuje
  skript `pridat-do-nabidky.sh`. Po rozbalení stačí spustit `./AnEP/pridat-do-nabidky.sh`
  a AnEP se objeví v nabídce aplikací přihlášeného uživatele. Skript nepotřebuje sudo, nic
  nemaže ani neinstaluje a funguje na libovolné distribuci. Jen zapíše spouštěč pro místo, kde
  AnEP právě leží. Po přesunu adresáře AnEP je třeba skript spustit znovu. Odebrání položky
  skript sám vypíše (`rm ~/.local/share/applications/anep.desktop`).
