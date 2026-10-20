package cz.digitalnikontinuita.anep.core;

import cz.digitalnikontinuita.anep.core.OcspFirstCrlFallbackRevocationDataLoadingStrategy.SkippedRevocation;
import eu.europa.esig.dss.spi.validation.RevocationDataLoadingStrategy;
import eu.europa.esig.dss.spi.validation.RevocationDataLoadingStrategyFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Factory for {@link OcspFirstCrlFallbackRevocationDataLoadingStrategy}.
 * Each factory instance accumulates {@link SkippedRevocation} records for every
 * post-expiry OCSP that was fetched but not used because a CRL was preferred.
 * Call {@link #getSkippedRevocations()} after validation to retrieve them.
 */
public class OcspFirstCrlFallbackRevocationDataLoadingStrategyFactory
        implements RevocationDataLoadingStrategyFactory {

    private final List<SkippedRevocation> skipped = new ArrayList<>();

    @Override
    public RevocationDataLoadingStrategy create() {
        return new OcspFirstCrlFallbackRevocationDataLoadingStrategy(skipped);
    }

    public List<SkippedRevocation> getSkippedRevocations() {
        return Collections.unmodifiableList(skipped);
    }
}
