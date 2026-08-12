/*
 * Copyright (c) 2024 AVI-SPL, Inc. All Rights Reserved.
 */
package com.avispl.symphony.dal.communicator;

import java.util.Map;

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
        String XAPI_BOOT_COMMAND = "SystemUnit.Boot";
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
        String REBOOT = "Reboot";
        /**
         * Must match the {@code gracePeriod} value declared for the Reboot control in both
         * src/main/resources/mapping/model-mapping.yml and src/test/resources/mappings/model-mapping.yml.
         * */
        long REBOOT_GRACE_PERIOD_MS = 180000L;
        String API_CAPABILITIES = "APICapabilities";
        String API_PERMISSIONS = "APIPermissions";
        String TAGS = "DeviceTags#Tags";
        String TOTAL_DEVICES = "MonitoredDevicesTotal";
        String LAST_CYCLE_DURATION = "LastMonitoringCycleDuration(s)";
        String AUTHORIZATION_MODE = "AuthorizationMode";
        String ADAPTER_VERSION = "AdapterVersion";
        String ADAPTER_BUILD_DATE = "AdapterBuildDate";
        String ADAPTER_UPTIME = "AdapterUptime";
        String ADAPTER_UPTIME_MIN = "AdapterUptime(min)";
        String MONITORING_CYCLE_INTERVAL = "MonitoringCycleInterval(min)";
        String AVAILABLE_PROPERTY_GROUPS = "AvailableDevicesPropertyGroups#";
        String STATUS = "Status";
        String STATUS_GROUP = "Status#";
        String DEVICE_STATE = "SystemUnit#State";
        String LAST_UPDATED = "LastUpdated";
        String DEVICE_TAGS = "DeviceTags";
        String CONFIGURATION = "Configuration";
        String CONFIGURATION_GROUP = "Configuration#";
    }

    /**
     * Header names
     * @author Maksym Rossiitsev
     * @since 1.0.0
     * */
    interface Headers {
        String AUTHORIZATION = "Authorization";
        String CONTENT_TYPE = "Content-Type";
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
        String EDITABLE = "/sources/configured/editability/isEditable";
    }

    /**
     * State constants to match online statuses, call statuses etc
     * @author Maksym.Rossiytsev
     * @since 1.0.0
     * */
    interface States {
        String IN_CALL = "InCall";
        String TRUE = "True";
    }

    /**
     * Collection of properties that indicate device call status
     * @author Maksym.Rossiytsev
     * @since 1.0.0
     * */
    interface CallIndicators {
        String SYSTEM_STATE = "/SystemUnit/State/System"; //InCall/Initialized/Initializing/Multisite/Sleeping
        String MS_EXTENSION_IN_CALL = "/SystemUnit/Extensions/Microsoft/InCall";
        String MS_TEAMS_IN_CALL = "/MicrosoftTeams/Calling/InCall";
    }

    /**
     * Catalog entries fixtures, to make aggregated devices onboarding process a bit more streamlined
     * @author Maksym.Rossiytsev
     * @since 1.0.1
     * */
    interface Catalog {
        Map<String, String> CATALOG_ENTRIES = Map.ofEntries(
                Map.entry("camera", "Camera"),
                Map.entry("monitor", "Monitors"),
                Map.entry("controlsystem", "Switchers"),
                Map.entry("microphone", "Microphone"),
                Map.entry("accessory", "Touch Screens"),
                Map.entry("cisco", "Cisco"),
                Map.entry("roomdesk", "Single Codecs")
        );
    }

    /**
     * Model-specific mapping rules, applied on top of the raw device model name (the {@code product}
     * field returned by the WebEx API, e.g. "Cisco Desk Pro") to normalize the device's model name,
     * manufacturer, type and category values, so they are consistent regardless of how WebEx reports
     * the model.
     *
     * @author Ritik Madaan
     * @since 1.0.2
     * */
    interface ModelCatalog {

        /**
         * A single model mapping rule entry: normalized model name, manufacturer, type and category
         * to apply for a given raw (external/API) device name.
         * */
        class ModelMappingEntry {
            private final String modelName;
            private final String manufacturer;
            private final String type;
            private final String category;

            public ModelMappingEntry(String modelName, String manufacturer, String type, String category) {
                this.modelName = modelName;
                this.manufacturer = manufacturer;
                this.type = type;
                this.category = category;
            }

            public String getModelName() {
                return modelName;
            }

            public String getManufacturer() {
                return manufacturer;
            }

            public String getType() {
                return type;
            }

            public String getCategory() {
                return category;
            }
        }

        /**
         * Mapping rules keyed by lower-cased raw/external device name (the value of the {@code product}
         * field returned by the WebEx API for a device, e.g. "cisco desk pro"), mapping to the normalized
         * model name we want to publish.
         * */
        Map<String, ModelMappingEntry> MODEL_ENTRIES = Map.ofEntries(
                Map.entry("cisco desk pro", new ModelMappingEntry("Webex Desk Pro", "Cisco", "Codecs", "Single Codecs")),
                Map.entry("cisco precision 60", new ModelMappingEntry("Precision 60", "Cisco", "AV Devices", "Camera")),
                Map.entry("cisco quad camera", new ModelMappingEntry("Quad Camera", "Cisco", "AV Devices", "Camera")),
                Map.entry("cisco room kit mini", new ModelMappingEntry("WebEx Room Kit Mini", "Cisco", "Codecs", "Single Codecs")),
                Map.entry("cisco room kit plus", new ModelMappingEntry("WebEx Room Kit Plus", "Cisco", "Codecs", "Single Codecs")),
                Map.entry("cisco room kit pro", new ModelMappingEntry("WebEx Room Kit Pro", "Cisco", "Codecs", "Single Codecs")),
                Map.entry("cisco codec plus", new ModelMappingEntry("WebEx Codec Plus", "Cisco", "Codecs", "Single Codecs")),
                Map.entry("cisco codec pro", new ModelMappingEntry("WebEx Codec Pro", "Cisco", "Codecs", "Single Codecs")),
                Map.entry("cisco room navigator", new ModelMappingEntry("Navigator", "Cisco", "AV Devices", "Touch Screens")),
                Map.entry("cisco desk pro g2", new ModelMappingEntry("Webex Desk Pro G2", "Cisco", "Codecs", "Single Codecs")),
                Map.entry("cisco codec pro g2", new ModelMappingEntry("WebEx Codec Pro G2", "Cisco", "Codecs", "Single Codecs")),
                Map.entry("cisco room kit pro g2", new ModelMappingEntry("WebEx Room Kit Pro G2", "Cisco", "Codecs", "Single Codecs"))
        );

        /**
         * Resolve a mapping entry for the given raw/external device name returned by the WebEx API.
         * First attempts an exact (case-insensitive) match against {@link #MODEL_ENTRIES}. If none is
         * found, falls back to prefix matching, so a hardware/firmware revision suffix appended by WebEx
         * on top of a known base name (e.g. "Cisco Codec Pro G2", "Cisco Desk Pro G2") still resolves to
         * that base model's mapping rule, without requiring a dedicated map entry for every new variant.
         *
         * @param rawModelName the raw device model/product name as returned by the WebEx API
         * @return the matching {@link ModelMappingEntry}, or {@code null} if no rule applies
         * */
        static ModelMappingEntry resolve(String rawModelName) {
            if (rawModelName == null || rawModelName.isEmpty()) {
                return null;
            }
            String normalized = rawModelName.toLowerCase().trim();

            ModelMappingEntry entry = MODEL_ENTRIES.get(normalized);
            if (entry != null) {
                return entry;
            }
            for (Map.Entry<String, ModelMappingEntry> candidate : MODEL_ENTRIES.entrySet()) {
                if (normalized.startsWith(candidate.getKey() + " ")) {
                    return candidate.getValue();
                }
            }
            return null;
        }
    }
}
