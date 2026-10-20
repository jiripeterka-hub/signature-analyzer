# Architecture notes

The application is two layers stacked on one another. The lower one is **DSS** —
document parsing, certificate paths, the ETSI EN 319 102-1 validation process.
The upper one is everything DSS deliberately does not do: deciding what a result
*means*, saying it in Czech, and offering the one action that can still change it.

Most of this document is about the upper layer, because the lower one is DSS's
own documentation.

## Validation flow

```
File (drag & drop, or FileChooser)
  -> DocumentLoader.load()          wraps as a DSS DSSDocument; tolerates a PDF
                                     whose header does not start at byte zero
  -> ValidatorFactory.validate()    attaches the certificate verifier, the
                                     validation policy and the revocation source
                                     stack; DSS auto-detects PDF / ASiC / CMS /
                                     XML / JSON
  -> Reports                        Simple + Detailed + Diagnostic Data +
                                     ETSI Validation Report
  -> ReportMapper.map()             the interesting part — see below
  -> ValidationResult               List<SignatureSummary> + ValidationCourse
  -> six views                      Zjednodušený / Rozšířený / Analýza /
                                     Digitální kontinuita / Detailní rozbor /
                                     Protokoly DSS
```

`ValidatorFactory` deliberately does not branch per format: DSS already inspects
the bytes (PDF header, ZIP + ASiC mimetype, ASN.1, XML root, JWS) to choose a
validator, and re-implementing that dispatch would be a worse copy of logic DSS
maintains against the specs.

## Situations, reasons, and the suppression table

This is the core of the upper layer and lives in `ReportMapper`.

**Situations** are findings — numbered facts about the document, derived from the
sub-indication, the diagnostic data, and checks of our own. *S5: the embedded
revocation data's issuer has expired. S31: the chain of time-stamps is unbroken.
S48: the certificate is revoked but nothing says when the signature was made.*
Numbers run to 48; two of them (28, 42) are withdrawn and stay unused so the
older ones keep meaning what they meant.

**Reasons** are what the reader is told — a problem, a technical explanation, and
what (if anything) helps. `detectReasons()` maps situations to reasons.

The mapping is not one-to-one, because several true findings on one document are
usually not equally *the* obstacle. A signature can carry a broken hash algorithm,
an unusable revocation record and a non-reference format at once; the reader needs
to be told the one that decides the outcome, not all three with equal weight. That
is what **`REASON_SUPPRESSIONS`** does: when reason A is present, reasons B, C…
are dropped.

Two properties of that table are load-bearing:

- **Every rule encodes a fact about the document, not a display preference.**
  Reason 21 silences reasons 2 and 25 because without a proof of existence the
  decisive instant lies in the present, so the advice those reasons give would be
  untrue. That stays true whether or not reason 21 itself is displayed —
  which is why suppression is decided from the *derived* set and applied at once.
  Doing it while iterating made the outcome depend on `Map.ofEntries` iteration
  order, which Java randomises per JVM: the same document showed different reasons
  on different launches of the application.
- **The rules were measured, not reasoned out.** Several exist in their present
  shape only because a corpus scan contradicted the obvious reading.

Both printable catalogues are generated from these structures — see below.

## Where trust decisions live

`TrustConfig`, deliberately separate from `ValidatorFactory` so that
validator selection stays trust-agnostic.

It builds a verifier over the **EU Trusted Lists**: ~4 900 certificates from 31
national lists plus the LOTL. Parsing those on every start would be unbearable, so
the resolved state is cached in a compact binary file (`trust-state-v2.bin`,
format version 7) and freshness is re-checked in the background afterwards.

The cache format carries **sunset** information — for each trust anchor, the
intervals during which its Trusted List entry actually held a qualified status.
This matters more than it sounds: a time-stamp is accepted or rejected by whether
its service held that status at the moment the time-stamp's existence is
independently proven, not by whether the TSA certificate is still valid today.
Version 6 of the cache predated this and assumed every anchor was trusted for all
time, which made an expired-but-preserved signature come out INDETERMINATE from
the cache and TOTAL_PASSED from a fresh parse. A v6-or-older file is therefore not
read as-is: it falls through to a reparse that rebuilds everything from the cached
XML, still without touching the network.

