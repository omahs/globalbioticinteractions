package org.globalbioticinteractions.doi;

import java.io.Serializable;
import java.net.URI;

public final class DOI implements Serializable {

    private final static String DIRECTORY_INDICATOR_PREFIX = "10.";
    private final String registrantCode;
    private final String suffix;

    public DOI(String registrantCode, String suffix) {
        this.registrantCode = registrantCode;
        this.suffix = suffix;
    }

    public String getDOI() {
        return String.format("%s%s/%s", DIRECTORY_INDICATOR_PREFIX, registrantCode, suffix);
    }

    /**
     * @return printable string as defined in http://www.doi.org/doi_handbook/2_Numbering.html#2.6.1
     */

    public String getPrintableDOI() {
        return String.format("doi:%s", getDOI());
    }

    public URI toURI() {
        return DOIUtil.URIForDoi(this);
    }

    public URI toURI(URI resolver) {
        return DOIUtil.URIForDoi(this, resolver);
    }

    public static DOI create(String doiString) throws MalformedDOIException {
        String s = DOIUtil.stripDOIPrefix(doiString);
        return getDOI(s);
    }

    public static DOI create(URI doiURI) throws MalformedDOIException {
        String path = doiURI == null ? "" : doiURI.getPath();
        int i = path.indexOf('/');
        if (i != 0) {
            throw new MalformedDOIException("path [" + path + "] does not start with [/]");
        }
        return getDOI(path.substring(1));
    }

    private static DOI getDOI(String doiCandidate) throws MalformedDOIException {
        if (!doiCandidate.startsWith(DIRECTORY_INDICATOR_PREFIX)) {
            throw new MalformedDOIException("expected directory indicator [10.] in [" + doiCandidate + "]");
        }

        int s = doiCandidate.indexOf('/');
        if (s < DIRECTORY_INDICATOR_PREFIX.length()) {
            throw new MalformedDOIException("missing registrant code in [" + doiCandidate + "]");
        }
        if (s < DIRECTORY_INDICATOR_PREFIX.length() + 1) {
            throw new MalformedDOIException("missing suffix in [" + doiCandidate + "]");
        }
        String registrantCode = doiCandidate.substring(DIRECTORY_INDICATOR_PREFIX.length(), s);
        String suffix = doiCandidate.substring(s + 1);
        return new DOI(registrantCode, suffix);
    }

    @Override
    public String toString() {
        return getDOI();
    }

    @Override
    public boolean equals(Object obj) {
        return (obj != null && obj instanceof DOI)
                && sameAs((DOI) obj);
    }

    private boolean sameAs(DOI other) {
        return other != null && getDOI().equalsIgnoreCase(other.getDOI());
    }

    public static boolean isDoiPrefix(String idPrefix) {
        String prefixLower = idPrefix == null ? "" : idPrefix.toLowerCase();
        return DOIUtil.PRINTABLE_DOI_PREFIX.contains(prefixLower) || DOIUtil.DOI_URLS.contains(prefixLower);
    }

}
