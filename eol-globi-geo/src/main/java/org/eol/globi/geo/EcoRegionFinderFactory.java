package org.eol.globi.geo;

import java.net.URL;
import java.util.HashMap;
import java.util.Map;

public class EcoRegionFinderFactory {

    private static Map<EcoRegionType, EcoRegionFinderConfig> typeUrlMap = new HashMap<EcoRegionType, EcoRegionFinderConfig>() {{
        // Terrestrial Ecosystem of the World
        // http://maps.tnc.org/files/metadata/TerrEcos.xml
        // http://maps.tnc.org/files/shp/terr-ecoregions-TNC.zip
        EcoRegionFinderConfig config = new EcoRegionFinderConfig();
        config.setShapeFileURL(getDataStoreURLForShapeFile("/teow-tnc/tnc_terr_ecoregions.shp"));
        config.setNameLabel("ECO_NAME");
        config.setIdLabel("ECO_ID_U");
        config.setNamespace("TEOW");
        config.setPathLabels(new String[]{config.getNameLabel(), "WWF_MHTNAM", "WWF_REALM2"});
        put(EcoRegionType.Terrestrial, config);
        config.setGeometryLabel("the_geom");

        // http://maps.tnc.org/gis_data.html
        // Marine Ecosystems of the World (MEOW) http://maps.tnc.org/files/metadata/MEOW.xml
        // http://maps.tnc.org/files/shp/MEOW-TNC.zip
        config = new EcoRegionFinderConfig();
        config.setShapeFileURL(getDataStoreURLForShapeFile("/meow-tnc/meow_ecos.shp"));
        config.setNameLabel("ECOREGION");
        config.setIdLabel("ECO_CODE");
        config.setNamespace("MEOW");
        config.setPathLabels(new String[]{config.getNameLabel(), "PROVINCE", "REALM", "Lat_Zone"});
        config.setGeometryLabel("the_geom");
        put(EcoRegionType.Marine, config);

        // Fresh Water Ecosystems of the World (FEW) http://www.feow.org/
        // http://maps.tnc.org/files/metadata/FEOW.xml
        // http://maps.tnc.org/files/shp/FEOW-TNC.zip
        config = new EcoRegionFinderConfig();
        config.setShapeFileURL(getDataStoreURLForShapeFile("/feow-tnc/FEOWv1_TNC.shp"));
        config.setNameLabel( "ECOREGION");
        config.setIdLabel("ECO_ID_U");
        config.setNamespace("FEOW");
        config.setPathLabels(new String[]{config.getNameLabel(), "MHT_TXT"});
        config.setGeometryLabel("the_geom");
        put(EcoRegionType.Freshwater, config);
    }};

    private static URL getDataStoreURLForShapeFile(String shapeFile) {
        try {
            return EcoRegionFinderFactory.class.getResource(shapeFile).toURI().toURL();
        } catch (Exception e) {
            throw new RuntimeException("failed to find [" + shapeFile + "] ... did you run mvn install on the commandline to install shapefiles?");
        }
    }


    public EcoRegionFinder createEcoRegionFinder(EcoRegionType type) {
        return new EcoRegionFinderImpl(typeUrlMap.get(type));
    }
}