Alongside the Trusted Lists sit the Windows root store (`Windows-ROOT`, which is
why the packaged runtime must contain `jdk.crypto.mscapi`) and the user's own
trust anchors and CRLs.

## The revocation source stack

More layers than one would expect, each answering a failure the previous one
caused. From the top:

| | |
|---|---|
| `DocumentCrlSource` / `DocumentOcspSource` | answer from what the document already carries, so preservation does not re-download what is already inside |
| `FallbackCrlSource` | online first, then the user's own store |
| `FilteringCRLSource` | discards a CRL that says nothing authoritative about an expired certificate |
| `BestMatchCustomCrlSource` | picks the list from the user's store that is actually relevant to the moment being judged |
| `PostExpiryRevokedOnlySource` | drops a post-expiry record that proves nothing |
| `OcspFirstCrlFallbackRevocationDataLoadingStrategy` | OCSP first, CRL when the OCSP is post-expiry — and records the skipped response, because DSS discards it without a trace and the report still needs to know it existed |

`RevocationCoverage` is the single place that answers "does this token say anything
about this certificate", so that validation, preservation and the report cannot
disagree. `ReliedUponRevocation` carries the lists a finished validation leaned on
over to preservation, which asks different sources and would otherwise miss them.

## Strict by default, tolerant on request

`ValidationPolicyFactory` builds the DSS policy from `policy/constraint.xml` and
relaxes individual constraints per `AppSettings`. `LenientRevocationDataVerifier`
covers the two revocation relaxations independently.

Concessions apply to **one** verification and are then reset
(`AppSettings.resetValidationPolicy()`), and whether a concession actually decided
the outcome is reported on the result, so a positive verdict never quietly rests
on one.

## Acting on the document

None of these rewrite the signed bytes in place.

| | |
|---|---|
| `AugmentationService` | embeds validation data (level LT) |
| `TimestampingService` | adds a document time-stamp without going through DSS — PDFBox field, value and `addSignature()` in one session |
| `RestampingService`, `SignatureExtensionService` | repair or extend a chain of time-stamps |
| `FixationService` | wraps the untouched original in a time-stamped ASiC container with every piece of evidence obtainable, plus a `soupis.xml` recording provenance |

After preservation, prolongation or re-stamping the result is validated at once
through `MainView.analyze` — the same path any opened file takes — and
`ActionRecord` compares the state of each element before and after with what the
Digitální kontinuita tab predicted (`ActionRecordWindow` shows it and exports it
as a PDF/A protocol with SHA-256 hashes of both files). Elements are paired by
their DSS identifier, and time-stamps whose bytes the action changed (ASiC) by
production time and issuer.

Whether an action is offered at all is a question about the document, not about
whether the code could run: an archive time-stamp must not be created after the
certificate was revoked (EN 319 122-1, clause 6.3 m), and the application says so
instead of quietly producing an artefact that reads as stronger than it is.

## Beyond the signature itself

A signed file often is not only a signature. Separate inspectors read what else is
there and present it consistently: `ContainerInspector` (ASiC structure and what
each signature actually covers), `IsdsMessageInspector` (a `.zfo` data-box message,
verified through the time-stamp over `dmDm` rather than the copied `dmHash`),
`PdfAttachmentInspector`, `EvidenceRecordDetector`, `LegalisationLinkInspector`
(the reference eLegalizace leaves in a PDF), `PdfOrphanSignatureDetector`
(signature fields DSS will not process), `PdfSignatureAppearanceExtractor` and
`PdfSigningToolExtractor`.

The **Inspector** (Inspektor PDF / CAdES / ASiC / XAdES / JAdES, a button in the header of
Detailní rozbor, so expert mode only) shows the signature structure itself, as
stored: signatures and seals, document time-stamps, `/DSS`, `/VRI` and the PDF
revisions. `StructureInspector` picks the format, `PdfStructure`, `CadesStructure`,
`JadesStructure` and `XadesStructure` read it (the last one re-computes reference digests through
Apache Santuario with secure validation, resolving only files of the container or
the supplied detached document), `CmsStructure` decomposes every CMS object (signer info,
attributes, embedded time-stamps, certificates, CRLs, OCSP) and re-computes the
digests it can, and `StructureNode` is the tree the window `StructureInspectorWindow`
draws. Revisions are found in the bytes (`%%EOF` preceded by `startxref`), and each
revision prefix is opened on its own so `/DSS` can be shown as it was in that
revision. `StructureAnnotations` then pairs the nodes with DSS diagnostic data by
bytes (certificate DER, inner `BasicOCSPResponse`, signature value, time-stamp
token) and adds what DSS did with each — kept apart from the structural findings.
`StructureInspectorScan` (test tools) runs it over a collection.

