package org.eol.globi.tool;

import net.trustyuri.TrustyUriException;
import org.apache.commons.cli.BasicParser;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.eol.globi.Version;
import org.eol.globi.data.NodeFactoryException;
import org.eol.globi.data.NodeFactoryNeo4j;
import org.eol.globi.data.StudyImporter;
import org.eol.globi.data.StudyImporterException;
import org.eol.globi.data.StudyImporterFactory;
import org.eol.globi.db.GraphService;
import org.eol.globi.domain.Taxon;
import org.eol.globi.domain.TaxonomyProvider;
import org.eol.globi.export.GraphExporterImpl;
import org.eol.globi.geo.EcoregionFinder;
import org.eol.globi.geo.EcoregionFinderFactoryImpl;
import org.eol.globi.opentree.OpenTreeTaxonIndex;
import org.eol.globi.service.DOIResolverCache;
import org.eol.globi.service.DOIResolverImpl;
import org.eol.globi.service.EcoregionFinderProxy;
import org.eol.globi.service.PropertyEnricher;
import org.eol.globi.service.PropertyEnricherException;
import org.eol.globi.service.PropertyEnricherFactory;
import org.eol.globi.taxon.CorrectionService;
import org.eol.globi.taxon.TaxonCacheService;
import org.eol.globi.taxon.TaxonIndexNeo4j;
import org.eol.globi.taxon.TaxonNameCorrector;
import org.eol.globi.util.HttpUtil;
import org.nanopub.MalformedNanopubException;
import org.neo4j.graphdb.GraphDatabaseService;
import org.openrdf.OpenRDFException;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;

public class Normalizer {
    private static final Log LOG = LogFactory.getLog(Normalizer.class);
    private static final String OPTION_HELP = "h";
    private static final String OPTION_SKIP_IMPORT = "skipImport";
    private static final String OPTION_SKIP_TAXON_CACHE = "skipTaxonCache";
    private static final String OPTION_SKIP_RESOLVE = "skipResolve";
    private static final String OPTION_SKIP_EXPORT = "skipExport";
    private static final String OPTION_SKIP_LINK_THUMBNAILS = "skipLinkThumbnails";
    private static final String OPTION_SKIP_LINK = "skipLink";
    private static final String OPTION_SKIP_REPORT = "skipReport";
    private static final String OPTION_USE_DARK_DATA = "useDarkData";
    private static final String OPTION_SKIP_RESOLVE_CITATIONS = OPTION_SKIP_RESOLVE;

    private EcoregionFinder ecoregionFinder = null;

