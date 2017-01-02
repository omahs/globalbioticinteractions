package org.eol.globi.taxon;

import org.apache.commons.lang3.StringUtils;
import org.apache.lucene.queryParser.QueryParser;
import org.eol.globi.data.NodeFactoryException;
import org.eol.globi.data.TaxonIndex;
import org.eol.globi.domain.PropertyAndValueDictionary;
import org.eol.globi.domain.Taxon;
import org.eol.globi.domain.TaxonImpl;
import org.eol.globi.domain.TaxonNode;
import org.eol.globi.service.PropertyEnricher;
import org.eol.globi.service.PropertyEnricherException;
import org.eol.globi.service.QueryUtil;
import org.eol.globi.service.TaxonUtil;
import org.eol.globi.util.NodeUtil;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.index.Index;
import org.neo4j.graphdb.index.IndexHits;

public class TaxonIndexNeo4j implements TaxonIndex {
    private final GraphDatabaseService graphDbService;
    private final Index<Node> taxons;
    private CorrectionService corrector;
    private PropertyEnricher enricher;
    private boolean indexResolvedOnly;

    public TaxonIndexNeo4j(PropertyEnricher enricher, CorrectionService correctionService, GraphDatabaseService graphDbService) {
        this.enricher = enricher;
        this.corrector = correctionService;
        this.graphDbService = graphDbService;
        this.taxons = graphDbService.index().forNodes("taxons");
    }

    @Override
    public TaxonNode getOrCreateTaxon(Taxon taxon) throws NodeFactoryException {
        if (StringUtils.isBlank(taxon.getExternalId()) && StringUtils.length(taxon.getName()) < 3) {
            throw new NodeFactoryException("taxon name [" + taxon.getName() + "] too short and no externalId is provided");
        }
        TaxonNode taxonNode = findTaxon(taxon);
        return taxonNode == null ? createTaxon(taxon) : taxonNode;
    }

    @Override
    public TaxonNode findTaxonById(String externalId) {
        return findTaxonByKey(PropertyAndValueDictionary.EXTERNAL_ID, externalId);
    }

    @Override
    public TaxonNode findTaxonByName(String name) throws NodeFactoryException {
        return findTaxonByKey(PropertyAndValueDictionary.NAME, name);
    }

    private TaxonNode findTaxonByKey(String key, String value) {
        TaxonNode firstMatchingTaxon = null;
        if (StringUtils.isNotBlank(value)) {
            String query = key + ":\"" + QueryParser.escape(value) + "\"";
            IndexHits<Node> matchingTaxa = taxons.query(query);
            Node matchingTaxon;
            if (matchingTaxa.hasNext()) {
                matchingTaxon = matchingTaxa.next();
                if (matchingTaxon != null) {
                    firstMatchingTaxon = new TaxonNode(matchingTaxon);
                }
            }
            matchingTaxa.close();
        }
        return firstMatchingTaxon;
    }

    private TaxonNode createTaxon(final Taxon origTaxon) throws NodeFactoryException {
        Taxon taxon = TaxonUtil.copy(origTaxon);
        taxon.setName(corrector.correct(origTaxon.getName()));

        TaxonNode indexedTaxon = findTaxon(taxon);
        while (indexedTaxon == null) {
            try {
                taxon = TaxonUtil.enrich(enricher, taxon);
            } catch (PropertyEnricherException e) {
                throw new NodeFactoryException("failed to enrich taxon with name [" + taxon.getName() + "]", e);
            }
            indexedTaxon = findTaxon(taxon);
            if (indexedTaxon == null) {
                if (TaxonUtil.isResolved(taxon)) {
                    indexedTaxon = createAndIndexTaxon(taxon);
                } else {
                    String truncatedName = NodeUtil.truncateTaxonName(taxon.getName());
                    if (StringUtils.equals(truncatedName, taxon.getName())) {
                        if (indexResolvedOnly) {
                            break;
                        } else {
                            indexedTaxon = addNoMatchTaxon(origTaxon);
                        }
                    } else {
                        taxon = new TaxonImpl();
                        taxon.setName(truncatedName);
                        indexedTaxon = findTaxonByName(taxon.getName());
                    }
                }
            }
        }
        if (indexedTaxon != null) {
            indexOriginalNameForTaxon(origTaxon.getName(), taxon, indexedTaxon);
            indexOriginalExternalIdForTaxon(origTaxon.getExternalId(), taxon, indexedTaxon);
        }
        return indexedTaxon;
    }

