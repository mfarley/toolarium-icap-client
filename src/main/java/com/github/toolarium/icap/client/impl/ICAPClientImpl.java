/*
 * ICAPClientImpl.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.icap.client.impl;

import com.github.toolarium.icap.client.ICAPClient;
import com.github.toolarium.icap.client.ICAPConnectionManager;
import com.github.toolarium.icap.client.dto.*;
import com.github.toolarium.icap.client.exception.ContentBlockedException;
import com.github.toolarium.icap.client.exception.UnknownIOException;
import com.github.toolarium.icap.client.impl.dto.ICAPRemoteServiceConfigurationImpl;
import com.github.toolarium.icap.client.util.ICAPClientUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;


/**
 * Implements an ICAP client.
 *
 * @author Patrick Meier
 */
public class ICAPClientImpl implements ICAPClient {
    private static final Logger LOG = LoggerFactory.getLogger(ICAPClientImpl.class);
    private static final String NEWLINE = "\r\n";
    private static final String ICAP_END_SEPARATOR = NEWLINE + NEWLINE;
    private static final String HTTP_END_SEPARATOR = "0" + ICAP_END_SEPARATOR;

    private ICAPConnectionManager connectionManager;
    private ICAPServiceInformation serviceInformation;
    private ICAPRemoteServiceConfiguration remoteServiceConfiguration;
    private int bufferSize = 8192;
    private String messageDigestAlgorithm = "SHA-256";
    private boolean supportCompareVerifyIdenticalContent;


    /**
     * Constructor for ICAPClientImpl
     *
     * @param serviceInformation the service information
     * @param remoteServiceConfiguration the remote service configuration
     * @param connectionManager the connection manager
     */
    public ICAPClientImpl(ICAPConnectionManager connectionManager, ICAPServiceInformation serviceInformation, ICAPRemoteServiceConfiguration remoteServiceConfiguration) {
        this.connectionManager = connectionManager;
        this.serviceInformation = serviceInformation;
        this.remoteServiceConfiguration = remoteServiceConfiguration;
        this.supportCompareVerifyIdenticalContent = false;
    }


    /**
     * @see ICAPClient#supportCompareVerifyIdenticalContent(boolean)
     */
    @Override
    public ICAPClient supportCompareVerifyIdenticalContent(boolean supportCompareVerifyIdenticalContent) {
        this.supportCompareVerifyIdenticalContent = supportCompareVerifyIdenticalContent;
        return this;
    }


    /**
     * @see ICAPClient#options()
     */
    @Override
    public ICAPRemoteServiceConfiguration options() throws IOException {
        return options(new ICAPRequestInformation());
    }


