package org.eol.globi.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eol.globi.server.util.RequestHelper;
import org.eol.globi.service.TaxonUtil;
import org.eol.globi.util.CypherQuery;
import org.eol.globi.util.HttpUtil;
import org.hamcrest.CoreMatchers;
import org.hamcrest.core.StringContains;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mockito;
import org.neo4j.harness.junit.Neo4jRule;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import static org.eol.globi.server.CypherQueryBuilderTest.getNeo4jRule;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.hamcrest.core.Is.is;
import static org.mockito.Mockito.when;

public class TaxonSearchImplIT extends ITBase {

    @Rule
    public Neo4jRule neo4j = getNeo4jRule();


    @Test
    public void nameSuggestions() throws IOException {
        CypherQuery cypherQuery = new TaxonSearchImpl()
                .findCloseMatchesForCommonAndScientificNames("homo zapiens", null);
        CypherTestUtil.validate(cypherQuery, neo4j.getGraphDatabaseService());
        String result = new CypherQueryExecutor(cypherQuery).execute(null);
        assertThat(result, containsString("Homo sapiens"));
    }

    @Test
    public void findApidae() throws IOException {
        CypherQuery cypherQuery = new TaxonSearchImpl()
                .findCloseMatchesForCommonAndScientificNames("Apidae", null);
        CypherTestUtil.validate(cypherQuery, neo4j.getGraphDatabaseService());
        String result = new CypherQueryExecutor(cypherQuery).execute(null);
        JsonNode json = new ObjectMapper().readTree(result);

        JsonNode data = json.get("data");
        JsonNode firstRow = data.get(0);
        JsonNode firstCell = firstRow.get(0);
        assertThat(firstCell.asText(), is("Apidae"));
    }

    public static final String COLUMN_PREFIX = "{\"columns\":[\"taxon_name\",\"taxon_common_names\",\"taxon_path\",\"taxon_path_ids\"]";


    @Test
    public void findCloseMatchesTypo() throws IOException {
        assertHuman("Homo SApient");
    }

    @Test
    public void findCloseMatchesTypo2() throws IOException {
        assertHuman("Homo SApientz");
    }

    @Test
    public void findCloseMatchesPartial() throws IOException {
        assertHuman("Homo sa");
        assertHuman("Homo sapi");
    }

    @Test
    public void findCloseMatchesShortPartial() throws IOException {
        assertHuman("Homo s");
    }

    @Test
    public void findCloseMatchesShortPartialFields() throws IOException {
        HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        when(request.getParameterMap()).thenReturn(new HashMap<String, String[]>() {{
            put("field", new String[]{"taxon_external_id", "taxon_name"});
        }});
        when(request.getParameter("limit")).thenReturn("10");

        CypherQuery cypherQuery = new TaxonSearchImpl().findCloseMatchesForCommonAndScientificNames("Homo s", request);
        CypherTestUtil.validate(cypherQuery, neo4j.getGraphDatabaseService());

        String response = new CypherQueryExecutor(cypherQuery).execute(request);
        assertThat(cypherQuery.getQuery(), StringContains.containsString("LIMIT 10"));
        assertThat(response, StringContains.containsString("taxon_external_id"));
        assertThat(response, StringContains.containsString("taxon_name"));
    }

    private void assertHuman(String searchTerm) throws IOException {
        CypherQuery cypherQuery = new TaxonSearchImpl().findCloseMatchesForCommonAndScientificNames(searchTerm, null);
        CypherTestUtil.validate(cypherQuery, neo4j.getGraphDatabaseService());

        String response = new CypherQueryExecutor(cypherQuery).execute(null);

        assertThat(response, startsWith(COLUMN_PREFIX));
        assertThat(response, StringContains.containsString("Homo sapiens"));
        assertThat(response, StringContains.containsString("man"));
    }

    @Test
    public void findCloseMatchesShortPartial2() throws IOException {
        CypherQuery cypherQuery = new TaxonSearchImpl().findCloseMatchesForCommonAndScientificNames("h s", null);
        CypherTestUtil.validate(cypherQuery, neo4j.getGraphDatabaseService());

        String response = new CypherQueryExecutor(cypherQuery).execute(null);
        assertThat(response.startsWith(COLUMN_PREFIX), is(true));
        // expect at least one common name
        assertThat(response, StringContains.containsString("@en"));
    }

    @Test
    public void findCloseMatchesCommonNameFoxDutch() throws IOException {
        CypherQuery cypherQuery = new TaxonSearchImpl().findCloseMatchesForCommonAndScientificNames("vos", null);
        CypherTestUtil.validate(cypherQuery, neo4j.getGraphDatabaseService());

        String response = new CypherQueryExecutor(cypherQuery).execute(null);
        assertThat(response, startsWith(COLUMN_PREFIX));
        assertThat(response, StringContains.containsString("vos"));
    }