    private TaxonNode findTaxon(Taxon taxon) throws NodeFactoryException {
        String name = taxon.getName();
        String externalId = taxon.getExternalId();
        TaxonNode taxon1 = null;
        if (StringUtils.isBlank(externalId)) {
            if (StringUtils.length(name) > 1) {
                taxon1 = findTaxonByName(name);
            }
        } else {
            taxon1 = findTaxonById(externalId);
        }
        return taxon1;
    }

    private void indexOriginalNameForTaxon(String name, Taxon taxon, TaxonNode taxonNode) throws NodeFactoryException {
        if (!StringUtils.equals(taxon.getName(), name)) {
            if (StringUtils.isNotBlank(name) && !StringUtils.equals(PropertyAndValueDictionary.NO_MATCH, name)) {
                if (findTaxonByName(name) == null) {
                    indexTaxonByProperty(taxonNode, PropertyAndValueDictionary.NAME, name);
                }
            }
        }
    }

    private void indexOriginalExternalIdForTaxon(String externalId, Taxon taxon, TaxonNode taxonNode) throws NodeFactoryException {
        if (!StringUtils.equals(taxon.getExternalId(), externalId)) {
            if (StringUtils.isNotBlank(externalId) && !StringUtils.equals(PropertyAndValueDictionary.NO_MATCH, externalId)) {
                if (findTaxonById(externalId) == null) {
                    indexTaxonByProperty(taxonNode, PropertyAndValueDictionary.EXTERNAL_ID, externalId);
                }
            }
        }
    }

    private void indexTaxonByProperty(TaxonNode taxonNode, String propertyName, String propertyValue) {
        Transaction tx = null;
        try {
            tx = taxonNode.getUnderlyingNode().getGraphDatabase().beginTx();
            taxons.add(taxonNode.getUnderlyingNode(), propertyName, propertyValue);
            tx.success();
        } finally {
            if (tx != null) {
                tx.finish();
            }
        }
    }

    private TaxonNode addNoMatchTaxon(Taxon origTaxon) throws NodeFactoryException {
        Taxon noMatchTaxon = TaxonUtil.copy(origTaxon);
        noMatchTaxon.setName(StringUtils.isBlank(origTaxon.getName()) ? PropertyAndValueDictionary.NO_MATCH : origTaxon.getName());
        noMatchTaxon.setExternalId(StringUtils.isBlank(origTaxon.getExternalId()) ? PropertyAndValueDictionary.NO_MATCH : origTaxon.getExternalId());
        return createAndIndexTaxon(noMatchTaxon);
    }

    private TaxonNode createAndIndexTaxon(Taxon taxon) throws NodeFactoryException {
        TaxonNode taxonNode = null;
        Transaction transaction = graphDbService.beginTx();
        try {
            taxonNode = new TaxonNode(graphDbService.createNode(), taxon.getName());
            addToIndeces((TaxonNode) TaxonUtil.copy(taxon, taxonNode), taxon.getName());
            transaction.success();
        } finally {
            transaction.finish();
        }
        return taxonNode;
    }

    public TaxonNode createTaxonNoTransaction(String name, String externalId, String path) {
        Node node = graphDbService.createNode();
        TaxonNode taxon = new TaxonNode(node, corrector.correct(name));
        if (null != externalId) {
            taxon.setExternalId(externalId);
        }
        if (null != path) {
            taxon.setPath(path);
        }
        addToIndeces(taxon, taxon.getName());
        return taxon;
    }

    public void setEnricher(PropertyEnricher enricher) {
        this.enricher = enricher;
    }

    public void setCorrector(CorrectionService corrector) {
        this.corrector = corrector;
    }

    public IndexHits<Node> findCloseMatchesForTaxonName(String taxonName) {
        return QueryUtil.query(taxonName, PropertyAndValueDictionary.NAME, taxons);
    }

    private void addToIndeces(TaxonNode taxon, String indexedName) {
        if (StringUtils.isNotBlank(indexedName)) {
            if (!StringUtils.equals(PropertyAndValueDictionary.NO_MATCH, indexedName)
                    && !StringUtils.equals(PropertyAndValueDictionary.NO_NAME, indexedName)) {
                taxons.add(taxon.getUnderlyingNode(), PropertyAndValueDictionary.NAME, indexedName);
            }

            String externalId = taxon.getExternalId();
            if (!StringUtils.equals(PropertyAndValueDictionary.NO_MATCH, externalId)) {
                taxons.add(taxon.getUnderlyingNode(), PropertyAndValueDictionary.EXTERNAL_ID, externalId);
            }
        }
    }


    public void setIndexResolvedTaxaOnly(boolean indexResolvedOnly) {
        this.indexResolvedOnly = indexResolvedOnly;
    }

    public boolean isIndexResolvedOnly() {
        return indexResolvedOnly;
    }
}