package org.eol.globi.tool;

import org.apache.commons.cli.BasicParser;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.lang3.StringUtils;
import org.eol.globi.Version;
import org.eol.globi.data.NodeFactoryNeo4j2;
import org.eol.globi.data.StudyImporterException;
import org.eol.globi.db.GraphServiceFactory;
import org.eol.globi.db.GraphServiceFactoryImpl;
import org.eol.globi.export.GraphExporterImpl;
import org.eol.globi.service.DOIResolverCache;
import org.eol.globi.taxon.NonResolvingTaxonIndex;
import org.eol.globi.taxon.TaxonCacheService;
import org.eol.globi.util.HttpUtil;
import org.neo4j.graphdb.GraphDatabaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;

public class Normalizer {
    private static final Logger LOG = LoggerFactory.getLogger(Normalizer.class);
    private static final String OPTION_HELP = "h";
    private static final String OPTION_SKIP_IMPORT = "skipImport";
    private static final String OPTION_SKIP_TAXON_CACHE = "skipTaxonCache";
    private static final String OPTION_SKIP_RESOLVE = "skipResolve";
    private static final String OPTION_SKIP_EXPORT = "skipExport";
    private static final String OPTION_SKIP_LINK_THUMBNAILS = "skipLinkThumbnails";
    private static final String OPTION_SKIP_LINK = "skipLink";
    private static final String OPTION_SKIP_REPORT = "skipReport";
    private static final String OPTION_SKIP_RESOLVE_CITATIONS = OPTION_SKIP_RESOLVE;

    public static void main(final String[] args) throws StudyImporterException, ParseException {
        String o = Version.getVersionInfo(Normalizer.class);
        LOG.info(o);
        CommandLine cmdLine = parseOptions(args);
        if (cmdLine.hasOption(OPTION_HELP)) {
            HelpFormatter formatter = new HelpFormatter();
            formatter.printHelp("java -jar eol-globi-data-tool-[VERSION]-jar-with-dependencies.jar", getOptions());
        } else {
            try {
                new Normalizer().run(cmdLine);
            } catch (Throwable th) {
                LOG.error("failed to run GloBI indexer with [" + StringUtils.join(args, " ") + "]", th);
                throw th;
            }
        }
    }


    protected static CommandLine parseOptions(String[] args) throws ParseException {
        CommandLineParser parser = new BasicParser();
        return parser.parse(getOptions(), args);
    }

    private static Options getOptions() {
        Options options = new Options();
        options.addOption(OPTION_SKIP_IMPORT, false, "skip the import of all GloBI datasets");
        options.addOption(OPTION_SKIP_EXPORT, false, "skip the export for GloBI datasets to aggregated archives.");
        options.addOption(OPTION_SKIP_TAXON_CACHE, false, "skip usage of taxon cache");
        options.addOption(OPTION_SKIP_RESOLVE, false, "skip taxon name query to external taxonomies");
        options.addOption(OPTION_SKIP_LINK_THUMBNAILS, false, "skip linking of names to thumbnails");
        options.addOption(OPTION_SKIP_LINK, false, "skip taxa cross-reference step");
        options.addOption(OPTION_SKIP_REPORT, false, "skip report generation step");
        options.addOption(CmdOptionConstants.OPTION_DATASET_DIR, true, "specifies location of dataset cache");

        Option helpOpt = new Option(OPTION_HELP, "help", false, "print this help information");
        options.addOption(helpOpt);
        return options;
    }

    void run(CommandLine cmdLine) throws StudyImporterException {

        GraphServiceFactoryImpl graphServiceFactory = new GraphServiceFactoryImpl("./");

        try {
            importDatasets(cmdLine, graphServiceFactory);
            processDatasets(cmdLine, graphServiceFactory);
        } finally {
            HttpUtil.shutdown();
        }

    }

    private void processDatasets(CommandLine cmdLine, GraphServiceFactoryImpl graphServiceFactory) throws StudyImporterException {
        Factories factoriesNeo4j2 = new FactoriesBase(graphServiceFactory);
        GraphServiceFactory graphServiceFactory1 = factoriesNeo4j2.getGraphServiceFactory();
        resolveAndLinkTaxa(cmdLine, graphServiceFactory1);
        generateReports(cmdLine, graphServiceFactory1);
        exportData(cmdLine, graphServiceFactory1.getGraphService());
    }

    private void importDatasets(CommandLine cmdLine, GraphServiceFactory graphServiceFactory) throws StudyImporterException {
        Factories importerFactory = new FactoriesForDatasetImport(graphServiceFactory);
        GraphServiceFactory graphDbFactory = importerFactory.getGraphServiceFactory();
        importDatasets(cmdLine, graphDbFactory, importerFactory.getNodeFactoryFactory());
    }

    private void exportData(CommandLine cmdLine, GraphDatabaseService graphService) throws StudyImporterException {
        if (cmdLine == null || !cmdLine.hasOption(OPTION_SKIP_EXPORT)) {
            exportData(graphService, "./");
        } else {
            LOG.info("skipping data export...");
        }
    }

