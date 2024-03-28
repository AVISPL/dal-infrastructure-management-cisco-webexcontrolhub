/*
 * Copyright (c) 2024 AVI-SPL, Inc. All Rights Reserved.
 */
package com.avispl.symphony.dal.communicator;

import java.util.Arrays;
import java.util.List;

/**
 * Adapter constants storage
 * @author Maksym Rossiitsev
 * @since 1.0.0
 */
public interface Constants {

    /**
     * URL constants
     * @author Maksym Rossiitsev
     * @since 1.0.0
     * */
    interface URL {
        String DEVICES_URL = "devices";
        String DEVICE_URL = "devices/";
        String ACCESS_TOKEN_URL = "access_token";
        String DEVICE_CONFIGURATIONS = "deviceConfigurations?deviceId=";

        /**
         * POST https://webexapis.com/v1/xapi/command/{commandKey}
         * The command key is not case sensitive.
         * The path segments are separated by dots (".").
         *
         * {
         *     "deviceId": "...",
         *     "arguments": {
         *         "argOne": "string",
         *         "argTwo": "literal",
         *         "argThree": integer value,
         *         "argFour": true or false,
         *         "argFive": [1, 2, 3]
         *     }
         * }
         * */
        String DEVICE_CONTROL = "xapi/command/"; // if xApi is supported
        String XAPI_STATUS = "xapi/status?deviceId=%s&name=*";
        String DEVICE_TAGS = "devices/"; //requires device id
    }

    /**
     * Property names constants
     * @author Maksym Rossiitsev
     * @since 1.0.0
     * */
    interface PropertyNames {
        String ADD_TAG = "DeviceTags#AddTag";
        String REMOVE_TAG = "DeviceTags#RemoveAll";
        String TAGS = "DeviceTags#Tags";
        String TOTAL_DEVICES = "MonitoredDevicesTotal";
        String LAST_CYCLE_DURATION = "LastMonitoringCycleDuration(s)";
        String AUTHORIZATION_MODE = "AuthorizationMode";
        String ADAPTER_VERSION = "AdapterVersion";
        String ADAPTER_BUILD_DATE = "AdapterBuildDate";
        String ADAPTER_UPTIME = "AdapterUptime";
        String AVAILABLE_PROPERTY_GROUPS = "AvailableDevicesPropertyGroups#";
    }

    /**
     * Json Path constants
     * @author Maksym Rossiitsev
     * @since 1.0.0
     * */
    interface Paths {
        /**
         * Internal Json data types
         * @author Maksym Rossiitsev
         * @since 1.0.0
         * */
        interface DataType {
            String INTEGER = "integer";
            String STRING = "string";
        }
        String ID = "/id";
        String TAGS = "/tags";
        String ITEMS = "/items";
        String SOURCE = "/source";
        String SOURCES = "/sources/%s/value";
        String VALUESPACE = "/valueSpace";
        String TYPE = "/type";
        String ENUM = "/enum";
        String MIN = "/minimum";
        String MAX = "/maximum";
        String RESULT = "/result";
    }

    /**
     * State constants to match online statuses, call statuses etc
     * @author Maksym.Rossiytsev
     * @since 1.0.0
     * */
    interface States {
        List<String> ONLINE_STATUS = Arrays.asList("connected", "connected_with_issues", "activating");
    }

}
