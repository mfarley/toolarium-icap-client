/*
 * UnknownIOException.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.icap.client.exception;

import com.github.toolarium.icap.client.dto.ICAPHeaderInformation;

import java.io.IOException;

/**
 * The unknown IO exception.
 *
 * @author Michael Farley
 */
public class UnknownIOException extends IOException {
    private static final long serialVersionUID = 5711527857339125277L;
    private final ICAPHeaderInformation icapHeaderInformation;


    /**
     * Constructor for ICAPException
     *
     * @param message the message
     * @param icapHeaderInformation the ICAP header information
     */
    public UnknownIOException(String message, ICAPHeaderInformation icapHeaderInformation) {
        super(message);
        this.icapHeaderInformation = icapHeaderInformation;
    }


    /**
     * Get the ICAP response
     *
     * @return the ICAP response
     */
    public ICAPHeaderInformation getICAPHeaderInformation() {
        return icapHeaderInformation;
    }
}
