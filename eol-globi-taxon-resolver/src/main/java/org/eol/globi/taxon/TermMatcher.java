package org.eol.globi.taxon;

import org.eol.globi.service.PropertyEnricherException;

import java.util.List;

public interface TermMatcher {
    void findTermsForNames(List<String> names, TermMatchListener termMatchListener, List<GlobalNamesSources> sources) throws PropertyEnricherException;
}