    @Test
    public void taxonLinks() throws IOException {
        Collection<String> links = new TaxonSearchImpl().taxonLinks("Homo sapiens", null);
        assertThat(links, CoreMatchers.hasItem("http://eol.org/pages/327955"));
        assertThat(links, CoreMatchers.hasItem("https://www.wikidata.org/wiki/Q15978631"));
    }

    @Test
    public void taxonLinksEOLv2() throws IOException {
        Collection<String> links = new TaxonSearchImpl().taxonLinks("Lepidochelys kempii", null);
        assertThat(links, not(CoreMatchers.hasItem("http://eol.org/pages/1056176")));
        assertThat(links, CoreMatchers.hasItem("https://www.wikidata.org/wiki/Q301089"));
    }

    @Test
    public void taxonLinks3() throws IOException {
        Collection<String> links = new TaxonSearchImpl().findTaxonIds("Homo sapiens");
        assertThat(links, CoreMatchers.hasItem("http://eol.org/pages/327955"));
        assertThat(links, CoreMatchers.hasItem("https://www.wikidata.org/wiki/Q15978631"));
    }

    @Test
    public void taxonLinksPlazi() throws IOException {
        Collection<String> links = new TaxonSearchImpl().findTaxonIds("http://taxon-concept.plazi.org/id/Animalia/Anguillicola_crassus_Kuwahara_1974");
        assertThat(links, CoreMatchers.hasItem("http://treatment.plazi.org/id/038FB248FF95FF9289B9C4DD228698DA"));
        assertThat(links, CoreMatchers.hasItem("WD:Q1859493"));
    }

    @Test
    public void taxonLinksPlazi2() throws IOException {
        Map<String, String> taxon = new TaxonSearchImpl().findTaxon("http://taxon-concept.plazi.org/id/Animalia/Anguillicola_crassus_Kuwahara_1974");
        assertThat(TaxonUtil.mapToTaxon(taxon).getPath(), containsString("Animalia"));
    }

    @Test
    public void taxonLinks2() throws IOException {
        Collection<String> links = new TaxonSearchImpl().taxonLinks("Enhydra lutris nereis", null);
        assertThat(links, CoreMatchers.hasItem("https://www.marinespecies.org/aphia.php?p=taxdetails&id=242601"));
    }

    @Test
    public void findCloseMatchesCommonNameFoxFrenchType() throws IOException {
        CypherQuery cypherQuery = new TaxonSearchImpl().findCloseMatchesForCommonAndScientificNames("reinard", null);
        CypherTestUtil.validate(cypherQuery, neo4j.getGraphDatabaseService());
        String response = new CypherQueryExecutor(cypherQuery).execute(null);
        assertThat(response, StringContains.containsString("Vulpes vulpes"));
        assertThat(response, StringContains.containsString("renard"));
    }

    @Test
    public void findCloseMatchesScientificNameRedFoxWithTypo() throws IOException {
        CypherQuery cypherQuery = new TaxonSearchImpl().findCloseMatchesForCommonAndScientificNames("Vulpes vules", null);
        CypherTestUtil.validate(cypherQuery, neo4j.getGraphDatabaseService());

        String response = new CypherQueryExecutor(cypherQuery).execute(null);
        assertThat(response, StringContains.containsString("Vulpes vulpes"));
    }

    @Test
    public void findCloseMatchesScientificGenus() throws IOException {
        CypherQuery cypherQuery = new TaxonSearchImpl().findCloseMatchesForCommonAndScientificNames("Ariidae", null);
        CypherTestUtil.validate(cypherQuery, neo4j.getGraphDatabaseService());

        String response = new CypherQueryExecutor(cypherQuery).execute(null);
        JsonNode mapper = new ObjectMapper().readTree(response);
        JsonNode data = mapper.get("data");
        assertThat(data.isArray(), is(true));
        assertThat(data.size() > 0, is(true));
        assertThat(response, StringContains.containsString("Ariidae"));
    }

    @Test
    public void findCloseMatchesScientificChineseCharacters() throws IOException {
        CypherQuery cypherQuery = new TaxonSearchImpl().findCloseMatchesForCommonAndScientificNames("Ariidae", null);
        CypherTestUtil.validate(cypherQuery, neo4j.getGraphDatabaseService());

        String response = new CypherQueryExecutor(cypherQuery).execute(null);
        assertThat(response, StringContains.containsString("印度尼西亚海鲶"));
    }

    @Test
    public void findCloseMatchesLowerCase() throws IOException {
        CypherQuery cypherQuery = new TaxonSearchImpl().findCloseMatchesForCommonAndScientificNames("King mackerel", null);
        CypherTestUtil.validate(cypherQuery, neo4j.getGraphDatabaseService());

        String response = new CypherQueryExecutor(cypherQuery).execute(null);
        assertThat(response, StringContains.containsString("Scomberomorus cavalla"));
        assertThat(response, StringContains.containsString("king mackeral"));
    }

