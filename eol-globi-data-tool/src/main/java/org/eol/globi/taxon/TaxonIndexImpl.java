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

public class TaxonIndexImpl implements TaxonIndex {
    private final GraphDatabaseService graphDbService;
    private final Index<Node> taxons;
    private CorrectionService corrector;
    private PropertyEnricher enricher;

    public TaxonIndexImpl(PropertyEnricher enricher, CorrectionService correctionService, GraphDatabaseService graphDbService) {
        this.enricher = enricher;
        this.corrector = correctionService;
        this.graphDbService = graphDbService;
        this.taxons = graphDbService.index().forNodes("taxons");
    }

    @Override
    public TaxonNode getOrCreateTaxon(String name) throws NodeFactoryException {
        return getOrCreateTaxon(new TaxonImpl(name));
    }

    @Override
    public TaxonNode getOrCreateTaxon(String name, String externalId) throws NodeFactoryException {
        return getOrCreateTaxon(new TaxonImpl(name, externalId));
    }

    @Override
    public TaxonNode getOrCreateTaxon(String name, String externalId, String path) throws NodeFactoryException {
        TaxonImpl taxon1 = new TaxonImpl(name, externalId);
        taxon1.setPath(path);
        return getOrCreateTaxon(taxon1);
    }

    @Override
    public TaxonNode getOrCreateTaxon(Taxon taxon) throws NodeFactoryException {
        if (StringUtils.isBlank(taxon.getExternalId()) && StringUtils.length(taxon.getName()) < 3) {
            throw new NodeFactoryException("taxon name [" + taxon.getName() + "] too short and no externalId is provided");
        }
        TaxonNode taxonNode = findTaxon(taxon.getName(), taxon.getExternalId());
        return taxonNode != null ? taxonNode : createTaxon(taxon);
    }

    private TaxonNode findTaxon(String name, String externalId) throws NodeFactoryException {
        TaxonNode taxon = null;
        if (StringUtils.isBlank(externalId)) {
            if (StringUtils.length(name) > 1) {
                taxon = findTaxonByName(name);
            }
        } else {
            taxon = findTaxonById(externalId);
        }
        return taxon;
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
        Taxon taxon = new TaxonImpl();
        taxon.setName(corrector.correct(origTaxon.getName()));
        taxon.setExternalId(origTaxon.getExternalId());
        taxon.setPath(origTaxon.getPath());
        taxon.setStatus(origTaxon.getStatus());

        TaxonNode taxonNode = findTaxon(taxon.getName(), taxon.getExternalId());
        while (taxonNode == null) {
            try {
                taxon = TaxonUtil.enrich(enricher, taxon);
            } catch (PropertyEnricherException e) {
                throw new NodeFactoryException("failed to enrich taxon with name [" + taxon.getName() + "]", e);
            }
            taxonNode = findTaxon(taxon.getName(), taxon.getExternalId());
            if (taxonNode == null) {
                if (TaxonMatchValidator.hasMatch(taxon)) {
                    taxonNode = createAndIndexTaxon(taxon);
                } else {
                    String truncatedName = NodeUtil.truncateTaxonName(taxon.getName());
                    if (truncatedName == null || StringUtils.length(truncatedName) < 3) {
                        taxonNode = addNoMatchTaxon(origTaxon);
                    } else {
                        taxon = new TaxonImpl();
                        taxon.setName(truncatedName);
                        taxonNode = findTaxonByName(taxon.getName());
                    }
                }
            }
        }
        indexOriginalNameForTaxon(origTaxon.getName(), taxon, taxonNode);
        indexOriginalExternalIdForTaxon(origTaxon.getExternalId(), taxon, taxonNode);
        return taxonNode;
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
        Taxon noMatchTaxon = new TaxonImpl();
        noMatchTaxon.setName(StringUtils.isBlank(origTaxon.getName()) ? PropertyAndValueDictionary.NO_MATCH : origTaxon.getName());
        noMatchTaxon.setExternalId(StringUtils.isBlank(origTaxon.getExternalId()) ? PropertyAndValueDictionary.NO_MATCH : origTaxon.getExternalId());
        noMatchTaxon.setPath(origTaxon.getPath());
        noMatchTaxon.setStatus(origTaxon.getStatus());
        return createAndIndexTaxon(noMatchTaxon);
    }

    private TaxonNode createAndIndexTaxon(Taxon taxon) throws NodeFactoryException {
        TaxonNode taxonNode = null;
        Transaction transaction = graphDbService.beginTx();
        try {
            taxonNode = new TaxonNode(graphDbService.createNode(), taxon.getName());
            taxonNode.setExternalId(taxon.getExternalId());
            taxonNode.setPath(taxon.getPath());
            taxonNode.setPathNames(taxon.getPathNames());
            taxonNode.setPathIds(taxon.getPathIds());
            taxonNode.setCommonNames(taxon.getCommonNames());
            taxonNode.setRank(taxon.getRank());
            taxonNode.setStatus(taxon.getStatus());
            addToIndeces(taxonNode, taxon.getName());
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


}
