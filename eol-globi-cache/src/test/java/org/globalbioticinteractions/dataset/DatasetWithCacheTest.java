package org.globalbioticinteractions.dataset;

import org.eol.globi.service.DatasetImpl;
import org.globalbioticinteractions.cache.Cache;
import org.globalbioticinteractions.cache.CachedURI;
import org.hamcrest.core.Is;
import org.junit.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Date;

import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.when;

public class DatasetWithCacheTest {

    @Test
    public void citationWithLastAccessed() {
        Date lastAccessed = new Date(0);
        DatasetWithCache dataset = datasetLastAccessedAt(lastAccessed);
        assertThat(dataset.getCitation(), Is.is("Accessed on 01 Jan 1970 via <some:bla>."));
    }

    @Test
    public void citationWithoutLastAccessed() {
        DatasetWithCache dataset = datasetLastAccessedAt(null);
        assertThat(dataset.getCitation(), Is.is("Accessed via <some:bla>."));
    }

    private DatasetWithCache datasetLastAccessedAt(Date lastAccessed) {
        Cache cache = Mockito.mock(Cache.class);
        CachedURI cacheURI = Mockito.mock(CachedURI.class);
        when(cacheURI.getAccessedAt()).thenReturn(lastAccessed);
        when(cache.asMeta(any(URI.class))).thenReturn(cacheURI);
        DatasetImpl datasetUncached = new DatasetImpl("some/namespace", URI.create("some:bla"));
        return new DatasetWithCache(datasetUncached, cache);
    }

}