    private void generateReports(CommandLine cmdLine, GraphServiceFactory graphService) {
        if (cmdLine == null || !cmdLine.hasOption(OPTION_SKIP_REPORT)) {
            new CmdGenerateReport(graphService.getGraphService()).run();
        } else {
            LOG.info("skipping report generation ...");
        }
    }

    private void importDatasets(CommandLine cmdLine, GraphServiceFactory factory, NodeFactoryFactory nodeFactoryFactory) throws StudyImporterException {
        if (cmdLine == null || !cmdLine.hasOption(OPTION_SKIP_IMPORT)) {
            new CmdIndexDatasets(cmdLine, nodeFactoryFactory, factory).run();
        } else {
            LOG.info("skipping data import...");
        }
    }

    private void resolveAndLinkTaxa(CommandLine cmdLine, GraphServiceFactory graphServiceFactory) throws StudyImporterException {
        if (cmdLine == null || !cmdLine.hasOption(OPTION_SKIP_RESOLVE_CITATIONS)) {
            LOG.info("resolving citations to DOIs ...");
            new LinkerDOI(new DOIResolverCache(), graphServiceFactory).index();
        } else {
            LOG.info("skipping citation resolving ...");
        }

        if (cmdLine == null || !cmdLine.hasOption(OPTION_SKIP_TAXON_CACHE)) {
            new CmdInterpretTaxa(graphServiceFactory).run();
        } else {
            LOG.info("skipping taxon cache ...");
        }

        if (cmdLine == null || !cmdLine.hasOption(OPTION_SKIP_RESOLVE)) {
            cmdIndexTaxa(graphServiceFactory);
        } else {
            LOG.info("skipping taxa resolving ...");
        }

        if (cmdLine == null || !cmdLine.hasOption(OPTION_SKIP_LINK)) {
            CmdIndexTaxa(graphServiceFactory);
        } else {
            LOG.info("skipping linking ...");
        }
    }

    private void CmdIndexTaxa(GraphServiceFactory graphServiceFactory) throws StudyImporterException {
        new CmdBuildTaxonSearch(graphServiceFactory);
    }

    private void cmdIndexTaxa(GraphServiceFactory graphServiceFactory) throws StudyImporterException {
        new CmdIndexTaxa(graphServiceFactory).run();
    }

    void exportData(GraphDatabaseService graphService, String baseDir) throws StudyImporterException {
        new GraphExporterImpl()
                .export(graphService, baseDir);
    }

    private static class CmdInterpretTaxa implements Cmd {

        private final GraphServiceFactory graphServiceFactory;

        public CmdInterpretTaxa(GraphServiceFactory graphServiceFactory) {
            this.graphServiceFactory = graphServiceFactory;
        }

        @Override
        public void run() throws StudyImporterException {
            final TaxonCacheService taxonCacheService = new TaxonCacheService(
                    "/taxa/taxonCache.tsv.gz",
                    "/taxa/taxonMap.tsv.gz");
            IndexerNeo4j taxonIndexer = new IndexerTaxa(taxonCacheService, graphServiceFactory);
            taxonIndexer.index();
        }
    }

    private static class CmdIndexTaxa implements Cmd {

        private final GraphServiceFactory graphServiceFactory;

        public CmdIndexTaxa(GraphServiceFactory graphServiceFactory) {
            this.graphServiceFactory = graphServiceFactory;
        }

        @Override
        public void run() throws StudyImporterException {
            final NonResolvingTaxonIndex taxonIndex = new NonResolvingTaxonIndex(graphServiceFactory.getGraphService());
            final IndexerNeo4j nameResolver = new NameResolver(graphServiceFactory, taxonIndex);
            final IndexerNeo4j taxonInteractionIndexer = new TaxonInteractionIndexer(graphServiceFactory);

            List<IndexerNeo4j> indexers = Arrays.asList(nameResolver, taxonInteractionIndexer);
            for (IndexerNeo4j indexer : indexers) {
                indexer.index();
            }

        }
    }

    public class FactoriesForDatasetImport extends FactoriesBase {
        FactoriesForDatasetImport(GraphServiceFactory graphServiceFactory) {
            super(graphServiceFactory);
        }

        @Override
        public NodeFactoryFactory getNodeFactoryFactory() {
            return new NodeFactoryFactoryTransactingOnDataset(this.getGraphServiceFactory());
        }

    }

    public class FactoriesBase implements Factories {

        private final GraphServiceFactory factory;

        FactoriesBase(GraphServiceFactory factory) {
            this.factory = factory;
        }

        @Override
        public GraphServiceFactory getGraphServiceFactory() {
            return factory;
        }

        @Override
        public NodeFactoryFactory getNodeFactoryFactory() {
            return service -> new NodeFactoryNeo4j2(factory.getGraphService());
        }
    }

}