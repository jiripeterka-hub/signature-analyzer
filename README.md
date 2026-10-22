# AnEP — Analyzátor elektronických podpisů

A Windows desktop tool for validating electronic signatures, seals and time-stamps
against **eIDAS** and the ETSI standards, and for explaining the result in plain
Czech to someone who is not a PKI specialist.

Signature parsing and the validation process itself are delegated to the European
Commission's open-source **DSS (Digital Signature Services)** library — the
reference implementation of ETSI EN 319 102-1. What this application adds on top
is the part DSS deliberately leaves out: turning `INDETERMINATE/REVOKED_NO_POE`
into a sentence that says what happened, why, and what (if anything) can be done
about it.

The user interface is in Czech.

## What it reads

| | |
|---|---|
| PDF | PAdES, including PDF-NOT-ETSI signatures predating eIDAS |
| ASiC-S / ASiC-E | `.asice`, `.asics`, `.sce`, `.scs`, and plain `.zip` containers |
| Detached / standalone | CAdES (`.p7s`, `.p7m`, `.cms`), XAdES (`.xml`), JAdES (`.json`, `.jose`, `.jws`) |
| Data-box messages | `.zfo` — ISDS messages, verified through the time-stamp over `dmDm` |
| Evidence records | RFC 4998 (`.ers`, `.er`) and XMLERS, standalone or embedded |

## What it shows

Six tabs, in increasing depth:

- **Zjednodušený přehled** — the verdict, the signatory, the time.
- **Rozšířený přehled** — the same in nine named, collapsible blocks: certificate,
  time-stamps, decisive instant, revocation check, algorithms, visual appearance…
- **Analýza** — why it came out that way. Numbered *situations* (what was found)
  are mapped to numbered *reasons* (what it means and what helps), a narrative of
  how the validation actually proceeded, and — where the verdict permits it —
  the buttons that can still do something about it.
- **Digitální kontinuita** — how long the document stays verifiable, and what
  happens when it stops.
- **Detailní rozbor** — certificate paths, revocation records, container structure.
- **Protokoly DSS** — the raw Simple and Detailed reports, downloadable as XML or PDF.

Protocols export as **PDF/A-2b**.

### Document locking

A PDF can be locked by the act of signing it — Acrobat's *Lock document after
signing* — and that is **not** a certification signature: such a document may carry
no `/Perms /DocMDP` at all, so nothing in the usual reporting mentions it. It is
written instead as `/Lock` on the signature field (`/Action`, plus `/P` since
PDF 2.0 — the level of change still permitted) and as a `FieldMDP` transform in the
signature dictionary. Both halves are read, because each carries only part of the
answer: the scope comes from the signed `FieldMDP`, the permission level only from
`/Lock`.

Reported as a grey note under the element type in both overviews, a *Zámek
dokumentu* block in Analýza, the raw dictionaries in Detailní rozbor, and — on a
signature field that is still empty — what will be locked once somebody signs it.
A lock says nothing about validity, and it does not stand in the way of
preservation: embedding revocation data and adding a document time-stamp extends
the signature rather than changing the document.

## What it can do to a document

None of these touch the signed bytes; each writes a new file.

- **Zachovat ověřitelnost** — augment to B-LTA: embed the missing revocation data
  and add an archive time-stamp, so the signature survives the expiry of its own
  certificate. Three routes lead here (preserve, prolong, re-stamp) depending on
  what the document already carries; all of them save the same kind of result.
  Offered only where it is both possible and honest — a certificate revoked before
  the signature can be shown to have existed disqualifies the document
  (ETSI EN 319 122-1, clause 6.3 m), and the application says so instead.
- **Vytvořit forenzní balíček** (zafixování) — for documents that cannot be
  preserved. Wraps the original, **untouched**, in a time-stamped ASiC container
  together with every piece of validation material that could be obtained and a
  `soupis.xml` recording where each came from. Deliberately a container and not a
  rewritten PAdES: a best-effort in-place preservation would be indistinguishable
  from a real one, and would read as the opposite of what happened.

