package cz.digitalnikontinuita.anep.core;

/**
 * Doklad totožnosti zapsaný v certifikátu — tedy v atributu serialNumber předmětu (OID 2.5.4.5).
 *
 * <p>ETSI EN 319 412-1 čl. 5.1.3 dává tomu atributu <b>registrační schéma</b>: tři znaky
 * pojmenují druh odkazu na totožnost, za nimi dvoupísmenný kód země a pomlčka. Pro české doklady
 * to jsou {@code IDCCZ-} (občanský průkaz) a {@code PASCZ-} (cestovní pas).
 *
 * <p><b>Jen ta dvě schémata.</b> Norma zná i další — {@code PNO} pro rodné číslo, {@code TIN}
 * a {@code TAX} pro daňové identifikátory, {@code IDC} a {@code PAS} jiných zemí. Ty identifikátor
 * také pojmenovávají, ale ne takový, který by tahle aplikace uměla popsat správně, takže se hlásí
 * jako nepřítomný doklad, ne jako doklad neznámého druhu. Hádat za neznámou předponu by znamenalo
 * vydávat dohad za zjištění.
 *
 * <p>Třída sedí v {@code core}, protože se na totéž ptají dvě místa v různých balíčcích:
 * {@code DnUtil} kvůli výpisu podepsané osoby a bloku o právních účincích, a
 * {@link ContainerInspector} kvůli prohlížeči certifikátů. Dvě kopie téhož pravidla by se dřív
 * nebo později rozešly.
 */
public final class IdentityDocument {

    private IdentityDocument() { }

    private static final String ID_CARD_PREFIX = "IDCCZ-";
    private static final String PASSPORT_PREFIX = "PASCZ-";

    /**
     * Rozpoznaný doklad.
     *
     * <p>Tři podoby názvu, protože každé místo potřebuje jinou a skloňovat se v kódu nedá:
     * {@code kind} je první pád pro popisek ve výpisu („občanský průkaz“), {@code phrase} celá
     * vazba do souvislého textu („číslo občanského průkazu“) a {@code shortLabel} zkratka do
     * úzkého sloupce („Číslo OP“).
     */
    public record Document(String kind, String phrase, String shortLabel, String number) { }

    /**
     * Doklad zapsaný v hodnotě atributu serialNumber, nebo {@code null}, není-li to žádné
     * ze dvou českých schémat.
     *
     * @param subjectSerialNumber syrová hodnota OID 2.5.4.5, i s předponou schématu
     */
    public static Document of(String subjectSerialNumber) {
        if (subjectSerialNumber == null || subjectSerialNumber.isBlank()) return null;
        String raw = subjectSerialNumber.trim();
        if (raw.startsWith(ID_CARD_PREFIX)) {
            return document("občanský průkaz", "číslo občanského průkazu", "Číslo OP",
                    raw.substring(ID_CARD_PREFIX.length()));
        }
        if (raw.startsWith(PASSPORT_PREFIX)) {
            return document("cestovní pas", "číslo cestovního pasu", "Číslo pasu",
                    raw.substring(PASSPORT_PREFIX.length()));
        }
        return null;
    }

    /**
     * Samé schéma bez čísla doklad není.
     *
     * <p>{@code IDCCZ-} s prázdnou hodnotou by jinak prolezlo až do bloku o právních
     * účincích, kde rozhoduje právě přítomnost dokladu: věta by přeskočila z „může mít účinky“
     * na „má účinky“ na základě údaje, který nic neříká. Vada byla i před přesunem pravidla
     * sem, jen se na ni nikdo neptal; našel ji vlastní test.
     */
    private static Document document(String kind, String phrase, String shortLabel,
                                     String number) {
        return number == null || number.isBlank() ? null
                : new Document(kind, phrase, shortLabel, number.trim());
    }
}
