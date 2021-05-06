package org.eol.globi.util;

import org.eol.globi.data.DatasetImporterForTSV;
import org.eol.globi.data.StudyImporterException;
import org.eol.globi.process.InteractionListener;
import org.eol.globi.service.TaxonUtil;
import org.hamcrest.core.Is;
import org.junit.Test;

import java.util.Map;
import java.util.TreeMap;

import static org.hamcrest.MatcherAssert.assertThat;

public class InteractionListenerIndexingTest {

    @Test
    public void indexOnSourceOccurrenceIdTargetOccurrenceIdPairs() throws StudyImporterException {
        TreeMap<String, Map<String, String>> interactionsWithUnresolvedOccurrenceIds = new TreeMap<>();
        InteractionListener listener = new InteractionListenerIndexing(interactionsWithUnresolvedOccurrenceIds);


        listener.on(new TreeMap<String, String>() {{
            put(DatasetImporterForTSV.TARGET_OCCURRENCE_ID, "target123");
            put(DatasetImporterForTSV.SOURCE_OCCURRENCE_ID, "source123");
            put(TaxonUtil.SOURCE_TAXON_NAME, "sourceName123");
        }});

        assertThat(interactionsWithUnresolvedOccurrenceIds.size(), Is.is(1));

        Map<String, String> props = interactionsWithUnresolvedOccurrenceIds.get("source123");

        assertThat(props.get(DatasetImporterForTSV.TARGET_OCCURRENCE_ID), Is.is("target123"));
        assertThat(props.get(DatasetImporterForTSV.SOURCE_OCCURRENCE_ID), Is.is("source123"));
        assertThat(props.get(TaxonUtil.SOURCE_TAXON_NAME), Is.is("sourceName123"));
    }

    @Test
    public void indexOnTargetOccurrenceIdOnly() throws StudyImporterException {
        TreeMap<String, Map<String, String>> interactionsWithUnresolvedOccurrenceIds = new TreeMap<>();
        InteractionListener listener = new InteractionListenerIndexing(interactionsWithUnresolvedOccurrenceIds);


        listener.on(new TreeMap<String, String>() {{
            put(DatasetImporterForTSV.TARGET_OCCURRENCE_ID, "target123");
            put(TaxonUtil.SOURCE_TAXON_NAME, "sourceName123");
        }});

        assertThat(interactionsWithUnresolvedOccurrenceIds.size(), Is.is(0));
    }

    @Test
    public void indexOnSourceOccurrenceIdOnly() throws StudyImporterException {
        TreeMap<String, Map<String, String>> interactionsWithUnresolvedOccurrenceIds = new TreeMap<>();
        InteractionListener listener = new InteractionListenerIndexing(interactionsWithUnresolvedOccurrenceIds);


        listener.on(new TreeMap<String, String>() {{
            put(DatasetImporterForTSV.SOURCE_OCCURRENCE_ID, "target123");
            put(TaxonUtil.SOURCE_TAXON_NAME, "sourceName123");
        }});

        assertThat(interactionsWithUnresolvedOccurrenceIds.size(), Is.is(0));
    }

}