## Trust and revocation

`TrustConfig` builds a verifier over the **EU Trusted Lists (LOTL)** — 31 national
lists, ~4 900 certificates — cached locally as `trust-state-v2.bin` so start-up does
not wait for the network. Freshness is re-checked in the background. The Windows
root store (`Windows-ROOT`) is consulted as well, which is why the packaged runtime
must include `jdk.crypto.mscapi`.

Revocation data is fetched live (OCSP first, CRL as fallback) and can be
supplemented from the user's own CRL store and trust anchors. Settings are kept in
`%APPDATA%\SignatureAnalyzer\`.

### Strict by default, tolerant on request

Validation runs strictly per the standards unless told otherwise. Three concessions
can be granted individually, and each applies to **one** verification only:

- accept revocation data issued after the certificate expired
- accept revocation data from an issuer already expired at the time of issue
- accept PDF-NOT-ETSI as a signature format

Whether a concession actually decided the outcome is reported on the result itself,
so a positive verdict never quietly rests on one.

## Project layout

```
signature-analyzer/
├── pom.xml                  parent POM — imports the DSS BOM
├── app/
│   └── src/main/java/com/yourorg/sigtool/
│       ├── Main.java        JavaFX entrypoint
│       ├── core/            DSS integration, trust, augmentation, time-stamping
│       ├── model/           plain UI-facing result types
│       └── ui/              JavaFX views
├── packaging/windows/       jpackage scripts for the .msi and the portable ZIP
└── docs/                    architecture notes + generated reference sheets
    ├── schopnosti.html      what the application can do — for readers, not builders
    ├── situace-tisk.html    číselník situací   ) generated from source,
    ├── duvody-tisk.html     číselník důvodů    ) do not edit by hand
    ├── gen-situace-tisk.py
    └── gen-duvody-tisk.py
```

> **After changing `SITUATION_LABELS`, `REASONS`, `REASON_WORKING_LABELS` or
> `REASON_SUPPRESSIONS`, both sheets must be regenerated** — the procedure and its
> two silent traps are in
> [docs/architecture.md](docs/architecture.md#číselníky-situací-a-důvodů).

**Word documents.** The user handbook, the validation and preservation policies and
the capability overview are published by the author as edited and electronically
signed Word documents at <https://bajecnysvet.cz/anep/>. The `gen-*.py` scripts in
`docs/` only produce unedited drafts of them, so the generated `.docx` files are
deliberately kept out of this repository.

## Prerequisites

- JDK 21+
- Maven 3.9+
- (Windows packaging only) [WiX Toolset v3](https://wixtoolset.org/) on PATH

## Running it

```bash
mvn -pl app -am javafx:run
```

`run.bat` does the same, picking the newest installed Adoptium JDK — the fixed path
otherwise breaks on every JDK update.

## Building

```powershell
mvn -pl app -am package                      # jar
.\packaging\windows\dist.ps1                # portable app-image + ZIP
.\packaging\windows\dist.ps1 -Installer     # the same, plus an .msi installer
```

One script builds both. The installer is packaged from the finished app-image, so
it gets exactly what the portable build was verified with — the JavaFX module path,
`jdk.crypto.mscapi` in the runtime, `licenses\` and `relink\`. There used to be a
second script for the installer and it drifted: by the time anyone looked, it named
the vendor "YourOrg" and produced an application that could not start. `-Installer`
needs [WiX Toolset v3](https://github.com/wixtoolset/wix3/releases) on `PATH`.

### Linux (portable)

```bash
bash packaging/linux/dist.sh 1.0.0 1.0      # app-image + AnEP-1.0-linux-<arch>-portable.tar.gz
```

The same steps as `dist.ps1` without the installer: one shaded JAR, JavaFX outside the
classpath in `lib/app/fx`, `licenses/` and `relink/`. jpackage only builds for the
system and CPU it runs on, so the two Linux archives are built by GitHub Actions —
workflow **Linux portable** (`.github/workflows/linux-portable.yml`), started by hand
(`gh workflow run linux-portable.yml -f version=1.0.0 -f release=1.0`), one job on
`ubuntu-22.04` (x64) and one on `ubuntu-22.04-arm` (aarch64). Each job also starts the
packaged application under a virtual display and fails if it exits or logs an exception.

Two things differ from Windows. The ARM64 build uses **JavaFX 25**: JavaFX 21, used
everywhere else, has no `linux-aarch64` artifacts on Maven Central. And the JavaFX
classifier for packaging is passed as `-Danep.fx.classifier`, never as
`-Djavafx.platform` — that name belongs to the JavaFX parent POM, and Maven 3.10
rejects the JavaFX POMs as recursive when it is set, leaving only empty JARs.
Application data on Linux lives in `~/.config/SignatureAnalyzer` (`AppDirs`).

## Tests and measurement tools

`app/src/test/java/.../core/` holds ordinary JUnit tests. `.../tools/` holds
something else: around forty headless **probes and scans** that run the real
pipeline over a corpus of signed documents and report what came out.

They exist because most questions in this domain cannot be answered by reading the
code. Whether a situation still fires anywhere, whether a change to the suppression
table alters any verdict, what a document's card will actually say — all of it
depends on live revocation data and on the state of the Trusted Lists, so the only
honest answer comes from running it. `SituationScan` is the workhorse:

```bash
java -cp "app/target/test-classes;app/target/classes;$(cat cp.txt)" \
     cz.digitalnikontinuita.anep.tools.SituationScan -strict -reasons "D:\corpus"
