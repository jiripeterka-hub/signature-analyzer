package cz.digitalnikontinuita.anep.core;

import eu.europa.esig.dss.asic.cades.ASiCWithCAdESTimestampParameters;
import eu.europa.esig.dss.asic.cades.signature.ASiCWithCAdESService;
import eu.europa.esig.dss.diagnostic.CertificateWrapper;
import eu.europa.esig.dss.diagnostic.DiagnosticData;
import eu.europa.esig.dss.diagnostic.RevocationWrapper;
import eu.europa.esig.dss.enumerations.ASiCContainerType;
import eu.europa.esig.dss.enumerations.DigestAlgorithm;
import eu.europa.esig.dss.enumerations.RevocationType;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.model.FileDocument;
import eu.europa.esig.dss.model.InMemoryDocument;
import eu.europa.esig.dss.service.tsp.OnlineTSPSource;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;
import cz.digitalnikontinuita.anep.model.ValidationResult;

import java.io.File;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Zafixování — wraps a document, untouched, in a time-stamped ASiC container together with every
 * piece of validation material that could be obtained, so that someone can examine it later. The
 * result is called a <em>forenzní balíček</em>: the verb names what the user does, the noun names
 * what they get, and the noun is the one that has to survive being mailed to a third party.
 *
 * <p>This is deliberately not preservation. Preservation (uchování) rewrites the document so that
 * an ordinary validator can check it on its own; it needs every required piece of evidence and an
 * unbroken chain of time-stamps. Fixation makes no such demand: it takes whatever there is and
 * freezes it. What the time-stamp then proves is that <em>this material existed in this form at
 * this moment</em> — it does not hand the signature a proof of existence it never had, and it
 * cannot mend a broken chain. The intended reader is an expert who can obtain the missing pieces
 * from a CA's archive and judge the rest.
 *
 * <p>Wrapping rather than rewriting is what makes this honest. A best-effort attempt to preserve
 * in place would produce a document indistinguishable from a properly preserved one — same DSS
 * dictionary, same document time-stamp, "LTV enabled" in a reader — and anyone receiving it
 * without context would read the opposite of what happened. A container cannot be mistaken for a
 * preserved PAdES. It also leaves the original bytes exactly as they arrived, which is what an
 * expert needs, and it does not disturb an evidence record the document may already carry.
 *
 * <p>The value is in provenance, and no ASiC manifest records it: a CRL inside a container is just
 * bytes, what makes it evidence is knowing where it came from and when. So {@code soupis.xml} is a
 * first-class part of the contents, not a courtesy.
 */
public class FixationService {

    /**
     * Proč balíček ze souborů vzniká bez ověření — podle toho mluví okno i POPIS.txt. Dřív stálo
     * všude „Formát … aplikace nepodporuje“, i u nepodepsaného DOCX, ačkoli podepsaný DOCX
     * aplikace rozebere; tvrzení o formátu tak bylo nepravdivé (17. 10. 2026).
     */
    public enum Reason {
        /** Formát aplikace opravdu nezná. */
        UNSUPPORTED_FORMAT,
        /** Kancelářský dokument (DOCX, ODT…), v němž není žádný podpis. */
        OFFICE_UNSIGNED,
        /** Podepsaný kancelářský dokument, jehož podpis aplikace jen rozebrala, neověřila. */
        OFFICE_SIGNED
    }

    /**
     * What went into the container, for reporting back to the user.
     *
     * @param tsaCertNotAfter when the certificate of the container's time-stamp expires — the date
     *        the package has to be renewed by, and the one its file name carries. Null when it
     *        cannot be read, and then the name simply omits it rather than stating a guess.
     */
    public record FixationResult(DSSDocument container, int documents, int revocations,
                                 int certificates, Date tsaCertNotAfter) {}