    public static void main(final String[] args) throws StudyImporterException, ParseException {
        String o = Version.getVersionInfo(Normalizer.class);
        LOG.info(o);
        CommandLine cmdLine = parseOptions(args);
        if (cmdLine.hasOption(OPTION_HELP)) {
            HelpFormatter formatter = new HelpFormatter();
            formatter.printHelp("java -jar eol-globi-data-tool-[VERSION]-jar-with-dependencies.jar", getOptions());
        } else {
            new Normalizer().run(cmdLine);
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
        options.addOption(OPTION_SKIP_RESOLVE, false, "skip taxon name resolve to external taxonomies");
        options.addOption(OPTION_SKIP_LINK_THUMBNAILS, false, "skip linking of names to thumbnails");
        options.addOption(OPTION_SKIP_LINK, false, "skip taxa cross-reference step");
        options.addOption(OPTION_SKIP_REPORT, false, "skip report generation step");
        options.addOption(OPTION_USE_DARK_DATA, false, "use only dark datasets (requires permission)");

        Option helpOpt = new Option(OPTION_HELP, "help", false, "print this help information");
        options.addOption(helpOpt);
        return options;
    }

    public void run(CommandLine cmdLine) throws StudyImporterException {
        final GraphDatabaseService graphService = GraphService.getGraphService("./");
        try {
            importDatasets(cmdLine, graphService);
            resolveAndLinkTaxa(cmdLine, graphService);
            generateReports(cmdLine, graphService);
            exportData(cmdLine, graphService);
        } finally {
            graphService.shutdown();
            HttpUtil.shutdown();
        }

    }

    public void exportData(CommandLine cmdLine, GraphDatabaseService graphService) throws StudyImporterException {
        if (cmdLine == null || !cmdLine.hasOption(OPTION_SKIP_EXPORT)) {
            exportData(graphService, "./");
        } else {
            LOG.info("skipping data export...");
        }
    }

    public void generateReports(CommandLine cmdLine, GraphDatabaseService graphService) {
        if (cmdLine == null || !cmdLine.hasOption(OPTION_SKIP_REPORT)) {
            new ReportGenerator(graphService).run();
        } else {
            LOG.info("skipping report generation ...");
        }
    }

    public void importDatasets(CommandLine cmdLine, GraphDatabaseService graphService) {
        if (cmdLine == null || !cmdLine.hasOption(OPTION_SKIP_IMPORT)) {
            Collection<Class<? extends StudyImporter>> importers = StudyImporterFactory.getImporters();
            importData(graphService, importers);
        } else {
            LOG.info("skipping data import...");
        }
    }

    public void resolveAndLinkTaxa(CommandLine cmdLine, GraphDatabaseService graphService) {
        if (cmdLine == null || !cmdLine.hasOption(OPTION_SKIP_RESOLVE_CITATIONS)) {
            LOG.info("resolving citations to DOIs ...");
            new LinkerDOI(graphService, new DOIResolverCache()).link();
            new LinkerDOI(graphService).link();
        } else {
            LOG.info("skipping citation resolving ...");
        }


        if (cmdLine == null || !cmdLine.hasOption(OPTION_SKIP_TAXON_CACHE)) {
            LOG.info("resolving names with taxon cache ...");
            final TaxonCacheService enricher = new TaxonCacheService("/taxa/taxonCache.tsv.gz", "/taxa/taxonMap.tsv.gz");
            try {
                TaxonIndexNeo4j index = new TaxonIndexNeo4j(enricher, new CorrectionService() {
                    @Override
                    public String correct(String taxonName) {
                        return taxonName;
                    }
                }, graphService);
                index.setIndexResolvedTaxaOnly(true);

                TaxonFilter taxonCacheFilter = new TaxonFilter() {

                    private KnownBadNameFilter knownBadNameFilter = new KnownBadNameFilter();

                    @Override
                    public boolean shouldInclude(Taxon taxon) {
                        return taxon != null
                            && knownBadNameFilter.shouldInclude(taxon)
                            && (!StringUtils.startsWith(taxon.getExternalId(), TaxonomyProvider.INATURALIST_TAXON.getIdPrefix()));
                    }
                };

                new NameResolver(graphService, index, taxonCacheFilter).resolve();
            } finally {
                enricher.shutdown();
            }
            LOG.info("resolving names with taxon cache done.");
        } else {
            LOG.info("skipping taxon cache ...");
        }

        if (cmdLine == null || !cmdLine.hasOption(OPTION_SKIP_RESOLVE)) {
            final TaxonNameCorrector taxonNameCorrector = new TaxonNameCorrector();
            PropertyEnricher taxonEnricher = PropertyEnricherFactory.createTaxonEnricher();
            try {
                new NameResolver(graphService, new TaxonIndexNeo4j(taxonEnricher, taxonNameCorrector, graphService)).resolve();
                new TaxonInteractionIndexer(graphService).index();
            } finally {
                taxonEnricher.shutdown();
            }
        } else {
            LOG.info("skipping taxa resolving ...");
        }

        if (cmdLine == null || !cmdLine.hasOption(OPTION_SKIP_LINK)) {
            linkTaxa(graphService);
            indexInteractions(graphService);
            linkTrustyURIs(graphService);
        } else {
            LOG.info("skipping linking ...");
        }

        if (cmdLine == null || !cmdLine.hasOption(OPTION_SKIP_LINK_THUMBNAILS)) {
            new ImageLinker().linkImages(graphService, null);
        } else {
            LOG.info("skipping linking of taxa to thumbnails ...");
        }
    }

    public void linkTrustyURIs(GraphDatabaseService graphService) {
        try {
            LOG.info("trusty uri linking started...");
            new LinkerTrustyNanoPubs().link(graphService);
            LOG.info("trusty uri linking done.");
        } catch (MalformedNanopubException | TrustyUriException | OpenRDFException e) {
            LOG.warn("Problem linking interactions to trusty uris", e);
        }
    }

    public void indexInteractions(GraphDatabaseService graphService) {
        try {
            LOG.info("interaction indexing started...");
            new IndexInteractions().link(graphService);
            LOG.info("interaction indexing done.");
        } catch (NodeFactoryException e) {
            LOG.warn("Problem linking interactions with study and datasets", e);
        }
    }

    private void linkTaxa(GraphDatabaseService graphService) {
        try {
            new LinkerGlobalNames().link(graphService);
        } catch (PropertyEnricherException e) {
            LOG.warn("Problem linking taxa using Global Names Resolver", e);
        }

        String ottUrl = System.getProperty("ott.url");
        try {
            if (StringUtils.isNotBlank(ottUrl)) {
                new LinkerOpenTreeOfLife().link(graphService, new OpenTreeTaxonIndex(new URI(ottUrl).toURL()));
            }
        } catch (MalformedURLException | URISyntaxException e) {
            LOG.warn("failed to link against OpenTreeOfLife", e);
        }

        new LinkerTaxonIndex().link(graphService);

    }

    private EcoregionFinder getEcoregionFinder() {
        if (null == ecoregionFinder) {
            ecoregionFinder = new EcoregionFinderProxy(new EcoregionFinderFactoryImpl().createAll());
        }
        return ecoregionFinder;
    }

    public void setEcoregionFinder(EcoregionFinder finder) {
        this.ecoregionFinder = finder;
    }

    protected void exportData(GraphDatabaseService graphService, String baseDir) throws StudyImporterException {
        new GraphExporterImpl().export(graphService, baseDir);
    }


    private void importData(GraphDatabaseService graphService, Collection<Class<? extends StudyImporter>> importers) {
        NodeFactoryNeo4j factory = new NodeFactoryNeo4j(graphService);
        factory.setEcoregionFinder(getEcoregionFinder());
        factory.setDoiResolver(new DOIResolverImpl());
        for (Class<? extends StudyImporter> importer : importers) {
            try {
                importData(importer, factory);
            } catch (StudyImporterException e) {
                LOG.error("problem encountered while importing [" + importer.getName() + "]", e);
            }
        }
        EcoregionFinder regionFinder = getEcoregionFinder();
        if (regionFinder != null) {
            regionFinder.shutdown();
        }
    }

    protected void importData(Class<? extends StudyImporter> importer, NodeFactoryNeo4j factory) throws StudyImporterException {
        StudyImporter studyImporter = createStudyImporter(importer, factory);
        LOG.info("[" + importer + "] importing ...");
        studyImporter.importStudy();
        LOG.info("[" + importer + "] imported.");
    }

    private StudyImporter createStudyImporter(Class<? extends StudyImporter> studyImporter, NodeFactoryNeo4j factory) throws StudyImporterException {
        StudyImporter importer = new StudyImporterFactory(factory).instantiateImporter(studyImporter);
        importer.setLogger(new StudyImportLogger());
        return importer;
    }

}