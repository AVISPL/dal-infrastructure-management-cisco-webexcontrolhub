/*
 * Copyright (c) 2024 AVI-SPL, Inc. All Rights Reserved.
 */
package com.avispl.symphony.dal.communicator;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Ping mode - ICMP vs TCP
 * @author Maksym Rossiitsev
 * @since 1.0.0
 */
public enum PingMode {
    ICMP("ICMP"), TCP("TCP");
    private static final Log logger = LogFactory.getLog(PingMode.class);

    private String mode;

    PingMode(String mode) {
        this.mode = mode;
    }

    public static PingMode ofString(String mode) {
        if (logger.isDebugEnabled()) {
            logger.debug("Requested PING mode: " + mode);
        }
        Optional<PingMode> selectedAuthMode = Arrays.stream(PingMode.values()).filter(authorizationMode -> Objects.equals(mode, authorizationMode.mode)).findFirst();
        return selectedAuthMode.orElse(PingMode.ICMP);
    }
}