    /**
     * The expiry of the time-stamping certificate in a freshly built container.
     *
     * <p>Read back out of the container rather than captured while stamping: DSS hands back the
     * finished ASiC and nothing else, so the token has to be found where it ended up. The newest
     * one is the one that matters — a renewed package holds several, and the deadline belongs to
     * the last.
     */
    private static Date timestampCertExpiry(DSSDocument container) {
        Date newest = null;
        try (java.io.InputStream is = container.openStream();
             java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(is)) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".tst")) continue;
                Date d = TimestampingService.extractTsaCertNotAfterFromToken(zip.readAllBytes());
                if (d != null && (newest == null || d.after(newest))) newest = d;
            }
        } catch (Exception ignored) {
            // A container we cannot read back is still a container; the name loses its date, no more.
        }
        return newest;
    }

    private static final SimpleDateFormat ISO = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
    static { ISO.setTimeZone(java.util.TimeZone.getTimeZone("UTC")); }

    private final AtomicReference<CommonCertificateVerifier> verifierRef;

    public FixationService(AtomicReference<CommonCertificateVerifier> verifierRef) {
        this.verifierRef = verifierRef;
    }

    /**
     * Builds the container and time-stamps it.
     *
     * @param source    the document to fix, taken byte for byte
     * @param secondary the second submitted file, when there was one — a detached signature or an
     *                  evidence record. It goes in as it came, and it has to: with a detached pair
     *                  the signature is the very thing being evidenced, and a container holding
     *                  only the document it signed would freeze everything except the point.
     * @param result    the validation that was just run over it — the source of the material and
     *                  of its provenance
     * @param profile   TSA to stamp the container with
     * @param algorithm digest algorithm for the time-stamp
     */
    public FixationResult fix(File source, File secondary, ValidationResult result,
                              TsaProfile profile, DigestAlgorithm algorithm) {
        CommonCertificateVerifier verifier = verifierRef.get();
        if (verifier == null) {
            throw new FixationException("Ověřovatel certifikátů není dosud inicializován");
        }
        if (profile == null) {
            throw new FixationException("Není vybrána žádná autorita časových razítek");
        }

        try {
            Date now = new Date();
            DiagnosticData dd = result.getDiagnosticData();
            if (dd == null) throw new FixationException("Ověření neposkytlo diagnostická data");

            List<DSSDocument> contents = new ArrayList<>();
            List<Item> inventory = new ArrayList<>();

            // 1. The document itself, untouched.
            byte[] originalBytes = Files.readAllBytes(source.toPath());
            String originalName = "dokument/" + safeFileName(source.getName());
            contents.add(new InMemoryDocument(originalBytes, originalName,
                    eu.europa.esig.dss.enumerations.MimeType.fromFileName(source.getName())));
            inventory.add(new Item(originalName, "dokument", sha256(originalBytes),
                    "předložený soubor, beze změny", null, null));

            // 1b. The second submitted file, under a name that says what it is. A detached
            //     signature and an evidence record are both material an expert must have in the
            //     form it arrived, and neither can be reconstructed from the validation reports.
            String secondaryName = null;
            if (secondary != null && secondary.isFile()) {
                byte[] secondaryBytes = Files.readAllBytes(secondary.toPath());
                boolean isSignature = result.isExternalSignature();
                secondaryName = (isSignature ? "podpis/" : "evidencni-zaznam/")
                        + safeFileName(secondary.getName());
                contents.add(new InMemoryDocument(secondaryBytes, secondaryName,
                        eu.europa.esig.dss.enumerations.MimeType.fromFileName(secondary.getName())));
                inventory.add(new Item(secondaryName,
                        isSignature ? "oddělený podpis" : "evidenční záznam",
                        sha256(secondaryBytes), "předložený soubor, beze změny", null, null));
            }

            // 2. Revocation material, one file each, named by what it covers.
            int revCount = 0;
            for (RevocationWrapper rev : dd.getAllRevocationData()) {
                byte[] bin = rev.getBinaries();
                if (bin == null || bin.length == 0) continue;
                boolean crl = rev.getRevocationType() == RevocationType.CRL;
                String issuer = rev.getSigningCertificate() != null
                        ? rev.getSigningCertificate().getReadableCertificateName() : "neznamy";
                // The extension names the format, not the encoding: a CRL is .crl, an OCSP
                // response .ocsp — the same extensions the rest of the application offers
                // when saving these bytes. Not .der: DER encodes CRLs, OCSP responses and
                // certificates alike, so it says nothing about what the file holds. What
                // goes in is a complete OCSPResponse, which is what a tool opening an
                // .ocsp file expects to find.
                String kind = crl ? "crl" : "ocsp";
                String name = String.format("revokace/%02d-%s-%s.%s",
                        ++revCount, kind, safeName(issuer), kind);
                contents.add(new InMemoryDocument(bin, name));
                inventory.add(new Item(name, crl ? "CRL" : "OCSP", sha256(bin),
                        provenance(rev, bin), rev.getThisUpdate(), rev.getNextUpdate()));
            }

            // 3. Certificates seen during validation.
            int certCount = 0;
            for (CertificateWrapper cert : dd.getUsedCertificates()) {
                byte[] bin = cert.getBinaries();
                if (bin == null || bin.length == 0) continue;
                String name = String.format("certifikaty/%02d-%s.cer",
                        ++certCount, safeName(cert.getReadableCertificateName()));
                contents.add(new InMemoryDocument(bin, name));
                inventory.add(new Item(name, "certifikát", sha256(bin),
                        cert.isTrusted() ? "kotva důvěry" : "z ověřování",
                        cert.getNotBefore(), cert.getNotAfter()));
            }

            // 4. The validation engine's own reports. Deliberately DSS's output rather than a
            //    verdict written by this application: they are standard, machine-readable and
            //    state findings, not conclusions about what the document is worth.
            addText(contents, inventory, "protokoly/dss-simple-report.xml",
                    result.getSimpleReportXml(), "protokol DSS");
            addText(contents, inventory, "protokoly/dss-detailed-report.xml",
                    result.getDetailedReportXml(), "protokol DSS");

            // 5. The inventory and the description, added last so they can list everything above.
            String popis = buildDescription(source.getName(), secondaryName == null ? null
                    : secondary.getName(), result.isExternalSignature(), now, revCount, certCount);
            contents.add(new InMemoryDocument(popis.getBytes(StandardCharsets.UTF_8),
                    "POPIS.txt", eu.europa.esig.dss.enumerations.MimeTypeEnum.TEXT));
            String soupis = buildInventory(source.getName(), now, inventory);
            contents.add(new InMemoryDocument(soupis.getBytes(StandardCharsets.UTF_8),
                    "soupis.xml", eu.europa.esig.dss.enumerations.MimeTypeEnum.XML));

            // 6. Wrap and time-stamp.
            ASiCWithCAdESService service = new ASiCWithCAdESService(verifier);
            service.setTspSource(TsaSigningKey.observing(buildTspSource(profile), profile));
            ASiCWithCAdESTimestampParameters params =
                    new ASiCWithCAdESTimestampParameters(algorithm);
            params.aSiC().setContainerType(ASiCContainerType.ASiC_E);
            DSSDocument container = service.timestamp(contents, params);

            return new FixationResult(container, contents.size(), revCount, certCount,
                    timestampCertExpiry(container));

        } catch (Exception e) {
            throw new FixationException("Zafixování selhalo: " + e.getMessage(), e);
        }
    }

    /**
     * Builds a forensic package out of files alone, with no validation behind it.
     *
     * <p>For the case where AnEP cannot read the format at all. Nothing was verified, so there is
     * no revocation material, no certificates and no validation reports to collect — and the
     * description has to say that rather than let the reader assume an empty package means a clean
     * one. The time-stamp is still worth having: it fixes these bytes at this moment, which is the
     * whole and only claim such a package makes.
     *
     * <p>Several files go in at once because that is how the material usually arrives — a contract,
     * its annexes, the covering e-mail — and stamping them separately would fix each one at its own
     * moment while saying nothing about them belonging together.
     *
     * @param subject the file the user submitted for validation, and {@code reason} why that failed.
     *        Both null when the subject is no longer among {@code files} — the user removed it from
     *        the list — and the description then says only that nothing was verified.
     */
    public FixationResult fixFiles(List<File> files, File subject, Reason reason,
                                   TsaProfile profile, DigestAlgorithm algorithm) {
        CommonCertificateVerifier verifier = verifierRef.get();
        if (verifier == null) {
            throw new FixationException("Ověřovatel certifikátů není dosud inicializován");
        }
        if (profile == null) {
            throw new FixationException("Není vybrána žádná autorita časových razítek");
        }
        if (files == null || files.isEmpty()) {
            throw new FixationException("Nebyl vybrán žádný soubor");
        }

        try {
            Date now = new Date();
            List<DSSDocument> contents = new ArrayList<>();
            List<Item> inventory = new ArrayList<>();
            List<String> displayNames = new ArrayList<>();
            java.util.Set<String> used = new java.util.HashSet<>();

            for (File f : files) {
                byte[] bytes = Files.readAllBytes(f.toPath());
                String entry = "dokumenty/" + uniqueEntryName(containerFileName(f.getName()), used);
                contents.add(new InMemoryDocument(bytes, entry,
                        eu.europa.esig.dss.enumerations.MimeType.fromFileName(f.getName())));
                // Where the file came from, verbatim. Entry names are sanitised and deduplicated,
                // so two files called smlouva.docx from different folders end up as smlouva.docx
                // and smlouva-2.docx — without the original path nobody could say which is which,
                // and a package whose items cannot be traced back is not evidence.
                inventory.add(new Item(entry, "soubor", sha256(bytes),
                        "předložený soubor, beze změny; původní umístění " + f.getAbsolutePath(),
                        null, null));
                displayNames.add(entry);
            }

            String popis = buildFilesDescription(displayNames, now,
                    whyNotVerified(subject, reason, files.size()));
            contents.add(new InMemoryDocument(popis.getBytes(StandardCharsets.UTF_8),
                    "POPIS.txt", eu.europa.esig.dss.enumerations.MimeTypeEnum.TEXT));
            // No single source document here — several files of equal standing went in.
            String soupis = buildInventory(null, now, inventory);
            contents.add(new InMemoryDocument(soupis.getBytes(StandardCharsets.UTF_8),
                    "soupis.xml", eu.europa.esig.dss.enumerations.MimeTypeEnum.XML));

            ASiCWithCAdESService service = new ASiCWithCAdESService(verifier);
            service.setTspSource(TsaSigningKey.observing(buildTspSource(profile), profile));
            ASiCWithCAdESTimestampParameters params =
                    new ASiCWithCAdESTimestampParameters(algorithm);
            params.aSiC().setContainerType(ASiCContainerType.ASiC_E);
            DSSDocument container = service.timestamp(contents, params);

            return new FixationResult(container, contents.size(), 0, 0,
                    timestampCertExpiry(container));

        } catch (Exception e) {
            throw new FixationException("Vytvoření forenzního balíčku selhalo: " + e.getMessage(), e);
        }
    }

    /**
     * The submitted file's own name, kept as it is. Diacritics and spaces stay: the name is part of
     * what was submitted, and "priloha-c.-1-ucet.png" is a worse record of "příloha č. 1 — účet.png"
     * than no record at all would be — it looks like the original rather than a transcription of it.
     *
     * <p>Only what would break the container or the file system is replaced: path separators, which
     * would silently create directories or escape the entry, control characters, and a leading or
     * trailing dot or space, which Windows cannot extract. Length is capped because ZIP entry names
     * plus the container path can outrun MAX_PATH on extraction.
     */
    private static String containerFileName(String raw) {
        String s = raw.replaceAll("[\\\\/\\u0000-\\u001f\\u007f]", "-")
                      .replaceAll("^[.\\s]+|[.\\s]+$", "");
        if (s.isBlank()) s = "soubor";
        if (s.length() <= 120) return s;
        int dot = s.lastIndexOf('.');
        if (dot > 0 && s.length() - dot <= 8) {
            return s.substring(0, 120 - (s.length() - dot)) + s.substring(dot);
        }
        return s.substring(0, 120);
    }

    /**
     * Keeps two files of the same name apart. They arrive from different folders often enough —
     * "smlouva.pdf" and its scan of the same name — and inside the container there is only one
     * namespace, so the second would silently overwrite the first.
     */
    private static String uniqueEntryName(String name, java.util.Set<String> used) {
        if (used.add(name)) return name;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; ; i++) {
            String candidate = base + "-" + i + ext;
            if (used.add(candidate)) return candidate;
        }
    }

    /**
     * The description for a package with no validation behind it. Its job is to be explicit about
     * the absence: a reader who opens this container and finds no revocation material must not be
     * left to conclude that none was needed.
     */
    /**
     * Odstavec „PROČ ZDE NENÍ NIC DALŠÍHO“ bez druhé půle o chybějících revokačních údajích.
     * Zalomený ručně jako zbytek popisu; jméno souboru je v uvozovkách na vlastním řádku, protože
     * jeho délku předem nikdo nezná.
     */
    static String whyNotVerified(File subject, Reason reason, int fileCount) {
        String others = fileCount > 1
                ? "\nO ostatních souborech v balíčku aplikace nezjišťovala nic." : "";
        if (subject == null || reason == null) {
            return "Aplikace soubory neověřovala, takže žádné ověření neproběhlo.";
        }
        String name = "\n  \"" + subject.getName() + "\"\n";
        return switch (reason) {
            case OFFICE_UNSIGNED -> "V předloženém souboru" + name
                    + "aplikace nenašla žádný elektronický podpis, takže nebylo co ověřovat."
                    + others;
            case OFFICE_SIGNED -> "Předložený soubor" + name
                    + "nese elektronický podpis ve formátu, jehož podpisy aplikace neověřuje\n"
                    + "(podpis jen rozebrala), takže žádné ověření neproběhlo." + others;
            default -> "Formát předloženého souboru" + name
                    + "aplikace nepodporuje, takže žádné ověření neproběhlo." + others;
        };
    }

    private static String buildFilesDescription(List<String> fileNames, Date now, String why) {
        StringBuilder seznam = new StringBuilder();
        for (String n : fileNames) seznam.append("  ").append(n).append('\n');
        return """
                FORENZNÍ BALÍČEK
                ================

                Tento forenzní balíček vytvořila aplikace AnEP dne %s (UTC).

                CO TO JE
                Balíček je kontejner ASiC-E a obsahuje %s v nezměněné podobě,
                přesně tak, jak byly předloženy:

                %s
                Jména souborů jsou zachována tak, jak byla předložena. Původní umístění každého
                z nich najdete v soupisu.

                Celý balíček je opatřen časovým razítkem.

                PROČ ZDE NENÍ NIC DALŠÍHO
                %s
                V balíčku proto nejsou žádné revokační informace, certifikáty ani
                protokoly ověřovací knihovny — ne proto, že by nebyly potřeba, ale proto, že
                nebylo z čeho je získat. Balíček neříká, že soubory jsou v pořádku, ani že
                obsahují nebo neobsahují elektronický podpis.

                CO TO DOKLÁDÁ
                Časové razítko dokládá, že tyto soubory existovaly v této podobě v okamžiku,
                kdy bylo razítko vydáno. Nic víc.

                CO TO NEDOKLÁDÁ
                Nedokládá pravost ani původ souborů a nedokládá, kdo je vytvořil. Obsahuje-li
                některý z nich elektronický podpis, nedokládá jeho platnost — ta se zde vůbec
                neposuzovala.

                K ČEMU TO SLOUŽÍ
                Jako podklad pro pozdější zkoumání. Zafixuje stav souborů k dnešnímu dni, aby
                bylo možné později doložit, že se od té chvíle nezměnily.

                SOUPIS
                Soubor soupis.xml uvádí u každé položky její otisk SHA-256 a původ. Bez toho
                jsou vložené soubory jen bajty; teprve původ z nich dělá použitelný podklad.

                ČÍM JE OBSAH KRYT
                Razítko nekryje položky přímo, nýbrž přes manifest. V META-INF leží
                ASiCManifest001.xml s otiskem každé položky balíčku a razítko
                (META-INF/timestamp001.tst) je vydáno nad tímto manifestem. Změna kterékoli
                položky proto poruší její otisk v manifestu, a změna manifestu poruší razítko.
                Kdo balíček zkoumá, ověří obojí: otisky v manifestu proti souborům a razítko
                proti manifestu.
                Přidá-li se k balíčku později další razítko (obnova, než certifikátu toho
                dosavadního skončí platnost), vznikne vedle toho ASiCArchiveManifest.xml. Ten
                kryje totéž co první manifest, a navíc předchozí razítko i předchozí manifest,
                takže se řetěz prodlouží, aniž by starší doklad vypadl z rozsahu.
                """.formatted(ISO.format(now),
                        pocet(fileNames.size(), "soubor", "soubory", "souborů"),
                        seznam.toString(), why);
    }

    private record Item(String name, String kind, String digest, String provenance,
                        Date from, Date to) {}

    private static void addText(List<DSSDocument> contents, List<Item> inventory,
                                String name, String text, String kind) {
        if (text == null || text.isBlank()) return;
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        contents.add(new InMemoryDocument(bytes, name,
                eu.europa.esig.dss.enumerations.MimeTypeEnum.XML));
        inventory.add(new Item(name, kind, sha256(bytes), "vytvořeno při ověření", null, null));
    }

    /**
     * Where a revocation token came from — the part a manifest never records, and the reason this
     * container is evidence rather than a pile of bytes.
     *
     * <p>A CRL handed over by the user's own store carries no source address, and calling that
     * "obtained on-line" would be simply untrue: it says the file came from this machine, which is
     * something an expert has every reason to know.
     */
    private static String provenance(RevocationWrapper rev, byte[] binaries) {
        if (rev.isInternalRevocationOrigin()) return "bylo vloženo v dokumentu";
        String addr = rev.getSourceAddress();
        if (addr != null && !addr.isBlank()) return "staženo z " + addr;
        String stored = fromCustomStore(binaries);
        if (stored != null) return "z vlastního úložiště CRL aplikace, soubor " + stored;
        return "získáno on-line při ověření";
    }

    /** The store file whose bytes match, or null. */
    private static String fromCustomStore(byte[] binaries) {
        try {
            for (CustomCrlStore.Entry e : CustomCrlStore.listCrls()) {
                byte[] stored = Files.readAllBytes(e.file().toPath());
                if (java.util.Arrays.equals(stored, binaries)) return e.file().getName();
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** Czech needs three forms; "1 revokačních informací" reads like a machine wrote it. */
    private static String pocet(int n, String jedna, String dveAzCtyri, String vice) {
        if (n == 1) return n + " " + jedna;
        if (n >= 2 && n <= 4) return n + " " + dveAzCtyri;
        return n + " " + vice;
    }

    /**
     * The description. States what the container is, what is inside and — the part that matters —
     * what fixation does not establish. It records findings and their origin, never a verdict on
     * the signature: a neatly formatted opinion sealed inside a time-stamped container is exactly
     * the thing that would later be waved about as if this application had certified something.
     */
    private static String buildDescription(String fileName, String secondaryFileName,
                                           boolean secondaryIsSignature, Date now,
                                           int revCount, int certCount) {
        // Assembled without the text block's indentation: the block strips it from its own lines
        // only, so anything substituted in keeps whatever spaces it carries.
        String druhy = secondaryFileName == null ? ""
                : "\nDále obsahuje soubor \"" + secondaryFileName + "\", rovněž beze změny. Je to "
                  + (secondaryIsSignature
                        ? "oddělený podpis\nk tomuto dokumentu, předložený spolu s ním."
                        : "evidenční záznam\npředložený spolu s dokumentem.");
        return """
                FORENZNÍ BALÍČEK
                ================

                Tento forenzní balíček vytvořila aplikace AnEP dne %s (UTC).

                CO TO JE
                Balíček je kontejner ASiC-E. Obsahuje dokument
                "%s"
                v nezměněné podobě, přesně tak, jak byl předložen, a k němu veškerý materiál,
                který se při ověření podařilo získat: %s (CRL nebo OCSP)
                a %s, spolu s protokoly ověřovací knihovny DSS.
                Celý balíček je opatřen časovým razítkem.%s

                CO TO DOKLÁDÁ
                Časové razítko dokládá, že tento materiál existoval v této podobě v okamžiku,
                kdy bylo razítko vydáno. Nic víc.

                CO TO NEDOKLÁDÁ
                Nedokládá, že podpis v dokumentu je platný — o platnosti se zde nerozhoduje.
                Nedává podpisu důkaz existence k dřívějšímu okamžiku, než kdy vzniklo toto
                razítko, a nenahrazuje tedy chybějící ani neplatná časová razítka v dokumentu
                samotném. Neopravuje přerušený řetězec razítek.

                K ČEMU TO SLOUŽÍ
                Jako podklad pro pozdější zkoumání. Kdo jej bude posuzovat, si může chybějící
                údaje obstarat z archivů certifikačních autorit a posoudit i materiál, který
                běžný ověřovací nástroj odmítne použít — například revokační informace vydané
                až po expiraci certifikátu. Právě proto se sem takový materiál ukládá tak, jak
                byl získán, a nic se z něj nevyřazuje.

                SOUPIS
                Soubor soupis.xml uvádí u každé položky její otisk SHA-256 a původ, tedy odkud
                a kdy byla získána. Bez toho jsou vložené soubory jen bajty; teprve původ z nich
                dělá použitelný podklad.

                ČÍM JE OBSAH KRYT
                Razítko nekryje položky přímo, nýbrž přes manifest. V META-INF leží
                ASiCManifest001.xml s otiskem každé položky balíčku a razítko
                (META-INF/timestamp001.tst) je vydáno nad tímto manifestem. Změna kterékoli
                položky proto poruší její otisk v manifestu, a změna manifestu poruší razítko.
                Kdo balíček zkoumá, ověří obojí: otisky v manifestu proti souborům a razítko
                proti manifestu.
                Přidá-li se k balíčku později další razítko (obnova, než certifikátu toho
                dosavadního skončí platnost), vznikne vedle toho ASiCArchiveManifest.xml. Ten
                kryje totéž co první manifest, a navíc předchozí razítko i předchozí manifest,
                takže se řetěz prodlouží, aniž by starší doklad vypadl z rozsahu. Samotné
                archivní atributy uvnitř podpisů CAdES či XAdES by k tomu nestačily — datové
                objekty odkazované manifestem ASiC do jejich rozsahu nespadají (ETSI EN 319
                162-1, dříve TS 102 918).
                """.formatted(ISO.format(now), fileName,
                        pocet(revCount, "revokační informaci", "revokační informace",
                                "revokačních informací"),
                        pocet(certCount, "certifikát", "certifikáty", "certifikátů"),
                        druhy);
    }

    private static String buildInventory(String fileName, Date now, List<Item> items) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<forenzniBalicek vytvoreno=\"").append(ISO.format(now))
          .append("\" nastroj=\"AnEP\">\n");
        // Null when the package has no single source document but several files of equal standing;
        // naming one of them as the source would be a claim about their relationship that nobody
        // made.
        if (fileName != null) {
            sb.append("  <zdrojovyDokument>").append(escape(fileName)).append("</zdrojovyDokument>\n");
        }
        sb.append("  <polozky>\n");
        for (Item it : items) {
            sb.append("    <polozka nazev=\"").append(escape(it.name())).append("\"")
              .append(" druh=\"").append(escape(it.kind())).append("\">\n");
            sb.append("      <sha256>").append(it.digest()).append("</sha256>\n");
            sb.append("      <puvod>").append(escape(it.provenance())).append("</puvod>\n");
            if (it.from() != null) {
                sb.append("      <od>").append(ISO.format(it.from())).append("</od>\n");
            }
            if (it.to() != null) {
                sb.append("      <do>").append(ISO.format(it.to())).append("</do>\n");
            }
            sb.append("    </polozka>\n");
        }
        sb.append("  </polozky>\n");
        sb.append("</forenzniBalicek>\n");
        return sb.toString();
    }

    private static OnlineTSPSource buildTspSource(TsaProfile profile) {
        var loader = TrustConfig.createTimestampDataLoader();
        if (profile.hasAuth()) {
            try {
                URI uri = new URI(profile.getUrl());
                loader.addAuthentication(uri.getHost(), uri.getPort(), null,
                        profile.getUsername(), profile.getPassword().toCharArray());
                loader.setPreemptiveAuthentication(true);
            } catch (Exception ignored) {}
        }
        OnlineTSPSource tspSource = new OnlineTSPSource(profile.getUrl());
        tspSource.setDataLoader(loader);
        return tspSource;
    }

    private static String sha256(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Like {@link #safeName} but keeps the extension. Truncating a long name blindly threw away
     * the ".pdf" and left whoever unpacked the container guessing what the file was.
     */
    private static String safeFileName(String raw) {
        int dot = raw.lastIndexOf('.');
        if (dot <= 0 || dot == raw.length() - 1 || raw.length() - dot > 6) return safeName(raw);
        String ext = safeName(raw.substring(dot + 1));
        String base = safeName(raw.substring(0, dot));
        int room = 60 - ext.length() - 1;
        if (room > 0 && base.length() > room) base = base.substring(0, room);
        return base + "." + ext;
    }

    /** Keeps entry names portable: ASCII, no separators, no spaces. */
    private static String safeName(String raw) {
        String s = java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[^A-Za-z0-9._-]+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-|-$", "");
        if (s.isBlank()) s = "polozka";
        return s.length() > 60 ? s.substring(0, 60) : s;
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    public static final class FixationException extends RuntimeException {
        public FixationException(String msg) { super(msg); }
        public FixationException(String msg, Throwable cause) { super(msg, cause); }
    }
}
