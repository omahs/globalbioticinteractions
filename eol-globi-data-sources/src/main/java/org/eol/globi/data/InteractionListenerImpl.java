package org.eol.globi.data;

import org.apache.commons.lang.StringUtils;
import org.eol.globi.domain.InteractType;
import org.eol.globi.domain.Location;
import org.eol.globi.domain.Specimen;
import org.eol.globi.domain.Study;
import org.eol.globi.domain.StudyImpl;
import org.eol.globi.domain.TaxonImpl;
import org.eol.globi.domain.Term;
import org.eol.globi.geo.LatLng;
import org.eol.globi.service.GeoNamesService;
import org.eol.globi.util.InvalidLocationException;
import org.joda.time.DateTime;
import org.joda.time.format.ISODateTimeFormat;

import java.io.IOException;
import java.util.Map;
import java.util.logging.Level;

import static org.eol.globi.data.StudyImporterForTSV.BASIS_OF_RECORD_ID;
import static org.eol.globi.data.StudyImporterForTSV.BASIS_OF_RECORD_NAME;
import static org.eol.globi.data.StudyImporterForTSV.DECIMAL_LATITUDE;
import static org.eol.globi.data.StudyImporterForTSV.DECIMAL_LONGITUDE;
import static org.eol.globi.data.StudyImporterForTSV.INTERACTION_TYPE_ID;
import static org.eol.globi.data.StudyImporterForTSV.LOCALITY_ID;
import static org.eol.globi.data.StudyImporterForTSV.REFERENCE_CITATION;
import static org.eol.globi.data.StudyImporterForTSV.REFERENCE_DOI;
import static org.eol.globi.data.StudyImporterForTSV.REFERENCE_ID;
import static org.eol.globi.data.StudyImporterForTSV.REFERENCE_URL;
import static org.eol.globi.data.StudyImporterForTSV.SOURCE_TAXON_ID;
import static org.eol.globi.data.StudyImporterForTSV.SOURCE_TAXON_NAME;
import static org.eol.globi.data.StudyImporterForTSV.STUDY_SOURCE_CITATION;
import static org.eol.globi.data.StudyImporterForTSV.TARGET_TAXON_ID;
import static org.eol.globi.data.StudyImporterForTSV.TARGET_TAXON_NAME;

class InteractionListenerImpl implements InteractionListener {
    private final NodeFactory nodeFactory;
    private final GeoNamesService geoNamesService;

    private final ImportLogger logger;

    public InteractionListenerImpl(NodeFactory nodeFactory, GeoNamesService geoNamesService, ImportLogger logger) {
        this.nodeFactory = nodeFactory;
        this.geoNamesService = geoNamesService;
        this.logger = logger;
    }

    @Override
    public void newLink(Map<String, String> properties) throws StudyImporterException {
        try {
            if (properties != null) {
                importLink(properties);
            }
        } catch (NodeFactoryException | IOException e) {
            throw new StudyImporterException("failed to import: " + properties, e);
        }
    }

    private void importLink(Map<String, String> link) throws StudyImporterException, IOException {
        String sourceTaxonName = link.get(SOURCE_TAXON_NAME);
        String sourceTaxonId = link.get(SOURCE_TAXON_ID);
        String targetTaxonName = link.get(TARGET_TAXON_NAME);
        String targetTaxonId = link.get(TARGET_TAXON_ID);
        if ((StringUtils.isNotBlank(sourceTaxonName) || StringUtils.isNotBlank(sourceTaxonId))
                && (StringUtils.isNotBlank(targetTaxonName) || StringUtils.isNotBlank(targetTaxonId))) {
            String interactionTypeId = link.get(INTERACTION_TYPE_ID);
            InteractType type = InteractType.typeOf(interactionTypeId);

            Study study = nodeFactory.getOrCreateStudy(studyFromLink(link));
            if (type == null) {
                final String msg = "unsupported interaction type id [" + interactionTypeId + "]";
                study.appendLogMessage(msg, Level.WARNING);
            } else {
                Specimen source = nodeFactory.createSpecimen(study, new TaxonImpl(sourceTaxonName, sourceTaxonId));
                setBasisOfRecordIfAvailable(link, source);
                setDateTimeIfAvailable(link, source);
                Specimen target = nodeFactory.createSpecimen(study, new TaxonImpl(targetTaxonName, targetTaxonId));
                setBasisOfRecordIfAvailable(link, target);
                setDateTimeIfAvailable(link, target);
                source.interactsWith(target, type, getOrCreateLocation(study, link));
            }
        }
    }