    /**
     * @see ICAPClient#options()
     */
    @Override
    public ICAPRemoteServiceConfiguration options(final ICAPRequestInformation requestInformation) throws IOException {
        if (remoteServiceConfiguration != null) {
            return remoteServiceConfiguration;
        }

        validateRequestInformation(requestInformation);
        final String requestIdentifier = createRequestIdentifier("options", null);
        try (ICAPSocket icapSocket = new ICAPSocket(connectionManager, requestIdentifier, serviceInformation.getHostName(), serviceInformation.getServicePort(),
                                                    serviceInformation.getServiceName(), serviceInformation.isSecureConnection(), requestInformation.getMaxConnectionTimeout(), requestInformation.getMaxReadTimeout())) {
            icapSocket.write("OPTIONS icap://" + serviceInformation.getHostName() + ":" + serviceInformation.getServicePort() + "/" + serviceInformation.getServiceName() + " ICAP/" + requestInformation.getApiVersion() + NEWLINE
                             + "Host: " + serviceInformation.getHostName() + NEWLINE
                             + "User-Agent: " + requestInformation.getUserAgent() + NEWLINE
                             + createCustomHeaders(requestInformation)
                             + ICAPConstants.HEADER_KEY_ENCAPSULATED + ": null-body=0" + NEWLINE + NEWLINE);
            icapSocket.flush();

            ICAPHeaderInformation icapHeaderInformation = icapSocket.readICAPResponse(requestIdentifier, ICAP_END_SEPARATOR, bufferSize);
            if (icapHeaderInformation.getStatus() != 200) {
                throw new IOException("Could not resolve options!");
            }

            int serverPreviewSize = 1024;
            if (icapHeaderInformation.containsHeader(ICAPConstants.HEADER_KEY_PREVIEW)
                    && icapHeaderInformation.getHeaderValues(ICAPConstants.HEADER_KEY_PREVIEW) != null
                    && icapHeaderInformation.getHeaderValues(ICAPConstants.HEADER_KEY_PREVIEW).size() > 0) {
                try {
                    serverPreviewSize = Integer.parseInt(icapHeaderInformation.getHeaderValues(ICAPConstants.HEADER_KEY_PREVIEW).get(0));
                    if (LOG.isDebugEnabled()) {
                        LOG.debug(requestIdentifier + "Server preview size: " + serverPreviewSize);
                    }
                } catch (NumberFormatException e) {
                    LOG.warn(requestIdentifier + "Could not parse server preview size [" + icapHeaderInformation.getHeaderValues(ICAPConstants.HEADER_KEY_PREVIEW).get(0) + "]: " + e.getMessage());
                }
            }

            boolean serverAllow204 = false;
            if (icapHeaderInformation.containsHeader(ICAPConstants.HEADER_KEY_ALLOW)
                    && icapHeaderInformation.getHeaderValues(ICAPConstants.HEADER_KEY_ALLOW) != null
                    && icapHeaderInformation.getHeaderValues(ICAPConstants.HEADER_KEY_ALLOW).size() > 0) {
                serverAllow204 = Boolean.valueOf(icapHeaderInformation.getHeaderValues(ICAPConstants.HEADER_KEY_ALLOW).get(0).equalsIgnoreCase("204"));
            }

            LOG.info(requestIdentifier + "Valid service ["
                     + icapHeaderInformation.getStatus() + "/" + icapHeaderInformation.getMessage() + "], "
                     + "allow 204: " + serverAllow204 + ", "
                     + "available methods: " + icapHeaderInformation.getHeaderValues("Methods"));

            int i = 0;
            ICAPMode[] result = new ICAPMode[icapHeaderInformation.getHeaderValues("Methods").size()];
            for (String method : icapHeaderInformation.getHeaderValues("Methods")) {
                result[i++] = ICAPMode.valueOf(method.trim());
            }

            remoteServiceConfiguration = new ICAPRemoteServiceConfigurationImpl(Instant.now(), result, serverPreviewSize, serverAllow204, icapHeaderInformation.getHeaders());
            return remoteServiceConfiguration;
        } catch (IOException e) {
            remoteServiceConfiguration = null;
            throw e;
        }
    }


    /**
     * @see ICAPClient#validateResource(ICAPMode, ICAPResource)
     */
    @Override
    public ICAPHeaderInformation validateResource(final ICAPMode mode, final ICAPResource resource) throws IOException, ContentBlockedException {
        return validateResource(mode, new ICAPRequestInformation(), resource);
    }


