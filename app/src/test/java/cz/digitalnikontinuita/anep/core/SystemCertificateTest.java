package cz.digitalnikontinuita.anep.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Znění upozornění v certifikační politice, podle kterých se pozná systémový certifikát
 * (zákon č. 227/2000 Sb.). Texty jsou doslovně z certifikátů ve sbírce uživatele.
 */
class SystemCertificateTest {

    @Test
    void postSignumFirstGeneration() {
        assertEquals(SystemCertificate.Kind.QUALIFIED_SYSTEM, SystemCertificate.classify(
                "Tento certifikat byl vydan jako kvalifikovany systemovy certifikat ve smyslu"
                + " zakona 227/2000 Sb. a navazujicich predpisu."));
    }

    @Test
    void postSignumSecondGeneration() {
        assertEquals(SystemCertificate.Kind.QUALIFIED_SYSTEM, SystemCertificate.classify(
                "Tento kvalifikovany systemovy certifikat byl vydan podle zakona 227/2000Sb."
                + " a navaznych predpisu/This qualified system certificate was issued according"
                + " to Law No 227/2000Coll. and related regulations"));
    }

    @Test
    void ica() {
        assertEquals(SystemCertificate.Kind.QUALIFIED_SYSTEM, SystemCertificate.classify(
                "Tento kvalifikovany systemovy certifikat je vydan podle zakona c. 227/2000 Sb."
                + " v platnem zneni/This is qualified system certificate according to Czech Act"
                + " No. 227/2000 Coll."));
    }

    @Test
    void diacriticsDoNotMatter() {
        assertEquals(SystemCertificate.Kind.QUALIFIED_SYSTEM,
                SystemCertificate.classify("Kvalifikovaný systémový certifikát"));
    }

    @Test
    void nonQualifiedSystemCertificate() {
        assertEquals(SystemCertificate.Kind.SYSTEM,
                SystemCertificate.classify("Tento systemovy certifikat byl vydan ..."));
    }

    @Test
    void personalQualifiedCertificateIsNotSystem() {
        assertEquals(SystemCertificate.Kind.NONE, SystemCertificate.classify(
                "Tento kvalifikovany certifikat je vydan podle zakona Ceske republiky"
                + " c. 227/2000 Sb. v platnem zneni/This is qualified certificate according to"
                + " Czech Act No. 227/2000 Coll."));
    }

    @Test
    void eidasSealIsNotSystem() {
        assertEquals(SystemCertificate.Kind.NONE, SystemCertificate.classify(
                "Tento kvalifikovany certifikat pro elektronickou pecet byl vydan v souladu"
                + " s narizenim EU c. 910/2014."));
    }
}
