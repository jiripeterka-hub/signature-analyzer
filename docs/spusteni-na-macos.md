# Jak spustit AnEP na Macu pomocí Claude Code

**Analyzátor elektronických podpisů (AnEP) — neoficiální postup pro macOS**

Stav ke dni 21. 11. 2026 · AnEP 1.5.0

## 1 Oč jde

AnEP se vydává pro Windows a Linux. Pro macOS hotový balíček zatím není: aby ho macOS bez potíží spustil, musel by ho podepsat a nechat prověřit (notarizovat) Apple.

Na Macu lze ale AnEP **sestavit ze zdrojového kódu a spustit přímo na vlastním počítači**. Zdrojový kód je veřejný na GitHubu pod licencí EUPL 1.2. Kdo má nainstalovaný **Claude Code** (programovací asistent od firmy Anthropic, který pracuje v Terminálu), může mu celé sestavení svěřit: stačí mu dát odkaz na repozitář a jeden pokyn.

Protože se aplikace sestaví přímo na Macu a nestahuje se jako hotový program, Gatekeeper ji neblokuje.

Jde o **neoficiální postup**. Autor verzi pro macOS netestuje a nepodporuje ji tak jako verze pro Windows a Linux (podrobnosti v kapitole 6).

## 2 Co je potřeba

- Mac s procesorem Apple Silicon (M1 a novější) nebo Intel a aktuální verzí macOS.
- Nainstalovaný a přihlášený **Claude Code** (https://claude.com/claude-code).
- Připojení k internetu. Při prvním sestavení se stahuje Java, Maven a knihovny, celkem několik set MB.
- Asi 2 GB volného místa.
- Heslo k účtu na Macu: instalace Javy ho vyžaduje.

Doporučený, ale ne nutný je správce balíčků **Homebrew** (https://brew.sh). Pokud ho nemáte, nainstalujte ho sami podle návodu na jeho stránce, ještě před spuštěním Claude Code. Instalace se ptá na heslo a Claude Code takové dotazy sám zodpovědět neumí.

## 3 Postup

1. Otevřete **Terminál** (Aplikace → Utility → Terminál).
2. Vytvořte složku pro AnEP a přejděte do ní:

   ```
   mkdir -p ~/AnEP && cd ~/AnEP
   ```

3. Spusťte Claude Code:

   ```
   claude
   ```

4. Vložte tento pokyn:

   > Stáhni z GitHubu aplikaci AnEP z repozitáře https://github.com/jiripeterka-hub/signature-analyzer, a to vydání se značkou v1.5.0 (`git clone --branch v1.5.0`), do této složky. Zkontroluj, že mám JDK 21 nebo novější (doporučeně Eclipse Temurin 25 ve verzi 25.0.5.1 či novější) a Maven 3.9 nebo novější. Co chybí, nainstaluj přes Homebrew (`brew install --cask temurin@25`, `brew install maven`). Když instalace bude chtít heslo, řekni mi, ať příkaz spustím sám. Potom aplikaci spusť příkazem `mvn -pl app -am javafx:run` podle README. Nakonec mi napiš, jakým příkazem ji příště spustím sám bez tebe.

5. Claude Code bude postupně navrhovat příkazy a před každým se zeptá, zda ho smí provést. Co dělají, popisuje kapitola 4.
6. První sestavení trvá několik minut, protože se stahují knihovny. Pak se otevře okno AnEP.

## 4 Co Claude Code udělá

Aby bylo jasné, co schvalujete, tady jsou kroky, které by měl provést:

| Krok | Typický příkaz | Proč |
|---|---|---|
| Stažení zdrojového kódu | `git clone --branch v1.5.0 https://github.com/jiripeterka-hub/signature-analyzer.git` | vydaná verze 1.5.0, ne rozpracovaný stav |
| Instalace Javy | `brew install --cask temurin@25` | AnEP potřebuje JDK 21 nebo novější |
| Instalace Mavenu | `brew install maven` | nástroj, který aplikaci sestaví a stáhne knihovny |
| Sestavení a spuštění | `cd signature-analyzer && mvn -pl app -am javafx:run` | přeloží aplikaci a otevře její okno |

Když navrhne něco výrazně jiného, například příkaz se `sudo`, stahování z jiných adres než GitHub, Homebrew a Maven Central nebo změny mimo složku `~/AnEP`, zeptejte se ho proč, nebo příkaz odmítněte.

Repozitář obsahuje i soubor `CLAUDE.md` s pokyny pro autorův vývoj aplikace. Claude Code si ho přečte, ale pro pouhé spuštění aplikace nemá význam.

## 5 Další spuštění a aktualizace

Claude Code už potom nepotřebujete. AnEP spustíte v Terminálu takto:

```
cd ~/AnEP/signature-analyzer
mvn -pl app -am javafx:run
```

Okno Terminálu musí zůstat otevřené, dokud s AnEP pracujete. Jeho zavřením se ukončí i aplikace.

Na novější vydání přejdete takto (místo `v1.6.0` uveďte značku vydání, které chcete):

```
cd ~/AnEP/signature-analyzer
git fetch --tags
git checkout v1.6.0
mvn -pl app -am javafx:run
```

Seznam vydání je na https://github.com/jiripeterka-hub/signature-analyzer/tags.

Celý postup lze provést i bez Claude Code, stačí zadat příkazy z kapitoly 4 ručně.

## 6 Čím se to liší od verze pro Windows

- **Platforma není ověřená.** Autor AnEP na macOS netestuje. Vzhled okna, otevírání souborů a další drobnosti se mohou lišit a chyby specifické pro macOS nemusí být opraveny.
- **Výsledek ověření podpisu stojí na téže knihovně (DSS) a týchž důvěryhodných seznamech** jako na Windows. Kořenové certifikáty českých autorit nese aplikace sama a doplňuje je kořeny z klíčenky macOS.
- **Chybí instalátor a položka „Ověřit v AnEP“ v kontextové nabídce.** Soubory se otevírají z aplikace.
- **Ověření podpisu kódu programů pro Windows** (`.exe`, `.dll`, `.msi`) se na Windows opírá o úložiště certifikátů Windows. Na macOS může dopadnout jinak.
- **Data aplikace** (nastavení, stažené důvěryhodné seznamy, vlastní úložiště certifikátů) jsou ve složce `SignatureAnalyzer` v domovské složce uživatele.
- **Aktualizace** si uživatel provádí sám podle kapitoly 5.

## 7 Když něco nefunguje

- **„JavaFX runtime components are missing“ nebo chyba při překladu:** zkontrolujte příkazem `java -version`, že běží JDK 21 nebo novější. Pokud je na Macu Javy víc, nastavte proměnnou `JAVA_HOME`. Claude Code s tím umí poradit.
- **Chyba stahování knihoven:** zkuste to znovu později. Při prvním sestavení se stahuje z repozitáře Maven Central.
- Chybu lze nahlásit autorovi na adresu uvedenou na https://bajecnysvet.cz/anep/. Uveďte, že jde o macOS, jaký máte procesor a co vypsal Terminál.
