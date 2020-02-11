package org.globalbioticinteractions.cache;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.eol.globi.util.DateUtil;
import org.eol.globi.util.InputStreamFactory;
import org.eol.globi.util.ResourceUtil;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class CachePullThrough implements Cache {
    private final static Log LOG = LogFactory.getLog(CachePullThrough.class);
    private final String namespace;
    private final String cachePath;
    private final InputStreamFactory inputStreamFactory;

    public CachePullThrough(String namespace, String cachePath) {
        this(namespace, cachePath, inStream -> inStream);
    }

    public CachePullThrough(String namespace, String cachePath, InputStreamFactory factory) {
        this.namespace = namespace;
        this.cachePath = cachePath;
        this.inputStreamFactory = factory;
    }

    static ContentProvenance cache(URI sourceURI, File cacheDir) throws IOException {
        return cache(sourceURI, cacheDir, inStream -> inStream);
    }

    public static ContentProvenance cache(URI sourceURI, File cacheDir, InputStreamFactory factory) throws IOException {
        String msg = "caching [" + sourceURI + "]";
        LOG.info(msg + " started...");
        InputStream inputStream = ResourceUtil.asInputStream(sourceURI.toString(), factory);
        ContentProvenance contentProvenance = cacheStream(inputStream, cacheDir);
        LOG.info(msg + " cached at [" + contentProvenance.getLocalURI().toString() + "]...");
        LOG.info(msg + " complete.");
        return contentProvenance;
    }

    public static ContentProvenance cacheStream(InputStream inputStream, File cacheDir) throws IOException {
        File destinationFile = null;

        try (InputStream sourceStream = inputStream) {
            destinationFile = File.createTempFile("archive", "tmp", cacheDir);
            try {
                OutputStream os = FileUtils.openOutputStream(destinationFile);
                String sha256 = calculateContentHash(sourceStream, os);
                File destFile = new File(cacheDir, sha256);
                if (!destFile.exists()) {
                    FileUtils.moveFile(destinationFile, destFile);
                }
                return new ContentProvenance(null, null, destFile.toURI(), sha256, DateUtil.nowDateString());
            } catch (NoSuchAlgorithmException e) {
                throw new IOException("failed to access hash/digest algorithm", e);
            }
        } finally {
            if (destinationFile != null && destinationFile.exists()) {
                FileUtils.deleteQuietly(destinationFile);
            }
        }
    }

    public static String calculateContentHash(InputStream sourceStream, OutputStream os) throws NoSuchAlgorithmException, IOException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (DigestInputStream digestInputStream = new DigestInputStream(sourceStream, md)) {
            IOUtils.copy(digestInputStream, os);
        }
        return String.format("%064x", new java.math.BigInteger(1, md.digest()));
    }

    @Override
    public URI getLocalURI(URI resourceName) throws IOException {
        File cacheDir = CacheUtil.getCacheDirForNamespace(cachePath, namespace);
        ContentProvenance localResourceLocation = cache(resourceName, cacheDir, getInputStreamFactory());
        ContentProvenance contentProvenanceWithNamespace = new ContentProvenance(namespace, resourceName, localResourceLocation.getLocalURI(), localResourceLocation.getSha256(), localResourceLocation.getAccessedAt());
        ProvenanceLog.appendProvenanceLog(cacheDir, contentProvenanceWithNamespace);
        return contentProvenanceWithNamespace.getLocalURI();
    }

    @Override
    public ContentProvenance provenanceOf(URI resourceURI) {
        return null;
    }

    @Override
    public InputStream retrieve(URI resourceURI) throws IOException {
        URI resourceURI1 = getLocalURI(resourceURI);
        return resourceURI1 == null ? null : ResourceUtil.asInputStream(resourceURI1.toString(), getInputStreamFactory());
    }

    private InputStreamFactory getInputStreamFactory() {
        return inputStreamFactory;
    }
}