    /**
     * @see ICAPClient#validateResource(ICAPMode, ICAPRequestInformation, ICAPResource)
     */
    @Override
    public ICAPHeaderInformation validateResource(final ICAPMode inputMode, final ICAPRequestInformation requestInformation, final ICAPResource resource) throws IOException, ContentBlockedException {
        validateRequestInformation(requestInformation);
        if (resource.getResourceLength() == 0) {
            return new ICAPHeaderInformation();
        }
        validateICAPResource(resource);

        ICAPMode icapMode = ICAPMode.REQMOD;
        if (inputMode != null) {
            icapMode = inputMode;
        }

        final String sourceRequest = requestInformation.prepareSourceRequest(resource);
        final String requestIdentifier = createRequestIdentifier(icapMode.name(), sourceRequest);
        LOG.info(requestIdentifier + "Validate resource (" + sourceRequest + ")");

        // validate the service availability
        if (remoteServiceConfiguration == null) {
            options(requestInformation);
        }

        // prepare preview size
        int previewSize = remoteServiceConfiguration.getServerPreviewSize();
        if (resource.getResourceLength() < previewSize) {
            previewSize = (int)resource.getResourceLength();
        }

        File resourceResponse = File.createTempFile(requestIdentifier, ".tmp");
        try (ICAPSocket icapSocket = new ICAPSocket(connectionManager, requestIdentifier, serviceInformation.getHostName(), serviceInformation.getServicePort(),
                                                    serviceInformation.getServiceName(), serviceInformation.isSecureConnection(), requestInformation.getMaxConnectionTimeout(), requestInformation.getMaxReadTimeout())) {
            ICAPHeaderInformation icapHeaderInformation = processResource(requestIdentifier, icapSocket, icapMode, requestInformation, resource, resourceResponse);
            icapHeaderInformation.getHeaders().remove(ICAPConstants.HEADER_KEY_X_ICAP_STATUSLINE);

            if (icapHeaderInformation.getStatus() == 200) {
                String threadInformation = "";

                for (Map.Entry<String, List<String>> e: icapHeaderInformation.getHeaders().entrySet()) {
                    if (e.getKey().toLowerCase().startsWith("x-")) {
                        threadInformation += "- " + e.getKey() + ": " + e.getValue() + "\n";
                    }
                }

                // verify if there is a thread is found taken from header
                if (hasThreadHeaderInformation(icapHeaderInformation)) {
                    String threadHeaderInformation = readThreadHeaderInformation(icapMode, icapHeaderInformation, resourceResponse);
                    String msg = "Threat found in resource (" + sourceRequest + ", http-status: " + icapHeaderInformation.getStatus() + "):\n" + threadInformation.trim();
                    LOG.info(requestIdentifier + msg);
                    throw new ContentBlockedException(msg, icapHeaderInformation, threadHeaderInformation);
                } else if (supportCompareVerifyIdenticalContent
                        && icapHeaderInformation.containsHeader(ICAPConstants.HEADER_KEY_X_IDENTICAL_CONTENT) && !icapHeaderInformation.getHeaderValues(ICAPConstants.HEADER_KEY_X_IDENTICAL_CONTENT).isEmpty()
                        && !Boolean.valueOf(icapHeaderInformation.getHeaderValues(ICAPConstants.HEADER_KEY_X_IDENTICAL_CONTENT).get(0))) {
                    String msg = "Not identical resource (" + sourceRequest + ", http-status: " + icapHeaderInformation.getStatus() + "):\n" + threadInformation.trim();
                    LOG.info(requestIdentifier + msg);
                    throw new ContentBlockedException(msg, icapHeaderInformation);
                }
            }

            LOG.info(requestIdentifier + "Valid resource (" + sourceRequest + ", http-status: " + icapHeaderInformation.getStatus() + ").");
            return icapHeaderInformation;
        } catch (IOException eio) {
            LOG.warn(requestIdentifier + "Could not access to ICAP server: " + eio.getMessage());
            throw eio;
        } finally {
            if (resourceResponse != null && resourceResponse.exists()) {
                resourceResponse.delete();
            }
        }
    }


    /**
     * Create custom headers
     *
     * @param requestInformation the ICAP request information
     * @return the customer headers
     */
    private String createCustomHeaders(final ICAPRequestInformation requestInformation) {
        if (requestInformation.getCustomHeaders() == null || requestInformation.getCustomHeaders().isEmpty()) {
            return "";
        }

        final StringBuilder headers = new StringBuilder();
        for (Map.Entry<String, String> e : requestInformation.getCustomHeaders().entrySet()) {
            final String key = e.getKey().trim();
            final String value = e.getValue().trim();

            if (key.equalsIgnoreCase("Host") || key.equalsIgnoreCase("Connection") || key.equalsIgnoreCase("User-Agent") || key.equalsIgnoreCase("Preview") || key.equalsIgnoreCase("Encapsulated") || key.equalsIgnoreCase("Allow")) {
                LOG.warn("Invalid customer header [" + key + "], it's not allowed, ignore!");
            } else if (!value.isEmpty()) {
                headers.append(key).append(": ").append(value).append(NEWLINE);
            }
        }

        return headers.toString();
    }


    /**
     * Check if there are thread header information
     *
     * @param icapHeaderInformation the ICAP header information
     * @return true if a thread was detected
     */
    private boolean hasThreadHeaderInformation(ICAPHeaderInformation icapHeaderInformation) {
        return icapHeaderInformation.containsHeader(ICAPConstants.HEADER_KEY_X_INFECTION_FOUND)
               || icapHeaderInformation.containsHeader(ICAPConstants.HEADER_KEY_X_VIOLATIONS_FOUND)
               || icapHeaderInformation.containsHeader(ICAPConstants.HEADER_KEY_X_BLOCKED)        // used by Sophos
               || icapHeaderInformation.containsHeader(ICAPConstants.HEADER_KEY_X_VIRUS_ID)       // used by Sophos, Kaspersky, Trenxd Micro, ESET, McAfee, C-ICAP
               || icapHeaderInformation.containsHeader(ICAPConstants.HEADER_KEY_X_VIRUS_NAME)     // used by McAfee
               || icapHeaderInformation.containsHeader(ICAPConstants.HEADER_KEY_X_BLOCK_REASON)   // used by McAfee
               || icapHeaderInformation.containsHeader(ICAPConstants.HEADER_KEY_X_BLOCK_RESULT);  // used by McAfee
    }