    @Test
    public void findCloseMatchesUpperCase() throws IOException {
        CypherQuery cypherQuery = new TaxonSearchImpl().findCloseMatchesForCommonAndScientificNames("King Mackerel", null);
        CypherTestUtil.validate(cypherQuery, neo4j.getGraphDatabaseService());

        String response = new CypherQueryExecutor(cypherQuery).execute(null);
        assertThat(response, StringContains.containsString("Scomberomorus cavalla"));
        assertThat(response, StringContains.containsString("king mackeral"));
    }

    @Test
    @Ignore
    public void ensureSingleMatch() throws IOException {
        CypherQuery cypherQuery = new TaxonSearchImpl().findCloseMatchesForCommonAndScientificNames("Ariopsis felis", null);
        CypherTestUtil.validate(cypherQuery, neo4j.getGraphDatabaseService());

        String response = new CypherQueryExecutor(cypherQuery).execute(null);
        JsonNode jsonNode = new ObjectMapper().readTree(response);
        assertThat(jsonNode.get("data").get(0).get(0).asText(), is("Ariopsis felis"));
        assertThat(jsonNode.get("data").size(), is(1));
    }

    @Test
    public void findTaxonProxy() throws IOException {
        String response = new TaxonSearchImpl().findTaxonProxy("Ariopsis felis");
        JsonNode resp = RequestHelper.getFirstResult(new ObjectMapper().readTree(response));
        JsonNode columns = resp.get("columns");
        assertThat(columns.get(0).asText(), is("name"));
        assertThat(columns.get(1).asText(), is("commonNames"));
        assertThat(columns.get(2).asText(), is("path"));
        assertThat(columns.get(3).asText(), is("externalId"));
        assertThat(columns.get(4).asText(), is("externalUrl"));
        assertThat(columns.get(5).asText(), is("thumbnailUrl"));
        JsonNode info = RequestHelper.getRow(resp.get("data").get(0));
        assertThat(info.get(0).asText(), is("Ariopsis felis"));
        assertThat(info.get(2).asText(), StringContains.containsString("Actinopterygii"));
        assertThat(info.get(3).asText(), StringContains.containsString(":"));
    }

    @Test
    public void findTaxonAriopsisFelis() throws IOException {
        Map<String, String> props = new TaxonSearchImpl().findTaxon("Ariopsis felis");
        assertThat(props.get("name"), is("Ariopsis felis"));
        assertThat(props.get("path"), StringContains.containsString("Actinopterygii"));
        assertThat(props.get("externalId"), StringContains.containsString(":"));
    }

    @Test
    public void findTaxonAriopsisFelisById() throws IOException {
        Map<String, String> props = new TaxonSearchImpl().findTaxon("Ariopsis felis");
        assertThat(props.get("name"), is("Ariopsis felis"));
        assertThat(props.get("path"), StringContains.containsString("Actinopterygii"));
        assertThat(props.get("externalId"), is(notNullValue()));

        Map<String, String> linkProps = new TaxonSearchImpl().findTaxon(props.get("externalId"));
        assertThat(linkProps.get("name"), is("Ariopsis felis"));
    }

    @Test
    public void findTaxonNoAriopsisFelisWithThumbnail() throws IOException {
        Map<String, String> props = new TaxonSearchImpl().findTaxonWithImage("Ariopsis felis");
        assertThat(props.get("name"), is(nullValue()));
    }

    @Test
    public void findTaxonByExternalId() throws IOException {
        Map<String, String> props = new TaxonSearchImpl().findTaxon("Ariopsis felis");
        assertThat(props.get("name"), is("Ariopsis felis"));
        assertThat(props.get("path"), StringContains.containsString("Actinopterygii"));
        assertThat(props.get("externalId"), StringContains.containsString(":"));
    }

    @Test
    public void populateInfoURL() throws IOException {
        Map<String, String> props = new TaxonSearchImpl().findTaxon("Exacum divaricatum");
        assertThat(props.get("name"), is("Exacum divaricatum"));
        assertThat(props.get("path"), StringContains.containsString("Plantae"));
        assertThat(props.get("externalId"), StringContains.containsString(":"));
        assertThat(props.get("externalUrl"), StringContains.containsString(":"));
    }

    @Test
    public void taxonLinksWebAPI() throws IOException {
        String uri = getURLPrefix() + "taxonLinks/NCBI:9606";
        assertThat(HttpUtil.getRemoteJson(uri), is("[\"https://www.ncbi.nlm.nih.gov/Taxonomy/Browser/wwwtax.cgi?id=9606\"]"));
    }

    @Test
    public void taxonLinksWebAPI2() throws IOException {
        String uri = getURLPrefix() + "/taxonLinks/http%3A%2F%2Ftaxon-concept.plazi.org%2Fid%2FAnimalia%2FCARIDEA_Dana_1852";
        assertThat(HttpUtil.getRemoteJson(uri), CoreMatchers.containsString("https://www.wikidata.org/wiki/Q80117"));
    }
}
