# AnEP — pokyny pro práci na projektu

AnEP (Analyzátor elektronických podpisů) je desktopová aplikace v Javě (JavaFX), která ověřuje
elektronické podpisy, pečetě a časová razítka nad knihovnou DSS 6.5 a vykládá výsledek česky.
Uživatel je odborník na elektronické podpisy; s aplikací řeší i právní účinky podpisu.

## Kde co je

- `app/src/main/java/cz/digitalnikontinuita/anep/core/` — ověřování a jeho vyhodnocení:
  `ValidatorFactory` (zdroje a spuštění DSS), `ValidationPolicyFactory` (úpravy politiky DSS
  a ústupky), `TrustConfig` (důvěryhodné seznamy), `ReportMapper` (situace → důvody, souhrn prvku).
- `app/src/main/java/cz/digitalnikontinuita/anep/ui/` — záložky; texty karty Analýzy hlavně
  v `AdvisorView`, záhlaví a seznamy v `ResultView`.
- `app/src/main/resources/texty/` — **všechny texty důvodů a situací** (`duvody.txt`, `situace.txt`),
  ne v Javě. Náhradní znění mají oddíl s lomítkem (`[18/necitelny]`). Čísla důvodů a situací se
  nikdy nepřečíslovávají; nový oddíl s lomítkem je třeba zapsat do očekávaného výčtu
  v `ReasonTextsTest` / `SituationTextsTest`, nový důvod či situaci i do počtů tamtéž.
- `app/src/test/java/.../tools/` — měřicí sondy (sken sbírek, výpis karty, protokoly DSS).
- `docs/` — dokumenty pro čtenáře. Wordové podoby se **generují** (`gen-*.py` přes `docx_kit.py`),
  ručně se nemění. Vygenerované `.docx` jsou jen předloha: uživatel je sám upraví, podepíše
  a vydá na https://bajecnysvet.cz/anep/. Do repozitáře nepatří (`.gitignore`); po věcné
  změně dokumentu uživateli říct, které pasáže si má přenést do svých vydaných podob. `politika-overovani.md` je zdroj politiky ověřování; když se změní chování
  ověřování, aktualizovat ji a přegenerovat `python docs/gen-politika-overovani.py`.
- `docs/architecture.md` — stavba aplikace a spouštění sond (classpath `cp.txt`).

## Překlad a testy

- **Nepřekládat pod běžící aplikací.** JVM si třídy dotahuje průběžně; přepsané `target/classes`
  končí `NoSuchMethodError`. Před `mvn compile` / `mvn test` ověřit:
  `Get-CimInstance Win32_Process -Filter "Name like 'java%'" | ? { $_.CommandLine -match 'anep.Main|javafx:run' }`.
  Když běží, říct to uživateli; sondy lze pustit nad kopií tříd mimo `target/classes`
  (`javac -d app/target/cls-new …`, složka pod `app/target/` je v `.gitignore`).
- `mvn -o test` nechat doběhnout jako samostatný příkaz a výsledek číst ze
  `app/target/surefire-reports/*.txt`. **Nikdy neřetězit `mvn test | grep … && git push`.**
  Hláška „Surefire is going to kill self fork JVM“ je obvyklá a sama o selhání nesvědčí.
- `mvn -o -q test-compile` hlavní třídy nepřeloží — po změně v `main` napřed `mvn -o -q compile`.
- Sondy dědící z `Application` potřebují JavaFX na module-path (`--module-path <javafx jary
  z cp.txt> --add-modules javafx.controls,javafx.graphics,javafx.base`), jinak „JavaFX runtime
  components are missing“.

## Úpravy souborů

- Soubory mají smíšené konce řádků (část CRLF, část LF). Při skriptové úpravě zachovat ten, který
  soubor má; nikdy nezapisovat s `newline='\r\n'` řetězec, který už `\r\n` obsahuje.
- **Editační skripty psát nástrojem Write do souboru a pak spustit**, ne přes heredoc v Bash:
  heredoc požírá zpětná lomítka (`\r` v komentáři se stane znakem CR, `\\n` se smrskne).
  Po hromadné úpravě zkontrolovat osamělé `\r` a `git ls-files --eol` — `w/-text` znamená,
  že git soubor považuje za binární.
- Komentáře a javadoc v kódu jsou česky (starší části anglicky) a nesou **proč**, včetně data
  a toho, na čem se nález naměřil. Psát stejně.

## Jak se rozhoduje

- **Měřit, ne odvozovat.** Dopad změny ověřování se měří sondou na sbírkách `D:\tmp`
  a `D:\Downloads` (před a po). Sken obou trvá desítky minut — pouštět na pozadí; když se
  zasekne, procházet po podsložkách.
- Zpřísnit nebo zmírnit ověřování je rozhodnutí uživatele: navrhnout s odůvodněním, neprovádět
  samovolně.
- **Důvěrné dokumenty:** část zkušebních souborů jsou skutečné smlouvy a výpisy. Do repozitáře
  (kód, komentáře, testy, commit message) se nesmí dostat jejich jméno, cesta ani obsah — nález
  popsat tvarem prvku. Celý sken sbírky jen na vyžádání.
- Drobné změny UI jen přeložit a předat k vyzkoušení; sondu nad `MainView` nepsat bez vyžádání.
  Nové tlačítko či dialog ověřit skutečným stiskem (`fire()`), ne snímkem.

## Texty pro čtenáře

- O důvodech psát „je indikován“, ne „svítí“ — ani v odpovědích.
- Dokumenty v `docs/` nemluví o situacích (vnitřní věc aplikace); mluví o důvodech a poznámkách.
- Tvrzení o jiných ověřovatelích (CzechPOINT, SecuSign, Signer, QVerify) formulovat podmíněně
  („mohou“) a bez výčtu jejich tolerancí — aplikace nezná jejich verzi ani nastavení.

## Git

- Pracuje se na `master`. Commit i push jen na pokyn uživatele, commit message česky
  (předmět + odstavec proč), s řádkem `Co-Authored-By` podle pokynů prostředí.
