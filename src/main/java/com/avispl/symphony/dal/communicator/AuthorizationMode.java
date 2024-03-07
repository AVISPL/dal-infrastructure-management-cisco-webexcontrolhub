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
 * Authorization mode - Bot (password is accessToken) vs Integration (accessToken is generated)
 * @author Maksym Rossiitsev
 * @since 1.0.0
 */
public enum AuthorizationMode {
    BOT("Bot"), INTEGRATION("Integration");
    private static final Log logger = LogFactory.getLog(AuthorizationMode.class);

    private String mode;

    AuthorizationMode(String mode) {
        this.mode = mode;
    }

    /**
     * Retrieve {@link AuthorizationMode} instance based on the text value of the mode
     *
     * @param mode name of the mode to retrieve
     * @return instance of {@link AuthorizationMode}
     * */
    public static AuthorizationMode ofString(String mode) {
        if (logger.isDebugEnabled()) {
            logger.debug("Requested authorization mode: " + mode);
        }
        Optional<AuthorizationMode> selectedAuthMode = Arrays.stream(AuthorizationMode.values()).filter(authorizationMode -> Objects.equals(mode, authorizationMode.mode)).findFirst();
        return selectedAuthMode.orElse(AuthorizationMode.INTEGRATION);
    }
}
