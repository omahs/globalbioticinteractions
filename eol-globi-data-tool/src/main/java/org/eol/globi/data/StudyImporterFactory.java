package org.eol.globi.data;

import org.eol.globi.service.DatasetLocal;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;

public class StudyImporterFactory {

    private static final Collection<Class<? extends StudyImporter>> IMPORTERS = Collections.unmodifiableCollection(new ArrayList<Class<? extends StudyImporter>>() {{
        add(StudyImporterForGitHubData.class);
        add(StudyImporterForWebOfLife.class);
        add(StudyImporterForKelpForest.class);
        add(StudyImporterForGemina.class);
        add(StudyImporterForCruaud.class);
        add(StudyImporterForStrona.class);
        add(StudyImporterForBell.class);
        add(StudyImporterForHafner.class);
        add(StudyImporterForBrose.class);
        add(StudyImporterForSIAD.class);
        add(StudyImporterForHurlbert.class);
        add(StudyImporterForByrnes.class);
        add(StudyImporterForRaymond.class);
        add(StudyImporterForLifeWatchGreece.class);
        add(StudyImporterForRoopnarine.class);
        add(StudyImporterForSimons.class);
        add(StudyImporterForWrast.class);
        add(StudyImporterForBlewett.class);
        add(StudyImporterForBaremore.class);
        add(StudyImporterForJRFerrerParis.class);
        add(StudyImporterForSPIRE.class);
        add(StudyImporterForICES.class);
        add(StudyImporterForBarnes.class);
        add(StudyImporterForCook.class);
        add(StudyImporterForRobledo.class);
        add(StudyImporterForINaturalist.class);
        add(StudyImporterForThessen.class);
        add(StudyImporterForBioInfo.class);
    }});

    private NodeFactory nodeFactory;
    private ParserFactory parserFactory;

    public StudyImporterFactory(NodeFactory nodeFactory) {
        this(new ParserFactoryLocal(), nodeFactory);
    }
    public StudyImporterFactory(ParserFactory parserFactory, NodeFactory nodeFactory) {
        this.parserFactory = parserFactory;
        this.nodeFactory = nodeFactory;
    }

    public StudyImporter instantiateImporter(Class<? extends StudyImporter> clazz) throws StudyImporterException {
        try {
            Constructor<? extends StudyImporter> aConstructor = clazz.getConstructor(ParserFactory.class, NodeFactory.class);
            StudyImporter studyImporter = aConstructor.newInstance(parserFactory, nodeFactory);
            studyImporter.setDataset(new DatasetLocal());
            return studyImporter;
        } catch (Exception ex) {
            throw new StudyImporterException("failed to create study importer for [" + clazz.toString() + "]", ex);
        }
    }


    public static Collection<Class<? extends StudyImporter>> getImporters() {
        return IMPORTERS;
    }

}
