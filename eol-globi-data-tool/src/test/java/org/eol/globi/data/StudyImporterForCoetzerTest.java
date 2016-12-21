package org.eol.globi.data;

import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.node.ObjectNode;
import org.eol.globi.domain.Study;
import org.eol.globi.service.Dataset;
import org.eol.globi.util.NodeUtil;
import org.junit.Test;

import java.io.IOException;
import java.net.URI;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThat;

public class StudyImporterForCoetzerTest extends GraphDBTestCase {

    @Test
    public void importSome() throws StudyImporterException, IOException {
        StudyImporterForCoetzer importer = new StudyImporterForCoetzer(null, nodeFactory);
        Dataset dataset = new Dataset(null, URI.create("classpath:coetzer/CatalogOfAfrotropicalBees.zip"));
        ObjectNode objectNode = new ObjectMapper().createObjectNode();
        objectNode.put("citation", "source citation");
        dataset.setConfig(objectNode);
        importer.setDataset(dataset);
        importStudy(importer);

        List<Study> allStudies = NodeUtil.findAllStudies(getGraphDb());
        for (Study allStudy : allStudies) {
            assertThat(allStudy.getSource(), startsWith("source citation"));
            assertThat(allStudy.getSource(), containsString("Accessed at"));
        }

        assertThat(taxonIndex.findTaxonByName("Agrostis tremula"), is(notNullValue()));
        assertThat(taxonIndex.findTaxonByName("Coelioxys erythrura"), is(notNullValue()));
        assertThat(taxonIndex.findTaxonByName("Patellapis namaquensis"), is(notNullValue()));

    }

}
