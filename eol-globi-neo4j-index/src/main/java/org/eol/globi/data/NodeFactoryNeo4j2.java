package org.eol.globi.data;

import org.apache.commons.lang3.StringUtils;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.eol.globi.domain.DatasetNode;
import org.eol.globi.domain.EnvironmentNode;
import org.eol.globi.domain.Location;
import org.eol.globi.domain.LocationConstant;
import org.eol.globi.domain.LocationNode;
import org.eol.globi.domain.PropertyAndValueDictionary;
import org.eol.globi.domain.SeasonNode;
import org.eol.globi.domain.Study;
import org.eol.globi.domain.StudyConstant;
import org.eol.globi.domain.StudyNode;
import org.eol.globi.domain.Term;
import org.eol.globi.util.NodeUtil;
import org.globalbioticinteractions.dataset.Dataset;
import org.globalbioticinteractions.dataset.DatasetConstant;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.index.Index;
import org.neo4j.graphdb.index.IndexHits;
import org.neo4j.index.lucene.QueryContext;
import org.neo4j.index.lucene.ValueContext;

public class NodeFactoryNeo4j2 extends NodeFactoryNeo4j {

    private final Index<Node> datasets;
    private final Index<Node> studies;
    private final Index<Node> externalIds;
    private final Index<Node> seasons;
    private final Index<Node> locations;
    private final Index<Node> environments;

    // see https://github.com/globalbioticinteractions/globalbioticinteractions/issues/857
    // neo4j explicit indexes has a maximum to the size of values to be indexed
    public static final int MAX_NEO4J_INDEX_LENGTH = ((1 << 15) - 2) * 2;
    public static final int MAX_NEO4J_INDEX_LENGTH_IN_UTF_CHARACTERS = MAX_NEO4J_INDEX_LENGTH / 4;


    public NodeFactoryNeo4j2(GraphDatabaseService graphDb) {
        super(graphDb);
        this.datasets = NodeUtil.forNodes(graphDb, "datasets");
        this.studies = NodeUtil.forNodes(graphDb, "studies");
        this.externalIds = NodeUtil.forNodes(graphDb, "externalIds");
        this.seasons = NodeUtil.forNodes(graphDb, "seasons");
        this.locations = NodeUtil.forNodes(graphDb, "locations");
        this.environments = NodeUtil.forNodes(graphDb, "environments");
    }

    @Override
    Node createStudyNode() {
        return getGraphDb().createNode();
    }

    @Override
    void indexStudyNode(StudyNode studyNode) throws NodeFactoryException {
        indexNonBlankKeyValue(studies, studyNode.getUnderlyingNode(), StudyConstant.TITLE, studyNode.getTitle());
        indexNonBlankKeyValue(studies, studyNode.getUnderlyingNode(), StudyConstant.TITLE_IN_NAMESPACE, getIdInNamespace(studyNode));

    }

    @Override
    public StudyNode findStudy(Study study) {
        final IndexHits<Node> nodes = studies.get(StudyConstant.TITLE_IN_NAMESPACE, getIdInNamespace(study));
        Node foundStudyNode = nodes != null ? nodes.getSingle() : null;
        return foundStudyNode == null ? null : new StudyNode(foundStudyNode);
    }

    @Override
    protected void indexDatasetNode(Dataset dataset, Node datasetNode) throws NodeFactoryException {
        indexNonBlankKeyValue(datasets, datasetNode, DatasetConstant.NAMESPACE, dataset.getNamespace());
    }

    @Override
    protected Node createDatasetNode() {
        return getGraphDb().createNode();
    }

    @Override
    protected Dataset getOrCreateDatasetNoTx(Dataset originatingDataset) throws NodeFactoryException {
        Dataset datasetCreated = null;
        if (originatingDataset != null && StringUtils.isNotBlank(originatingDataset.getNamespace())) {
            IndexHits<Node> datasetHits = datasets.get(DatasetConstant.NAMESPACE, originatingDataset.getNamespace());

            Node datasetNode = datasetHits.hasNext()
                    ? datasetHits.next()
                    : createDatasetNode(originatingDataset);

            datasetCreated = new DatasetNode(datasetNode);
        }
        return datasetCreated;
    }

    @Override
    protected void indexExternalIdNode(String externalId, Node externalIdNode) throws NodeFactoryException {
        indexNonBlankKeyValue(externalIds, externalIdNode, PropertyAndValueDictionary.EXTERNAL_ID, externalId);
    }

    @Override
    protected Node createExternalIdNode() {
        return getGraphDb().createNode();
    }

    @Override
    protected Node getOrCreateExternalIdNoTx(String externalId) throws NodeFactoryException {
        Node externalIdNode = null;
        if (StringUtils.isNotBlank(externalId)) {
            IndexHits<Node> datasetHits = externalIds.get(PropertyAndValueDictionary.EXTERNAL_ID, externalId);
            externalIdNode = datasetHits.hasNext()
                    ? datasetHits.next()
                    : createExternalId(externalId);

        }
        return externalIdNode;
    }