## Threading and startup

Validation runs off the FX thread — DSS makes OCSP/CRL/AIA calls and parsing large
files is not instant. `WarmupService` runs one throw-away validation right after
the window appears, so the first real one does not pay for class loading;
`BackgroundWork` keeps trusted-list maintenance from competing with what the user
is doing. `StartupTrace` (`-Danep.timing`) reports where the time went.

## Číselníky situací a důvodů

Dva tisknutelné listy v `docs/` — `situace-tisk.html` a `duvody-tisk.html` — se
**generují ze zdrojového kódu**, neopisují se. Zdrojem je `ReasonDump`, který
texty i vazby vytáhne přímo z tříd, jež o nich rozhodují:

| list | co z něj čerpá |
|---|---|
| `situace-tisk.html` | `ResultView.SITUATION_LABELS` |
| `duvody-tisk.html` | `ResultView.REASONS`, `ResultView.REASON_WORKING_LABELS`, `ReportMapper.REASON_SUPPRESSIONS`; vazba situace → důvod se **měří** voláním `ReportMapper.detectReasons()` |

### Kdy je přegenerovat

Po každé změně kterékoli z těch čtyř struktur. **Ručně udržovaný list se tiše
rozejde s aplikací** — přesně to se stalo: v `situace-tisk.html` chyběly situace
46 a 47 od jejich zavedení a texty situací 2, 20 a 21 v něm zůstaly v podobě,
kterou kód už neříkal (tvrdily, že podpis na revokovaném certifikátu je
neplatný, ačkoli aplikace mezitím přešla na neznámou platnost).

Z kořene projektu:

```bash
mvn -o -q -DskipTests test-compile
mvn -o -q -pl app dependency:build-classpath -Dmdep.outputFile=../cp.txt -Dmdep.includeScope=test
java -Dstdout.encoding=UTF-8 \
     -cp "app/target/test-classes;app/target/classes;$(cat cp.txt)" \
     cz.digitalnikontinuita.anep.ui.ReasonDump > dump.txt
python3 docs/gen-situace-tisk.py dump.txt
python3 docs/gen-duvody-tisk.py dump.txt
```

Dvě věci, na kterých ten postup padá, a obojí tiše:

- **`-pl app` u `build-classpath` je povinné.** Bez něj sestaví Maven classpath
  rodičovského POMu, ten žádné závislosti nemá, a `cp.txt` vyjde prázdný. Chyba
  se pak projeví až o krok dál jako `NoClassDefFoundError` na BouncyCastle.
- **`-Dstdout.encoding=UTF-8` není ozdoba.** Bez něj píše JVM na Windows do
  roury v CP1250 a v obou listech skončí místo diakritiky rozsypaný čaj.

`cp.txt` je v `.gitignore`, takže jeho přepsáním se v repozitáři nic nerozbije.

### Co se udržuje ručně

Jen `EXTRA` v `gen-situace-tisk.py` — pět situací, které popis v kódu nemají
(28 a 42 jsou zrušené, 29, 34 a 35 aplikace hlásí jen číslem a vysvětlení je
v komentáři u jejich detekce v `ReportMapper`). Přibude-li další taková, patří
sem; všechno ostatní si generátor vezme z kódu sám.

## Measuring instead of reasoning

`app/src/test/java/.../tools/` holds around forty headless probes and scans. They
are not tests; they answer questions that cannot be answered by reading the code,
because the answers depend on live revocation data and on the state of the Trusted
Lists.

The working rule in this codebase is that a claim about behaviour is worth what
its measurement is worth. So a decision recorded in a comment names the document
that produced it — and when a scan contradicts the obvious reading, the scan wins.
Several rules exist in their current shape for exactly that reason, and at least
one was reverted after a corpus run showed it firing on eight documents, all of
them false positives.

`SituationScan` is the workhorse; `-reasons`, `-otherval` and `-strict` make it
report the derived reasons, the advisory-block predicates and a deterministic
settings baseline respectively.