    /**
     * Read the thread reason
     *
     * @param icapMode the icap mode
     * @param resourceResponse the resource response
     * @param icapHeaderInformation the ICAP header information
     * @return the thread content information
     */
    private String readThreadHeaderInformation(ICAPMode icapMode, ICAPHeaderInformation icapHeaderInformation, File resourceResponse) {
        String threadHeaderInformation = null;

        if (icapHeaderInformation.containsHeader(ICAPConstants.HEADER_KEY_ENCAPSULATED) && !icapHeaderInformation.getHeaderValues(ICAPConstants.HEADER_KEY_ENCAPSULATED).isEmpty()
            && resourceResponse != null && resourceResponse.length() > 0 && resourceResponse.exists()) {
            for (int i = 0; i < icapHeaderInformation.getHeaderValues(ICAPConstants.HEADER_KEY_ENCAPSULATED).size(); i++) {
                String entry = icapHeaderInformation.getHeaderValues(ICAPConstants.HEADER_KEY_ENCAPSULATED).get(i);
                String[] split = entry.split("=");
                if (split.length > 1 && split[0].trim().equalsIgnoreCase(icapMode.getTag() + "-body")) {
                    try {
                        threadHeaderInformation = new String(ICAPClientUtil.getInstance().readFile(resourceResponse), Charset.forName("UTF-8")).trim();
                    } catch (IOException e) {
                        LOG.warn("Could not read resource response: " + e.getMessage(), e);
                    }

                    break;
                }
            }
        }

        if ((threadHeaderInformation == null || threadHeaderInformation.isBlank()) && icapHeaderInformation.containsHeader(ICAPConstants.HEADER_KEY_X_BLOCKED)) {
            // used by Sophos
            threadHeaderInformation = "" + icapHeaderInformation.getHeaderValues(ICAPConstants.HEADER_KEY_X_BLOCKED);
        }

        if ((threadHeaderInformation == null || threadHeaderInformation.isBlank()) && icapHeaderInformation.containsHeader(ICAPConstants.HEADER_KEY_X_VIRUS_ID)) {
            // used by Sophos, Kaspersky, Trenxd Micro, ESET, McAfee, C-ICAP
            threadHeaderInformation = "" + icapHeaderInformation.getHeaderValues(ICAPConstants.HEADER_KEY_X_VIRUS_ID);
        }

        if ((threadHeaderInformation == null || threadHeaderInformation.isBlank()) && icapHeaderInformation.containsHeader(ICAPConstants.HEADER_KEY_X_VIRUS_NAME)) {
            // used by McAfee
            threadHeaderInformation = "" + icapHeaderInformation.getHeaderValues(ICAPConstants.HEADER_KEY_X_VIRUS_NAME);
        }

        if (threadHeaderInformation == null || threadHeaderInformation.isBlank()) {
            threadHeaderInformation = "n/a";
        }

        return threadHeaderInformation;
    }


    /**
     * Validate resource
     *
     * @param resource the resource
     * @throws IOException In case of an invalid resource
     */
    protected void validateICAPResource(final ICAPResource resource) throws IOException {
        if (resource == null
                || resource.getResourceName() == null || resource.getResourceName().isBlank()
                || resource.getResourceBody() == null
                || resource.getResourceLength() <= 0) {
            throw new IOException("Invalid input resource!");
        }
    }