    @Override
    public SeasonNode findSeason(String seasonName) {
        IndexHits<Node> nodeIndexHits = seasons.get(SeasonNode.TITLE, seasonName);
        Node seasonHit = nodeIndexHits.getSingle();
        nodeIndexHits.close();
        return seasonHit == null ? null : new SeasonNode(seasonHit);
    }

    @Override
    protected void indexSeasonNode(String seasonNameLower, Node node) throws NodeFactoryException {
        indexNonBlankKeyValue(seasons, node, SeasonNode.TITLE, seasonNameLower);
    }

    @Override
    protected Node createSeasonNode() {
        return getGraphDb().createNode();
    }

    @Override
    protected Node createLocationNode() {
        return getGraphDb().createNode();
    }

    @Override
    protected void indexLocation(Location location, Node node) throws NodeFactoryException {
        if (location.getLatitude() != null) {
            locations.add(node, LocationConstant.LATITUDE, ValueContext.numeric(location.getLatitude()));
        }
        if (location.getLongitude() != null) {
            locations.add(node, LocationConstant.LONGITUDE, ValueContext.numeric(location.getLongitude()));
        }
        if (location.getAltitude() != null) {
            locations.add(node, LocationConstant.ALTITUDE, ValueContext.numeric(location.getAltitude()));
        }
        indexNonBlankKeyValue(locations, node, LocationConstant.FOOTPRINT_WKT, location.getFootprintWKT());
        indexNonBlankKeyValue(locations, node, LocationConstant.LOCALITY, location.getLocality());
        indexNonBlankKeyValue(locations, node, LocationConstant.LOCALITY_ID, location.getLocalityId());

    }

    public static void indexNonBlankKeyValue(Index<Node> index, Node node, String key, String value) throws NodeFactoryException {
        if (StringUtils.isNotBlank(value)) {
            try {
                onlyIndexValuesThatFitIntoNeo4JIndex(index, node, key, value);
            } catch (IllegalArgumentException ex) {
                throw new NodeFactoryException("failed to index (key,value): (" + key + "," + value + ")", ex);
            }
        }
    }

    private static void onlyIndexValuesThatFitIntoNeo4JIndex(Index<Node> index, Node node, String key, String value) {
        if (value.length() < MAX_NEO4J_INDEX_LENGTH_IN_UTF_CHARACTERS) {
            index.add(node, key, value);
        }
    }

    public static String truncatedValueToBeIndexed(String value) {
        return value.length() < MAX_NEO4J_INDEX_LENGTH_IN_UTF_CHARACTERS
                ? value
                : StringUtils.truncate(value, MAX_NEO4J_INDEX_LENGTH_IN_UTF_CHARACTERS - 1);
    }

    @Override
    public LocationNode findLocation(Location location) throws NodeFactoryException {
        Node matchingLocation = null;
        if (org.eol.globi.domain.LocationUtil.hasLatLng(location)) {
            matchingLocation = findLocationByLatitude(location);
        }
        if (matchingLocation == null) {
            matchingLocation = findLocationByLocality(location);
        }
        if (matchingLocation == null) {
            matchingLocation = findLocationByLocalityId(location);
        }
        return matchingLocation == null ? null : new LocationNode(matchingLocation);
    }


    private Node findLocationByLocality(Location location) throws NodeFactoryException {
        return location.getLocality() == null ? null : findLocationBy(location, LocationConstant.LOCALITY, location.getLocality());
    }

    private Node findLocationByLocalityId(Location location) throws NodeFactoryException {
        return location.getLocalityId() == null ? null : findLocationBy(location, LocationConstant.LOCALITY_ID, location.getLocalityId());
    }

    private Node findLocationBy(Location location, String key, String value) {
        String query = key + ":\"" + QueryParser.escape(value) + "\"";
        IndexHits<Node> matchingLocations = locations.query(query);
        Node matchingLocation = findFirstMatchingLocationIfAvailable(location, matchingLocations);
        matchingLocations.close();
        return matchingLocation;
    }


    private Node findLocationByLatitude(Location location) throws NodeFactoryException {
        validate(location);
        QueryContext queryOrQueryObject = QueryContext.numericRange(LocationConstant.LATITUDE, location.getLatitude(), location.getLatitude());
        IndexHits<Node> matchingLocations = locations.query(queryOrQueryObject);
        Node matchingLocation = findFirstMatchingLocationIfAvailable(location, matchingLocations);
        matchingLocations.close();
        return matchingLocation;
    }

    @Override
    public Node createEnvironmentNode() {
        return getGraphDb().createNode();
    }

    @Override
    public void indexEnvironmentNode(Term term, EnvironmentNode environmentNode) throws NodeFactoryException {
        indexNonBlankKeyValue(environments,
                environmentNode.getUnderlyingNode(),
                PropertyAndValueDictionary.NAME,
                term.getName());
    }

    @Override
    public EnvironmentNode findEnvironment(String name) {
        EnvironmentNode firstMatchingEnvironment = null;
        String query = "name:\"" + name + "\"";
        IndexHits<Node> matches = environments.query(query);
        if (matches.hasNext()) {
            firstMatchingEnvironment = new EnvironmentNode(matches.next());
        }
        matches.close();
        return firstMatchingEnvironment;
    }


}

