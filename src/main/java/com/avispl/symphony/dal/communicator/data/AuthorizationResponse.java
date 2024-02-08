/*
 * Copyright (c) 2024 AVI-SPL, Inc. All Rights Reserved.
 */
package com.avispl.symphony.dal.communicator.data;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Authorization response domain object
 * @author Maksym Rossiitsev
 * @since 1.0.0
 */
public class AuthorizationResponse {
    @JsonProperty("access_token")
    private String accessToken;
    @JsonProperty("expires_in")
    private Integer expiresIn;
    @JsonProperty("refresh_token")
    private String refreshToken;
    @JsonProperty("refresh_token_expires_in")
    private Integer refreshTokenExpiresIn;
    @JsonProperty("token_type")
    private String tokenType;
    @JsonProperty("scope")
    private String scope;

    /**
     * Retrieves {@link #accessToken}
     *
     * @return value of {@link #accessToken}
     */
    public String getAccessToken() {
        return accessToken;
    }

    /**
     * Sets {@link #accessToken} value
     *
     * @param accessToken new value of {@link #accessToken}
     */
    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    /**
     * Retrieves {@link #expiresIn}
     *
     * @return value of {@link #expiresIn}
     */
    public Integer getExpiresIn() {
        return expiresIn;
    }

    /**
     * Sets {@link #expiresIn} value
     *
     * @param expiresIn new value of {@link #expiresIn}
     */
    public void setExpiresIn(Integer expiresIn) {
        this.expiresIn = expiresIn;
    }

    /**
     * Retrieves {@link #refreshToken}
     *
     * @return value of {@link #refreshToken}
     */
    public String getRefreshToken() {
        return refreshToken;
    }

    /**
     * Sets {@link #refreshToken} value
     *
     * @param refreshToken new value of {@link #refreshToken}
     */
    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }

    /**
     * Retrieves {@link #refreshTokenExpiresIn}
     *
     * @return value of {@link #refreshTokenExpiresIn}
     */
    public Integer getRefreshTokenExpiresIn() {
        return refreshTokenExpiresIn;
    }

    /**
     * Sets {@link #refreshTokenExpiresIn} value
     *
     * @param refreshTokenExpiresIn new value of {@link #refreshTokenExpiresIn}
     */
    public void setRefreshTokenExpiresIn(Integer refreshTokenExpiresIn) {
        this.refreshTokenExpiresIn = refreshTokenExpiresIn;
    }

    /**
     * Retrieves {@link #tokenType}
     *
     * @return value of {@link #tokenType}
     */
    public String getTokenType() {
        return tokenType;
    }

    /**
     * Sets {@link #tokenType} value
     *
     * @param tokenType new value of {@link #tokenType}
     */
    public void setTokenType(String tokenType) {
        this.tokenType = tokenType;
    }

    /**
     * Retrieves {@link #scope}
     *
     * @return value of {@link #scope}
     */
    public String getScope() {
        return scope;
    }

    /**
     * Sets {@link #scope} value
     *
     * @param scope new value of {@link #scope}
     */
    public void setScope(String scope) {
        this.scope = scope;
    }
}