    /**
     * Process a resource
     *
     * @param requestIdentifier the request identifier
     * @param icapSocket The icap socket
     * @param icapMode the icap mode
     * @param requestInformation the ICAP request information
     * @param resource the ICAP resource
     * @param resourceResponse the resource response
     * @return the ICAP header information
     * @throws IOException In case of an I/O error
     * @throws UnknownIOException In case of an unknown I/O error
     * @throws ContentBlockedException In case the content is blocked
     */
    protected ICAPHeaderInformation processResource(final String requestIdentifier,
                                                    final ICAPSocket icapSocket,
                                                    final ICAPMode icapMode,
                                                    final ICAPRequestInformation requestInformation,
                                                    final ICAPResource resource,
                                                    final File resourceResponse) throws IOException, ContentBlockedException {

        // first part of header
        String httpMethod = "GET";
        String header = httpMethod + " /" + URLEncoder.encode(resource.getResourceName().trim(), StandardCharsets.UTF_8.name()) + " HTTP/1.1" + NEWLINE
                        + "Host: " + requestInformation.getRequestSource() + NEWLINE + NEWLINE;
        String body = header + "HTTP/1.1 200 OK" + NEWLINE + ICAPConstants.HEADER_KEY_TRANSFER_ENCODING + ": chunked" + NEWLINE
                      + ICAPConstants.HEADER_KEY_CONTENT_LENGTH + ": " + resource.getResourceLength() + NEWLINE + NEWLINE;
        String reqHdr = "";
        String bodyHdr = "";
        if (ICAPMode.RESPMOD.equals(icapMode)) {
            reqHdr = "req-hdr=0, ";
            bodyHdr = icapMode.getTag() + "-hdr=" + header.length() + ", ";
        } else {
            reqHdr = "req-hdr=0, ";
        }

        int previewSize = remoteServiceConfiguration.getServerPreviewSize();
        if (resource.getResourceLength() < previewSize) {
            previewSize = (int) resource.getResourceLength();
        }

        String requestBuffer = "" + icapMode.name() + " icap://" + serviceInformation.getHostName() + ":" + serviceInformation.getServicePort() + "/" + serviceInformation.getServiceName() + " ICAP/" + requestInformation.getApiVersion() + NEWLINE
                             + "Host: " + serviceInformation.getHostName() + NEWLINE
                             + "Connection:  close" + NEWLINE
                             + "User-Agent: " + requestInformation.getUserAgent() + NEWLINE
                             + createCustomHeaders(requestInformation)
                             + supportAllow204(requestIdentifier, requestInformation.isAllow204())
                             + "Preview: " + previewSize + NEWLINE
                             + "Encapsulated: " + reqHdr + bodyHdr + icapMode.getTag() + "-body=" + body.length() + NEWLINE + NEWLINE
                             + body
                             + Integer.toHexString(previewSize) + NEWLINE;
        icapSocket.write(requestBuffer);

        // sending preview or, if smaller than previewSize, the whole file.
        byte[] chunk = new byte[previewSize];

        MessageDigest inputMessageDigest = ICAPClientUtil.getInstance().createMessageDigest(messageDigestAlgorithm);
        DigestInputStream inputstream = new DigestInputStream(resource.getResourceBody(), inputMessageDigest);
        int readBytes = inputstream.read(chunk);
        long totalReadBytes = readBytes;
        icapSocket.write(chunk, 0, readBytes);
        icapSocket.write(NEWLINE);
        if (resource.getResourceLength() <= previewSize) {
            icapSocket.write("0; ieof" + ICAP_END_SEPARATOR);
            icapSocket.flush();
        } else if (previewSize != 0) {
            icapSocket.write(HTTP_END_SEPARATOR);
            icapSocket.flush();
        }

        // parse the response; it might not be "100 continue" if fileSize < previewSize, then this is actually the respond otherwise it is a "go" for the rest of the file.
        if (resource.getResourceLength() > previewSize) {
            ICAPHeaderInformation icapHeaderInformation = icapSocket.readICAPResponse(requestIdentifier, ICAP_END_SEPARATOR, bufferSize);
            switch (icapHeaderInformation.getStatus()) {
                case 100: break; // continue transfer
                case 200: return icapHeaderInformation;
                case 204: return icapHeaderInformation;
                case 404: throw new IOException("404: ICAP Service not found");
                default: throw new UnknownIOException("Server returned unknown status code:" + icapHeaderInformation.getStatus(), icapHeaderInformation);
            }
        }

        // sending remaining part of file
        if (resource.getResourceLength() > previewSize) {
            byte[] buffer = new byte[bufferSize];
            readBytes = -1;
            while ((readBytes = inputstream.read(buffer)) != -1) {
                totalReadBytes += readBytes;
                if (LOG.isDebugEnabled()) {
                    LOG.debug(requestIdentifier + "Send next block of " + readBytes + " bytes (total sent: " + totalReadBytes + " bytes)...");
                }
                icapSocket.write((Integer.toHexString(readBytes) + NEWLINE));
                icapSocket.write(buffer, 0, readBytes);
                icapSocket.write(NEWLINE);
            }

            // closing resource transfer.
            icapSocket.write(HTTP_END_SEPARATOR);
            icapSocket.flush();
        }

        ICAPHeaderInformation icapHeaderInformation = icapSocket.readICAPResponse(requestIdentifier, ICAP_END_SEPARATOR, bufferSize);
        if (icapHeaderInformation.getStatus() == 204) { // unmodified
            return icapHeaderInformation;
        }

        if (icapHeaderInformation.getStatus() == 200) { // OK - The ICAP status is ok, but the encapsulated HTTP status will likely be different
            if ((requestInformation.isAllow204() != null && !requestInformation.isAllow204()) && ICAPMode.REQMOD.equals(icapMode)) {
                return icapHeaderInformation;
            }

            if (!icapHeaderInformation.containsHeader(ICAPConstants.HEADER_KEY_ENCAPSULATED)) {
                LOG.warn("Missing " + ICAPConstants.HEADER_KEY_ENCAPSULATED + " information!");
                return icapHeaderInformation;
            }

            boolean couldProcessFullContent;
            MessageDigest outputMessageDigest = ICAPClientUtil.getInstance().createMessageDigest(messageDigestAlgorithm);
            try (DigestOutputStream outputstream = new DigestOutputStream(new BufferedOutputStream(new FileOutputStream(resourceResponse)), outputMessageDigest)) {
                //int parsedResult = (int) Long.parseLong(hex, 16);
                couldProcessFullContent = (icapSocket.processContent(outputstream) >= 0);
                outputstream.flush();
                outputstream.close();
            }
            icapSocket.flush();
            icapSocket.close();

            String inputMsg = ICAPClientUtil.getInstance().messageDigestToString(messageDigestAlgorithm, inputMessageDigest);
            icapHeaderInformation.getHeaders().put(ICAPConstants.HEADER_KEY_X_REQUEST_MESSAGE_DIGEST, Arrays.asList(inputMsg));
            String outputMsg = ICAPClientUtil.getInstance().messageDigestToString(messageDigestAlgorithm, outputMessageDigest);
            icapHeaderInformation.getHeaders().put(ICAPConstants.HEADER_KEY_X_RESPONSE_MESSAGE_DIGEST, Arrays.asList(outputMsg));

            if (LOG.isDebugEnabled()) {
                LOG.debug(requestIdentifier + "Resource length: " + resource.getResourceLength() + ", Response length: " + resourceResponse.length() + "?");
            }

            if (supportCompareVerifyIdenticalContent) {
                boolean identicalContent = couldProcessFullContent && resource.getResourceLength() == resourceResponse.length() && inputMsg.equals(outputMsg);
                if (identicalContent) {
                    icapHeaderInformation.getHeaders().put(ICAPConstants.HEADER_KEY_X_IDENTICAL_CONTENT, Arrays.asList("" + identicalContent));
                    if (LOG.isDebugEnabled()) {
                        LOG.debug(requestIdentifier + "Input and output are equal -> allow, it's a valid response!");
                    }
                }
            }

            return icapHeaderInformation;
        }

        throw new UnknownIOException("Unrecognized or no status code in response header: " + icapHeaderInformation.getStatus() + "!", icapHeaderInformation);
    }