```

Findings from such runs are recorded in the comment next to the code they justify,
with the document that produced them named. Several rules in `ReportMapper` and
`AdvisorView` exist in their current shape only because a scan contradicted the
obvious reading.

## Licence

Copyright (c) 2024-2026 **Jiří Peterka** (<jiri@peterka.cz>), who is the licensor.

AnEP is distributed under the **European Union Public Licence v. 1.2 (EUPL-1.2)**.
The full text is in [LICENSE](LICENSE); the Czech wording, which is equally
authentic, is in
[packaging/windows/licenses/EUPL-1.2-cs.txt](packaging/windows/licenses/EUPL-1.2-cs.txt).

The EUPL was chosen deliberately rather than out of convenience. It has an official
Czech text with equal legal force — which matters for a tool whose users are being
introduced to the subject in Czech — its compatibility appendix names LGPL 2.1
and MPL 2, the licences of DSS and Saxon, and it comes from the same body as DSS
itself.

**The tool's purpose is stated in the application and in its protocols, not in the
licence.** AnEP serves teaching and getting acquainted with electronic signatures,
and its result carries no warranty; restricting *use* to education would have made
the software non-free without protecting anything.

The name "AnEP" and the application icon are not covered by the grant: if you
distribute a modified version, give it a different name.

### Third-party components

The application bundles around 110 runtime artifacts and a Java runtime. Their
licences and the attribution notices their authors ship are collected in
`packaging/windows/licenses/`, which is copied into every distribution:

| | |
|---|---|
| DSS (45 modules) | LGPL-2.1-or-later |
| Apache Software Foundation (~42 artifacts) | Apache-2.0 |
| OpenJFX, bundled Temurin runtime | GPL-2.0 with Classpath Exception |
| Bouncy Castle, SLF4J, jmail, json-sKema | MIT |
| Saxon-HE | MPL-2.0 |
| Jakarta XML Binding and friends | EDL-1.0 |
| stax2-api | BSD-2-Clause |
| xml-apis-ext | Apache-2.0 + W3C Software Licence |
| Feather icons (two header icons) | MIT |

`packaging/collect-licenses.py` regenerates `THIRD-PARTY-LICENSES.txt` from the
jars that are actually packaged, so the list cannot drift away from what ships.
Run it whenever a dependency changes.

Because the distribution merges DSS into a single jar with the application rather
than shipping it alongside, every build also carries a `relink/` directory holding
the application's own classes and the unmodified DSS jars — what LGPL section 6
asks for so that DSS can be replaced with another version.