    private StudyImpl studyFromLink(Map<String, String> link) {
        String referenceCitation = link.get(REFERENCE_CITATION);
        StudyImpl study1 = new StudyImpl(link.get(REFERENCE_ID),
                link.get(STUDY_SOURCE_CITATION),
                link.get(REFERENCE_DOI),
                referenceCitation);

        final String referenceUrl = link.get(REFERENCE_URL);
        if (StringUtils.isBlank(study1.getExternalId()) && StringUtils.isNotBlank(referenceUrl)) {
            study1.setExternalId(referenceUrl);
        }

        return study1;
    }

    private void setDateTimeIfAvailable(Map<String, String> link, Specimen target) throws StudyImporterException {
        final String eventDate = link.get(StudyImporterForMetaTable.EVENT_DATE);
        if (StringUtils.isNotBlank(eventDate)) {
            try {
                final DateTime dateTime = ISODateTimeFormat.dateTimeParser().parseDateTime(eventDate);
                nodeFactory.setUnixEpochProperty(target, dateTime.toDate());
            } catch (IllegalArgumentException ex) {
                throw new StudyImporterException("invalid date string [" + eventDate + "]", ex);
            } catch (NodeFactoryException e) {
                throw new StudyImporterException("failed to set time for [" + eventDate + "]", e);
            }

        }

    }

    private void setBasisOfRecordIfAvailable(Map<String, String> link, Specimen specimen) {
        final String basisOfRecordName = link.get(BASIS_OF_RECORD_NAME);
        final String basisOfRecordId = link.get(BASIS_OF_RECORD_ID);
        if (StringUtils.isNotBlank(basisOfRecordName) || StringUtils.isNotBlank(basisOfRecordId)) {
            specimen.setBasisOfRecord(new Term(basisOfRecordId, basisOfRecordName));
        }
    }

    private Location getOrCreateLocation(Study study, Map<String, String> link) throws IOException, NodeFactoryException {
        LatLng centroid = null;
        String[] latitudes = {DECIMAL_LATITUDE, StudyImporterForMetaTable.LATITUDE};
        String latitude = getFirstValueForTerms(link, latitudes);

        String[] longitudes = {DECIMAL_LONGITUDE, StudyImporterForMetaTable.LONGITUDE};
        String longitude = getFirstValueForTerms(link, longitudes);

        if (StringUtils.isNotBlank(latitude) && StringUtils.isNotBlank(longitude)) {
            try {
                centroid = LocationUtil.parseLatLng(latitude, longitude);
            } catch (InvalidLocationException e) {
                getLogger().warn(study, "found invalid location: [" + e.getMessage() + "]");
            }
        }
        if (centroid == null) {
            String localityId = link.get(LOCALITY_ID);
            if (StringUtils.isNotBlank(localityId)) {
                centroid = getGeoNamesService().findLatLng(localityId);
            }
        }
        return centroid == null ? null : nodeFactory.getOrCreateLocation(centroid.getLat(), centroid.getLng(), null);
    }

    private String getFirstValueForTerms(Map<String, String> link, String[] latitudes) {
        String latitude = null;
        for (String latitudeTerm : latitudes) {
            if (StringUtils.isBlank(latitude)) {
                latitude = link.get(latitudeTerm);
            }
        }
        return latitude;
    }

    public GeoNamesService getGeoNamesService() {
        return geoNamesService;
    }

    public ImportLogger getLogger() {
        return logger;
    }


}