    /**
     * Check allow 204 support
     *
     * @param requestIdentifier the equest identifier
     * @param isAllow204 the request information
     * @return the request string
     */
    protected String supportAllow204(final String requestIdentifier, final Boolean isAllow204) {

        String serverReason = "suppported by the icap-server";
        if (!remoteServiceConfiguration.isServerAllow204()) {
            serverReason = "not " + serverReason;
        }

        String requestReason = "requested";
        if (isAllow204 == null) {
            requestReason = "auto select";
        } else if (!isAllow204.booleanValue()) {
            requestReason = "not " + requestReason;
        }

        String selectAllow204Reason = "Not use allow 204";
        String allow204Request = "";
        if (remoteServiceConfiguration.isServerAllow204() && (isAllow204 == null || isAllow204.booleanValue())) {
            selectAllow204Reason = "Use allow 204";
            allow204Request = "Allow: 204" + NEWLINE;
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug(requestIdentifier + selectAllow204Reason + ": " + requestReason + " (" + serverReason + ")");
        }
        return allow204Request;
    }


    /**
     * Create request identifier
     *
     * @param mode the mode
     * @param sourceRequest the source request
     * @return the request identifier
     */
    protected String createRequestIdentifier(final String mode, final String sourceRequest) {
        return Integer.toHexString(("" + Instant.now() + "|" + mode + "|" + sourceRequest).hashCode()).toUpperCase() + " - ";
    }


    /**
     * Validate the request information
     *
     * @param requestInformation the request information
     * @throws IOException In case of an invalid request information
     */
    protected void validateRequestInformation(final ICAPRequestInformation requestInformation) throws IOException {
        if (requestInformation == null) {
            throw new IOException("Invalid request information!");
        }
    }
}
