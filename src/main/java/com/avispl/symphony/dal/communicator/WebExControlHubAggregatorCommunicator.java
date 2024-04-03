/*
 * Copyright (c) 2024 AVI-SPL, Inc. All Rights Reserved.
 */
package com.avispl.symphony.dal.communicator;

import com.avispl.symphony.api.dal.control.Controller;
import com.avispl.symphony.api.dal.dto.control.AdvancedControllableProperty;
import com.avispl.symphony.api.dal.dto.control.ControllableProperty;
import com.avispl.symphony.api.dal.dto.monitor.EndpointStatistics;
import com.avispl.symphony.api.dal.dto.monitor.ExtendedStatistics;
import com.avispl.symphony.api.dal.dto.monitor.Statistics;
import com.avispl.symphony.api.dal.dto.monitor.aggregator.AggregatedDevice;
import com.avispl.symphony.api.dal.error.CommandFailureException;
import com.avispl.symphony.api.dal.monitor.Monitorable;
import com.avispl.symphony.api.dal.monitor.aggregator.Aggregator;
import com.avispl.symphony.dal.aggregator.parser.AggregatedDeviceProcessor;
import com.avispl.symphony.dal.aggregator.parser.PropertiesMapping;
import com.avispl.symphony.dal.aggregator.parser.PropertiesMappingParser;
import com.avispl.symphony.dal.communicator.data.AuthorizationResponse;
import com.avispl.symphony.dal.communicator.error.RetryError;
import com.avispl.symphony.dal.util.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.CollectionUtils;
import org.springframework.web.client.RestTemplate;

import javax.security.auth.login.FailedLoginException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static com.avispl.symphony.dal.util.ControllablePropertyFactory.*;
import static java.util.stream.Collectors.toList;

/**
 * Communicator for WebEx ControlHub API
 * Supported features are:
 * - Devices metadata
 * - Device configuration
 * - xAPI status, if supported by the device
 * - Devices filtering (tag, product and type based)
 * - Property groups filtering
 *
 * @author Maksym Rossiitsev
 * @since 1.0.0
 */
public class WebExControlHubAggregatorCommunicator extends RestCommunicator implements Aggregator, Monitorable, Controller {
    /**
     * Interceptor for RestTemplate that checks for the response headers populated by the WebEx API.
     * Specifically - Retry-After header, which is essential for proper request-retry mechanism.
     *
     * @author Maksym.Rossiytsev
     * @since 1.0.0
     */
    class WebExRequestInterceptor implements ClientHttpRequestInterceptor {
        @Override
        public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
            ClientHttpResponse response = execution.execute(request, body);
            if (response.getRawStatusCode() == 429) {
                List<String> retryAfterSeconds = response.getHeaders().get("Retry-After");
                if (retryAfterSeconds != null && !retryAfterSeconds.isEmpty()) {
                    response.close();
                    throw new RetryError(Long.parseLong(retryAfterSeconds.get(0)));
                }
            }
            return response;
        }
    }

    /**
     * Process that is running constantly and triggers collecting data from WebEx API endpoints,
     * based on the given timeouts and thresholds.
     *
     * @author Maksym.Rossiytsev
     * @since 1.0.0
     */
    class WebExControlHubDeviceDataLoader implements Runnable {
        private volatile boolean inProgress;

        public WebExControlHubDeviceDataLoader() {
            logDebugMessage("Creating new device data loader.");
            inProgress = true;
        }

        @Override
        public void run() {
            logDebugMessage("Entering device data loader active stage.");
            mainloop:
            while (inProgress) {
                long startCycle = System.currentTimeMillis();
                try {
                    try {
                        TimeUnit.MILLISECONDS.sleep(500);
                    } catch (InterruptedException e) {
                        // Ignore for now
                    }

                    if (!inProgress) {
                        logDebugMessage("Main data collection thread is not in progress, breaking.");
                        break mainloop;
                    }

                    updateAggregatorStatus();
                    // next line will determine whether WebEx monitoring was paused
                    if (devicePaused) {
                        logDebugMessage("The device communicator is paused, data collector is not active.");
                        continue mainloop;
                    }
                    try {
                        logDebugMessage("Fetching devices list.");
                        fetchDevicesList();
                    } catch (Exception e) {
                        logger.error("Error occurred during device list retrieval: " + e.getMessage() + " with cause: " + e.getCause().getMessage(), e);
                        if (e instanceof CommandFailureException) {
                            saveActiveErrors(((CommandFailureException) e).getStatusCode(), e.getMessage());
                        } else if (e instanceof FailedLoginException) {
                            saveActiveErrors(401, e.getMessage());
                        }
                    }

                    if (!inProgress) {
                        logDebugMessage("The data collection thread is not in progress. Breaking the loop.");
                        break mainloop;
                    }

                    int aggregatedDevicesCount = aggregatedDevices.size();
                    if (aggregatedDevicesCount == 0) {
                        logDebugMessage("No devices collected in the main data collection thread so far. Continuing.");
                        continue mainloop;
                    }

                    while (nextDevicesCollectionIterationTimestamp > System.currentTimeMillis()) {
                        try {
                            TimeUnit.MILLISECONDS.sleep(1000);
                        } catch (InterruptedException e) {
                            //
                        }
                    }

                    for (AggregatedDevice aggregatedDevice : aggregatedDevices.values()) {
                        if (!inProgress) {
                            logDebugMessage("The data collection thread is not in progress. Breaking the data update loop.");
                            break;
                        }
                        if (executorService == null) {
                            logDebugMessage("Executor service reference is null. Breaking the execution.");
                            break;
                        }
                        if (includeStatusUpdates) {
                            devicesExecutionPool.add(executorService.submit(() -> {
                                try {
                                    requestDeviceStatus(aggregatedDevice);
                                    cleanupActiveErrors();
                                } catch (Exception e) {
                                    logger.error(String.format("Exception during WebEx device '%s' status data processing.", aggregatedDevice.getDeviceName()), e);
                                    if (e instanceof CommandFailureException) {
                                        int statusCode = ((CommandFailureException) e).getStatusCode();
                                        if (statusCode != 404 && statusCode != 403) {
                                            saveActiveErrors(statusCode, e.getMessage());
                                        }
                                    } else if (e instanceof FailedLoginException) {
                                        saveActiveErrors(401, e.getMessage());
                                    }
                                }
                            }));
                        }
                        if (includeConfigurationUpdates) {
                            devicesExecutionPool.add(executorService.submit(() -> {
                                try {
                                    requestDeviceConfiguration(aggregatedDevice);
                                    cleanupActiveErrors();
                                } catch (Exception e) {
                                    logger.error(String.format("Exception during WebEx device '%s' configuration data processing.", aggregatedDevice.getDeviceName()), e);
                                    if (e instanceof CommandFailureException) {
                                        int statusCode = ((CommandFailureException) e).getStatusCode();
                                        if (statusCode != 404 && statusCode != 403) {
                                            saveActiveErrors(statusCode, e.getMessage());
                                        }
                                    } else if (e instanceof FailedLoginException) {
                                        saveActiveErrors(401, e.getMessage());
                                    }
                                }
                            }));
                        }
                    }
                    do {
                        try {
                            TimeUnit.MILLISECONDS.sleep(500);
                        } catch (InterruptedException e) {
                            logger.error("Interrupted exception during main loop execution", e);
                            if (!inProgress) {
                                logDebugMessage("Breaking after the main loop execution");
                                break;
                            }
                        }
                        devicesExecutionPool.removeIf(Future::isDone);
                    } while (!devicesExecutionPool.isEmpty());

                    // We don't want to fetch devices statuses too often, so by default it's currentTime + 30s
                    // otherwise - the variable is reset by the retrieveMultipleStatistics() call, which
                    // launches devices detailed statistics collection
                    nextDevicesCollectionIterationTimestamp = System.currentTimeMillis() + 30000;

                    lastMonitoringCycleDuration = (System.currentTimeMillis() - startCycle)/1000;
                    logDebugMessage("Finished collecting devices statistics cycle at " + new Date() + ", total duration: " + lastMonitoringCycleDuration);
                } catch(Exception e) {
                    logger.error("Unexpected error occurred during main device collection cycle", e);
                }
            }
            logDebugMessage("Main device collection loop is completed, in progress marker: " + inProgress);
            // Finished collecting
        }

        /**
         * Triggers main loop to stop
         */
        public void stop() {
            logDebugMessage("Main device details collection loop is stopped!");
            inProgress = false;
        }

        /**
         * Retrieves {@link #inProgress}
         *
         * @return value of {@link #inProgress}
         */
        public boolean isInProgress() {
            return inProgress;
        }
    }

    /**
     * Indicates whether a device is considered as paused.
     * True by default so if the system is rebooted and the actual value is lost -> the device won't start stats
     * collection unless the {@link WebExControlHubAggregatorCommunicator#retrieveMultipleStatistics()} method is called which will change it
     * to a correct value
     */
    private volatile boolean devicePaused = true;

    /**
     * We don't want the statistics to be collected constantly, because if there's not a big list of devices -
     * new devices statistics loop will be launched before the next monitoring iteration. To avoid that -
     * this variable stores a timestamp which validates it, so when the devices statistics is done collecting, variable
     * is set to currentTime + 30s, at the same time, calling {@link #retrieveMultipleStatistics()} and updating the
     * {@link #aggregatedDevices} resets it to the currentTime timestamp, which will re-activate data collection.
     */
    private volatile long nextDevicesCollectionIterationTimestamp;

    /**
     * This parameter holds timestamp of when we need to stop performing API calls
     * It used when device stop retrieving statistic. Updated each time of called #retrieveMultipleStatistics
     */
    private volatile long validRetrieveStatisticsTimestamp;

    /**
     * Time period within which the device metadata (basic devices information) cannot be refreshed.
     * Ignored if device list is not yet retrieved or the cached device list is empty {@link WebExControlHubAggregatorCommunicator#aggregatedDevices}
     */
    private volatile long validDeviceMetaDataRetrievalPeriodTimestamp;

    /**
     * Include xAPI device status updates
     * */
    private boolean includeStatusUpdates = true;

    /**
     * Include device configuration updates
     * */
    private boolean includeConfigurationUpdates = true;

    /**
     *
     * */
    private int deviceRetrievalPageSize = 100;

    /**
     * Total retry attempts for data retrieval
     * */
    private int requestRetryAttempts = 10;

    /**
     * Time period within which the device configuration cannot be refreshed.
     * Ignored if device list is not yet retrieved or the cached device list is empty {@link WebExControlHubAggregatorCommunicator#aggregatedDevices}
     */
    private ConcurrentHashMap<String, Long> validDeviceConfigurationRetrievalPeriodTimestamps = new ConcurrentHashMap<>();

    /**
     * Time period within which the device status cannot be refreshed.
     * Ignored if device list is not yet retrieved or the cached device list is empty {@link WebExControlHubAggregatorCommunicator#aggregatedDevices}
     */
    private ConcurrentHashMap<String, Long> validDeviceStatusRetrievalPeriodTimestamps = new ConcurrentHashMap<>();

    /**
     * Aggregator inactivity timeout. If the {@link WebExControlHubAggregatorCommunicator#retrieveMultipleStatistics()}  method is not
     * called during this period of time - device is considered to be paused, thus the Cloud API
     * is not supposed to be called
     */
    private static final long retrieveStatisticsTimeOut = 180000;

    /**
     * Device metadata retrieval timeout. The general devices list is retrieved once during this time period.
     */
    private long deviceMetaDataRetrievalTimeout = 60 * 1000 * 5;

    /**
     * Device configuration retrieval timeout. The general devices list is retrieved once during this time period.
     */
    private long deviceConfigurationRetrievalTimeout = 60 * 1000 / 2;

    /**
     * Device status retrieval timeout. The general devices list is retrieved once during this time period.
     */
    private long deviceStatusRetrievalTimeout = 60 * 1000 / 2;

    /**
     * If the {@link WebExControlHubAggregatorCommunicator#deviceMetaDataRetrievalTimeout} is set to a value that is too small -
     * devices list will be fetched too frequently. In order to avoid this - the minimal value is based on this value.
     */
    private static final long defaultMetaDataTimeout = 60 * 1000 / 2;

    /**
     * If the {@link WebExControlHubAggregatorCommunicator#deviceConfigurationRetrievalTimeout} is set to a value that is too small -
     * devices list will be fetched too frequently. In order to avoid this - the minimal value is based on this value.
     */
    private static final long defaultConfigurationTimeout = 60 * 1000 / 2;

    /**
     * If the {@link WebExControlHubAggregatorCommunicator#deviceStatusRetrievalTimeout} is set to a value that is too small -
     * devices list will be fetched too frequently. In order to avoid this - the minimal value is based on this value.
     */
    private static final long defaultStatusTimeout = 60 * 1000 / 2;

    /**
     * Number of threads assigned for the data collection jobs
     * */
    private int executorServiceThreadCount = 10;

    /**
     * Pool for keeping all the async operations in, to track any operations in progress and cancel them if needed
     */
    private final List<Future> devicesExecutionPool = new ArrayList<>();

    /**
     * Executor that runs all the async operations, that {@link #deviceDataLoader} is posting and
     * {@link #devicesExecutionPool} is keeping track of
     */
    private ExecutorService executorService;

    /**
     * Runner service responsible for collecting data and posting processes to {@link #devicesExecutionPool}
     */
    private WebExControlHubDeviceDataLoader deviceDataLoader;

    /**
     * How much time last monitoring cycle took to finish
     * */
    private Long lastMonitoringCycleDuration;

    private int lastErrorCode = 0;
    private String lastErrorMessage;

    private String refreshToken; // The refresh token is ever lasting
    private String accessToken;
    private long adapterInitializationTimestamp;

    private AggregatedDeviceProcessor aggregatedDeviceProcessor;

    private AuthorizationMode authorizationMode = AuthorizationMode.INTEGRATION;
    private PingMode pingMode = PingMode.ICMP;

    /**
     * Adapter metadate properties, contain information about build version and build date
     * */
    private Properties adapterProperties;

    /**
     * Aggregated devices cache
     * */
    private ConcurrentHashMap<String, AggregatedDevice> aggregatedDevices = new ConcurrentHashMap<>();

    /**
     * Collection of symphony configuration names to webex configuration names
     * */
    private ConcurrentHashMap<String, String> controllablePropertiesToConfigurationNames = new ConcurrentHashMap<>();

    /**
     * List of available property groups
     * */
    private Set<String> availablePropertyGroups = new HashSet<>();

    /**
     * List of included/enabled property groups
     * */
    private Set<String> includePropertyGroups = new HashSet<>();

    /**
     * List of tags to use to filter devices
     * */
    private Set<String> tagDeviceFilter = new HashSet<>();

    /**
     * List of products to use to filter devices
     * */
    private Set<String> productDeviceFilter = new HashSet<>();

    /**
     * List of types to use to filter devices
     * */
    private Set<String> typeDeviceFilter = new HashSet<>();

    /**
     * Interceptor for RestTemplate that injects
     * authorization header and fixes malformed headers sent by WebEx API
     */
    private ClientHttpRequestInterceptor webExHeaderInterceptor = new WebExRequestInterceptor();

    public WebExControlHubAggregatorCommunicator() throws IOException {

        Map<String, PropertiesMapping> mapping = new PropertiesMappingParser().loadYML("mapping/model-mapping.yml", getClass());
        aggregatedDeviceProcessor = new AggregatedDeviceProcessor(mapping);
        adapterProperties = new Properties();
        adapterProperties.load(getClass().getResourceAsStream("/version.properties"));

        executorService = Executors.newFixedThreadPool(executorServiceThreadCount);
        executorService.submit(deviceDataLoader = new WebExControlHubDeviceDataLoader());
    }

    /**
     * Retrieves {@link #productDeviceFilter}
     *
     * @return value of {@link #productDeviceFilter}
     */
    public String getProductDeviceFilter() {
        return String.join(", ", productDeviceFilter);
    }

    /**
     * Sets {@link #productDeviceFilter} value
     *
     * @param productDeviceFilter new value of {@link #productDeviceFilter}
     */
    public void setProductDeviceFilter(String productDeviceFilter) {
        this.productDeviceFilter = Arrays.stream(productDeviceFilter.split(",")).map(String::trim).filter(StringUtils::isNotNullOrEmpty).collect(Collectors.toSet());
    }

    /**
     * Retrieves {@link #typeDeviceFilter}
     *
     * @return value of {@link #typeDeviceFilter}
     */
    public String getTypeDeviceFilter() {
        return String.join(", ",typeDeviceFilter);
    }

    /**
     * Sets {@link #typeDeviceFilter} value
     *
     * @param typeDeviceFilter new value of {@link #typeDeviceFilter}
     */
    public void setTypeDeviceFilter(String typeDeviceFilter) {
        this.typeDeviceFilter = Arrays.stream(typeDeviceFilter.split(",")).map(String::trim).filter(StringUtils::isNotNullOrEmpty).collect(Collectors.toSet());
    }

    /**
     * Retrieves {@link #tagDeviceFilter}
     *
     * @return value of {@link #tagDeviceFilter}
     */
    public String getTagDeviceFilter() {
        return String.join(", ", tagDeviceFilter);
    }

    /**
     * Sets {@link #tagDeviceFilter} value
     *
     * @param tagDeviceFilter new value of {@link #tagDeviceFilter}
     */
    public void setTagDeviceFilter(String tagDeviceFilter) {
        this.tagDeviceFilter = Arrays.stream(tagDeviceFilter.split(",")).map(String::trim).filter(StringUtils::isNotNullOrEmpty).collect(Collectors.toSet());
    }

    /**
     * Retrieves {@link #includeStatusUpdates}
     *
     * @return value of {@link #includeStatusUpdates}
     */
    public boolean isIncludeStatusUpdates() {
        return includeStatusUpdates;
    }

    /**
     * Sets {@link #includeStatusUpdates} value
     *
     * @param includeStatusUpdates new value of {@link #includeStatusUpdates}
     */
    public void setIncludeStatusUpdates(boolean includeStatusUpdates) {
        this.includeStatusUpdates = includeStatusUpdates;
    }

    /**
     * Retrieves {@link #includeConfigurationUpdates}
     *
     * @return value of {@link #includeConfigurationUpdates}
     */
    public boolean isIncludeConfigurationUpdates() {
        return includeConfigurationUpdates;
    }

    /**
     * Sets {@link #includeConfigurationUpdates} value
     *
     * @param includeConfigurationUpdates new value of {@link #includeConfigurationUpdates}
     */
    public void setIncludeConfigurationUpdates(boolean includeConfigurationUpdates) {
        this.includeConfigurationUpdates = includeConfigurationUpdates;
    }

    /**
     * Retrieves {@link #executorServiceThreadCount}
     *
     * @return value of {@link #executorServiceThreadCount}
     */
    public int getExecutorServiceThreadCount() {
        return executorServiceThreadCount;
    }

    /**
     * Sets {@link #executorServiceThreadCount} value
     *
     * @param executorServiceThreadCount new value of {@link #executorServiceThreadCount}
     */
    public void setExecutorServiceThreadCount(int executorServiceThreadCount) {
        if (executorServiceThreadCount == 0) {
            this.executorServiceThreadCount = 8;
        } else {
            this.executorServiceThreadCount = executorServiceThreadCount;
        }
    }

    /**
     * Retrieves {@link #deviceConfigurationRetrievalTimeout}
     *
     * @return value of {@link #deviceConfigurationRetrievalTimeout}
     */
    public long getDeviceConfigurationRetrievalTimeout() {
        return deviceConfigurationRetrievalTimeout;
    }

    /**
     * Sets {@link #deviceConfigurationRetrievalTimeout} value
     *
     * @param deviceConfigurationRetrievalTimeout new value of {@link #deviceConfigurationRetrievalTimeout}
     */
    public void setDeviceConfigurationRetrievalTimeout(long deviceConfigurationRetrievalTimeout) {
        this.deviceConfigurationRetrievalTimeout = Math.max(defaultConfigurationTimeout, deviceConfigurationRetrievalTimeout);
    }

    /**
     * Retrieves {@link #deviceStatusRetrievalTimeout}
     *
     * @return value of {@link #deviceStatusRetrievalTimeout}
     */
    public long getDeviceStatusRetrievalTimeout() {
        return deviceStatusRetrievalTimeout;
    }

    /**
     * Sets {@link #deviceStatusRetrievalTimeout} value
     *
     * @param deviceStatusRetrievalTimeout new value of {@link #deviceStatusRetrievalTimeout}
     */
    public void setDeviceStatusRetrievalTimeout(long deviceStatusRetrievalTimeout) {
        this.deviceStatusRetrievalTimeout = Math.max(defaultStatusTimeout, deviceStatusRetrievalTimeout);
    }

    /**
     * Retrieves {@code {@link #deviceMetaDataRetrievalTimeout }}
     *
     * @return value of {@link #deviceMetaDataRetrievalTimeout}
     */
    public long getDeviceMetaDataRetrievalTimeout() {
        return deviceMetaDataRetrievalTimeout;
    }

    /**
     * Sets {@code deviceMetaDataInformationRetrievalTimeout}
     *
     * @param deviceMetaDataRetrievalTimeout the {@code long} field
     */
    public void setDeviceMetaDataRetrievalTimeout(long deviceMetaDataRetrievalTimeout) {
        this.deviceMetaDataRetrievalTimeout = Math.max(defaultMetaDataTimeout, deviceMetaDataRetrievalTimeout);
    }

    /**
     * Retrieves {@link #includePropertyGroups}
     *
     * @return value of {@link #includePropertyGroups}
     */
    public String getIncludePropertyGroups() {
        return String.join(",", includePropertyGroups);
    }

    /**
     * Sets {@link #includePropertyGroups} value
     *
     * @param includePropertyGroups new value of {@link #includePropertyGroups}
     */
    public void setIncludePropertyGroups(String includePropertyGroups) {
        this.includePropertyGroups = Arrays.stream(includePropertyGroups.split(",")).map(String::trim).collect(Collectors.toSet());
    }

    /**
     * Retrieves {@link #deviceRetrievalPageSize}
     *
     * @return value of {@link #deviceRetrievalPageSize}
     */
    public int getDeviceRetrievalPageSize() {
        return deviceRetrievalPageSize;
    }

    /**
     * Sets {@link #deviceRetrievalPageSize} value
     *
     * @param deviceRetrievalPageSize new value of {@link #deviceRetrievalPageSize}
     */
    public void setDeviceRetrievalPageSize(int deviceRetrievalPageSize) {
        this.deviceRetrievalPageSize = deviceRetrievalPageSize;
    }

    /**
     * Retrieves {@link #requestRetryAttempts}
     *
     * @return value of {@link #requestRetryAttempts}
     */
    public int getRequestRetryAttempts() {
        return requestRetryAttempts;
    }

    /**
     * Sets {@link #requestRetryAttempts} value
     *
     * @param requestRetryAttempts new value of {@link #requestRetryAttempts}
     */
    public void setRequestRetryAttempts(int requestRetryAttempts) {
        this.requestRetryAttempts = requestRetryAttempts;
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
     * Retrieves {@link #pingMode}
     *
     * @return value of {@link #pingMode}
     */
    public String getPingMode() {
        return pingMode.name();
    }

    /**
     * Sets {@link #pingMode} value
     *
     * @param pingMode new value of {@link #pingMode}
     */
    public void setPingMode(String pingMode) {
        this.pingMode = PingMode.ofString(pingMode);
    }

    /**
     * Retrieves {@link #authorizationMode}
     *
     * @return value of {@link #authorizationMode}
     */
    public String getAuthorizationMode() {
        return authorizationMode.name();
    }

    /**
     * Sets {@link #authorizationMode} value
     *
     * @param authorizationMode new value of {@link #authorizationMode}
     */
    public void setAuthorizationMode(String authorizationMode) {
        this.authorizationMode = AuthorizationMode.ofString(authorizationMode);
    }

    @Override
    protected void internalInit() throws Exception {
        long currentTimestamp = System.currentTimeMillis();
        adapterInitializationTimestamp = currentTimestamp;
        validRetrieveStatisticsTimestamp = System.currentTimeMillis();
        validDeviceMetaDataRetrievalPeriodTimestamp = currentTimestamp;

        setBaseUri("v1");
        super.internalInit();
    }

    @Override
    protected void internalDestroy() {
        try {
            accessToken = null;
            if (deviceDataLoader != null) {
                deviceDataLoader.stop();
                deviceDataLoader = null;
            }
            if (executorService != null) {
                executorService.shutdownNow();
                executorService = null;
            }
            devicesExecutionPool.forEach(future -> future.cancel(true));
            devicesExecutionPool.clear();
            aggregatedDevices.clear();

            availablePropertyGroups.clear();
            controllablePropertiesToConfigurationNames.clear();
        } catch (Exception e) {
            logger.error("Error while adapter internalDestroy operation", e);
        } finally {
            super.internalDestroy();
        }
    }

    @Override
    public void controlProperty(ControllableProperty controllableProperty) throws Exception {
        String propertyName = controllableProperty.getProperty();
        String deviceId = controllableProperty.getDeviceId();
        Object propertyValue = controllableProperty.getValue();

        switch (propertyName) {
            case Constants.PropertyNames.ADD_TAG:
                addDeviceTag(deviceId, String.valueOf(propertyValue));
                break;
            case Constants.PropertyNames.REMOVE_TAG:
                removeDeviceTags(deviceId);
                break;
            default:
                updateDeviceConfiguration(deviceId, propertyName, propertyValue);
                break;
        }
    }

    @Override
    public void controlProperties(List<ControllableProperty> controllablePropertyList) throws Exception {
        if (CollectionUtils.isEmpty(controllablePropertyList)) {
            throw new IllegalArgumentException("Controllable properties cannot be null or empty");
        }
        for (ControllableProperty controllableProperty : controllablePropertyList) {
            controlProperty(controllableProperty);
        }
    }

    /**
     * Push device configuration change to WebEx API
     *
     * @param deviceId ID of the device to update configuration for
     * @param propertyName name of the property to change
     * @param value new value of the configuration property
     *
     * @throws Exception if any error occurs
     * */
    private void updateDeviceConfiguration(String deviceId, String propertyName, Object value) throws Exception {
        String configName = controllablePropertiesToConfigurationNames.get(propertyName);

        if (StringUtils.isNullOrEmpty(configName)) {
            throw new IllegalArgumentException("Unable to locate the configuration path for property " + propertyName);
        }
        Map<String, Object> configurationMap = new HashMap<>();
        configurationMap.put("op", "replace");
        configurationMap.put("path", configName + "/sources/configured/value");
        if (value instanceof Number) {
            configurationMap.put("value", Integer.parseInt(String.format("%.0f", value)));
        } else {
            configurationMap.put("value", String.valueOf(value));
        }

        JsonNode response = doPatch(Constants.URL.DEVICE_CONFIGURATIONS + deviceId, Collections.singletonList(configurationMap), JsonNode.class);
        response.asText();
    }

    @Override
    public List<Statistics> getMultipleStatistics() {
        List<Statistics> statisticsList = new ArrayList<>();
        ExtendedStatistics extendedStatistics = new ExtendedStatistics();

        Map<String, String> statistics = new HashMap<>();
        Map<String, String> dynamicStatistics = new HashMap<>();

        dynamicStatistics.put(Constants.PropertyNames.TOTAL_DEVICES, String.valueOf(aggregatedDevices.size()));
        if (lastMonitoringCycleDuration != null) {
            dynamicStatistics.put(Constants.PropertyNames.LAST_CYCLE_DURATION, String.valueOf(lastMonitoringCycleDuration));
        }

        statistics.put(Constants.PropertyNames.AUTHORIZATION_MODE, authorizationMode.name());
        statistics.put(Constants.PropertyNames.ADAPTER_VERSION, adapterProperties.getProperty("aggregator.version"));
        statistics.put(Constants.PropertyNames.ADAPTER_BUILD_DATE, adapterProperties.getProperty("aggregator.build.date"));
        statistics.put(Constants.PropertyNames.ADAPTER_UPTIME, normalizeUptime((System.currentTimeMillis() - adapterInitializationTimestamp) / 1000));

        for(String availableGroup: availablePropertyGroups) {
            statistics.put(Constants.PropertyNames.AVAILABLE_PROPERTY_GROUPS + availableGroup, includePropertyGroups.contains(availableGroup) ? "Enabled" : "Disabled");
        }

        extendedStatistics.setStatistics(statistics);
        extendedStatistics.setDynamicStatistics(dynamicStatistics);
        statisticsList.add(extendedStatistics);
        return statisticsList;
    }

    @Override
    public List<AggregatedDevice> retrieveMultipleStatistics() throws FailedLoginException {
        if (lastErrorCode != 0) {
            if (lastErrorCode == 401 || lastErrorCode == 403) {
                throw new FailedLoginException("Failed login while retrieving devices list: " + lastErrorMessage);
            }
        }
        updateValidRetrieveStatisticsTimestamp();
        aggregatedDevices.values().forEach(aggregatedDevice -> aggregatedDevice.setTimestamp(System.currentTimeMillis()));
        return new ArrayList<>(aggregatedDevices.values());
    }

    @Override
    public int ping() throws Exception {
        if (pingMode == PingMode.ICMP) {
            return super.ping();
        } else if (pingMode == PingMode.TCP) {
            if (isInitialized()) {
                long pingResultTotal = 0L;

                for (int i = 0; i < this.getPingAttempts(); i++) {
                    long startTime = System.currentTimeMillis();

                    try (Socket puSocketConnection = new Socket(this.host, this.getPort())) {
                        puSocketConnection.setSoTimeout(this.getPingTimeout());
                        if (puSocketConnection.isConnected()) {
                            long pingResult = System.currentTimeMillis() - startTime;
                            pingResultTotal += pingResult;
                            if (this.logger.isTraceEnabled()) {
                                this.logger.trace(String.format("PING OK: Attempt #%s to connect to %s on port %s succeeded in %s ms", i + 1, host, this.getPort(), pingResult));
                            }
                        } else {
                            if (this.logger.isDebugEnabled()) {
                                logger.debug(String.format("PING DISCONNECTED: Connection to %s did not succeed within the timeout period of %sms", host, this.getPingTimeout()));
                            }
                            return this.getPingTimeout();
                        }
                    } catch (SocketTimeoutException | ConnectException tex) {
                        throw new SocketTimeoutException("Socket connection timed out");
                    } catch (UnknownHostException tex) {
                        throw new SocketTimeoutException("Socket connection timed out" + tex.getMessage());
                    } catch (Exception e) {
                        if (this.logger.isWarnEnabled()) {
                            this.logger.warn(String.format("PING TIMEOUT: Connection to %s did not succeed, UNKNOWN ERROR %s: ", host, e.getMessage()));
                        }
                        return this.getPingTimeout();
                    }
                }
                return Math.max(1, Math.toIntExact(pingResultTotal / this.getPingAttempts()));
            } else {
                throw new IllegalStateException("Cannot use device class without calling init() first");
            }
        } else {
            throw new IllegalArgumentException("Unknown PING Mode: " + pingMode);
        }
    }

    /**
     * Fetch list of devices with metadata, once per {@link #deviceMetaDataRetrievalTimeout}
     *
     * @throws Exception if any error occurs
     * */
    private void fetchDevicesList() throws Exception {

        long currentTimestamp = System.currentTimeMillis();
        if (validDeviceMetaDataRetrievalPeriodTimestamp > currentTimestamp) {
            logDebugMessage(String.format("General devices metadata retrieval is in cooldown. %s seconds left",
                    (validDeviceMetaDataRetrievalPeriodTimestamp - currentTimestamp) / 1000));
            return;
        }
        validDeviceMetaDataRetrievalPeriodTimestamp = currentTimestamp + deviceMetaDataRetrievalTimeout;
        List<AggregatedDevice> aggregatedDevicesList = listWebExDevices();

        //If device is not in the list, provided by the server anymore - remove it from the cache
        List<String> collectedDeviceIds = aggregatedDevicesList.stream().map(AggregatedDevice::getDeviceId).collect(toList());
        aggregatedDevices.keySet().removeIf(deviceId -> !collectedDeviceIds.contains(deviceId));

        for(AggregatedDevice aggregatedDevice: aggregatedDevicesList) {
            String deviceId = aggregatedDevice.getDeviceId();
            if (!aggregatedDevices.containsKey(deviceId)) {
                aggregatedDevices.put(deviceId, aggregatedDevice);
            } else {
                AggregatedDevice existingAggregatedDevice = aggregatedDevices.get(deviceId);
                Map<String, String> deviceProperties = existingAggregatedDevice.getProperties();
                deviceProperties.putAll(aggregatedDevice.getProperties());
                deviceProperties.put(Constants.PropertyNames.LAST_UPDATED, generateCurrentDateISO8601());
                existingAggregatedDevice.setDeviceOnline(aggregatedDevice.getDeviceOnline());
                existingAggregatedDevice.setTimestamp(System.currentTimeMillis());
            }
        }
        logDebugMessage("Fetched devices list: " + aggregatedDevices);
        cleanupActiveErrors();
    }

    @Override
    public List<AggregatedDevice> retrieveMultipleStatistics(List<String> deviceIds) throws Exception {
        if (logger.isDebugEnabled()) {
            logger.debug("WebEx ControlHub retrieveMultipleStatistics deviceIds=" + String.join(" ", deviceIds));
        }
        return retrieveMultipleStatistics()
                .stream()
                .filter(aggregatedDevice -> deviceIds.contains(aggregatedDevice.getDeviceId()))
                .collect(toList());
    }

    @Override
    protected void authenticate() throws Exception {
        generateAccessToken();
    }

    /**
     * {@inheritDoc}
     * <p>
     * Additional interceptor to RestTemplate that checks the amount of requests left for metrics endpoints
     */
    @Override
    protected RestTemplate obtainRestTemplate() throws Exception {
        RestTemplate restTemplate = super.obtainRestTemplate();

        List<ClientHttpRequestInterceptor> interceptors = restTemplate.getInterceptors();
        if (!interceptors.contains(webExHeaderInterceptor)) {
            interceptors.add(webExHeaderInterceptor);
        }

        return restTemplate;
    }

    @Override
    protected HttpHeaders putExtraRequestHeaders(HttpMethod httpMethod, String uri, HttpHeaders headers) throws Exception {
        if (!uri.contains("access_token")) {
            if (StringUtils.isNullOrEmpty(accessToken)) {
                authenticate();
            }
            headers.add(Constants.Headers.AUTHORIZATION, "Bearer " + accessToken);
        }
        if (httpMethod == HttpMethod.PATCH) {
            headers.remove(Constants.Headers.CONTENT_TYPE);
            headers.add(Constants.Headers.CONTENT_TYPE, "application/json-patch+json");
        }
        return super.putExtraRequestHeaders(httpMethod, uri, headers);
    }

    /**
     * List webex devices using {@link Constants.URL#DEVICES_URL} along with
     * {@link #tagDeviceFilter}, {@link #typeDeviceFilter} and {@link #productDeviceFilter} filters,
     * if any are specified.
     *
     * @return {@link List} or {@link AggregatedDevice} instances with metadata
     * @throws Exception if any error occurs
     * */
    private List<AggregatedDevice> listWebExDevices() throws Exception {
        StringBuilder deviceListUrl = new StringBuilder();
        deviceListUrl.append(Constants.URL.DEVICES_URL);

        boolean hasFilter = false;
        if (!tagDeviceFilter.isEmpty()) {
            deviceListUrl.append("?tag=").append(String.join(",", tagDeviceFilter));
            hasFilter = true;
        }
        if (!typeDeviceFilter.isEmpty()) {
            if (hasFilter) {
                deviceListUrl.append("&");
            } else {
                deviceListUrl.append("?");
            }
            deviceListUrl.append("type=").append(String.join(",", typeDeviceFilter));
            hasFilter = true;
        }
        if (!productDeviceFilter.isEmpty()) {
            if (hasFilter) {
                deviceListUrl.append("&");
            } else {
                deviceListUrl.append("?");
            }
            deviceListUrl.append("product=").append(String.join(",", productDeviceFilter));
            hasFilter = true;
        }
        if (hasFilter) {
            deviceListUrl.append("&");
        } else {
            deviceListUrl.append("?");
        }
        deviceListUrl.append("start=%d&max=%d");

        List<AggregatedDevice> extractedDevices = new ArrayList<>();
        listWebExDevices(extractedDevices, deviceListUrl.toString(), 0);

        extractedDevices.forEach(aggregatedDevice -> aggregatedDevice.setDeviceName(aggregatedDevice.getDeviceName() + ": " + aggregatedDevice.getDeviceModel()));
        return extractedDevices;
    }

    /**
     * List WebEx devices, with pagination (start/max query string parameters). The method is called recursively
     *
     * @param aggregatedDevices list to add aggregated devices to
     * @param baseUrl url to use for devices collection (with filters applied)
     * @param start starting index for current request
     * */
    private void listWebExDevices(List<AggregatedDevice> aggregatedDevices, String baseUrl, int start) throws Exception {
        String url = String.format(baseUrl, start, start + deviceRetrievalPageSize);
        JsonNode response = null;
        int retries = 0;
        while (retries++ < requestRetryAttempts) {
            try {
                response = doGetWithRetry(url);
                break;
            } catch (Exception e) {
                Throwable rootCause = ExceptionUtils.getRootCause(e);
                if (ExceptionUtils.hasCause(rootCause, RetryError.class)) {
                    Long retryAfter = ((RetryError)rootCause).getRetryAfter();
                    if (retryAfter != null) {
                        TimeUnit.SECONDS.sleep(retryAfter);
                        response = doGetWithRetry(url);
                        continue;
                    }
                }
                break;
            }
        }

        if (response == null) {
            if (logger.isDebugEnabled()) {
                logger.debug("Unable to retrieve device configuration details.");
            }
            return;
        }
        List<AggregatedDevice> extractedDevices = aggregatedDeviceProcessor.extractDevices(response);
        aggregatedDevices.addAll(extractedDevices);

        if (extractedDevices.size() < deviceRetrievalPageSize) {
            if (logger.isDebugEnabled()) {
                logger.debug("Last page of the output is reached, finishing collecting devices list");
            }
            return;
        } else {
            listWebExDevices(aggregatedDevices, baseUrl, start + deviceRetrievalPageSize + 1);
        }
    }

    /**
     * Generate access token if adapter is in INTEGRATION authorization mode
     *
     * @throws Exception if any error occurs
     * */
    private void generateAccessToken() throws Exception {
        if (authorizationMode == AuthorizationMode.INTEGRATION) {
            Map<String, String> request = new HashMap<>();
            request.put("grant_type", "refresh_token");
            request.put("client_id", getLogin());
            request.put("client_secret", getPassword());
            request.put("refresh_token", refreshToken);

            AuthorizationResponse response = doPost(Constants.URL.ACCESS_TOKEN_URL, request, AuthorizationResponse.class);
            accessToken = response.getAccessToken();
        } else if (authorizationMode == AuthorizationMode.BOT) {
            accessToken = getPassword();
        }
    }

    /**
     * Retrieve and generate configuration properties for aggregated device. Execute once per device per {@link #deviceConfigurationRetrievalTimeout}
     *
     * Processes response of the following structure
     * {
     *     "deviceId": "Y2lzY29zcGFyazovL3VybjpURUFNOnVzLWVhc3QtMl9hL0RFVklDRS9hNmYwYjhkMi01ZjdkLTQzZDItODAyNi0zM2JkNDg3NjYzMTg=",
     *     "items": {
     *         "Audio.Ultrasound.MaxVolume": {
     *             "value": 70,
     *             "source": "default",
     *             "sources": {
     *                 "default": {
     *                     "value": 70,
     *                     "editability": {
     *                         "editable": false,
     *                         "reason": "FACTORY_DEFAULT"
     *                     }
     *                 },
     *                 "configured": {
     *                     "value": null,
     *                     "editability": {
     *                         "editable": true
     *                     }
     *                 }
     *             },
     *             "valueSpace": {
     *                 "type": "integer",
     *                 "maximum": 100,
     *                 "minimum": 0
     *             }
     *         },
     *         "FacilityService.Service[1].Name": {
     *             "value": "Live Support",
     *             "source": "default",
     *             "sources": {
     *                 "default": {
     *                     "value": "Live Support",
     *                     "editability": {
     *                         "editable": false,
     *                         "reason": "FACTORY_DEFAULT"
     *                     }
     *                 },
     *                 "configured": {
     *                     "value": null,
     *                     "editability": {
     *                         "editable": true
     *                     }
     *                 }
     *             },
     *             "valueSpace": {
     *                 "type": "string",
     *                 "maxLength": 1024,
     *                 "minLength": 0
     *             }
     *         },
     *         "Conference.MaxReceiveCallRate": {
     *             "value": 786,
     *             "source": "configured",
     *             "sources": {
     *                 "default": {
     *                     "value": 6000,
     *                     "editability": {
     *                         "editable": false,
     *                         "reason": "FACTORY_DEFAULT"
     *                     }
     *                 },
     *                 "configured": {
     *                     "value": 786,
     *                     "editability": {
     *                         "editable": true
     *                     }
     *                 }
     *             },
     *             "valueSpace": {
     *                 "type": "integer",
     *                 "maximum": 6000,
     *                 "minimum": 64
     *             }
     *         },
     *         "Video.Output.Connector[2].Resolution": {
     *             "value": "Auto",
     *             "source": "default",
     *             "sources": {
     *                 "default": {
     *                     "value": "Auto",
     *                     "editability": {
     *                         "editable": false,
     *                         "reason": "FACTORY_DEFAULT"
     *                     }
     *                 },
     *                 "configured": {
     *                     "value": null,
     *                     "editability": {
     *                         "editable": false,
     *                         "reason": "CONFIG_MANAGED_BY_DIFFERENT_AUTHORITY"
     *                     }
     *                 }
     *             },
     *             "valueSpace": {
     *                 "enum": [
     *                     "1920_1080_50",
     *                     "1920_1080_60",
     *                     "1920_1200_50",
     *                     "1920_1200_60",
     *                     "2560_1440_60",
     *                     "3840_2160_30",
     *                     "3840_2160_60",
     *                     "Auto"
     *                 ],
     *                 "type": "string"
     *             }
     *         }
     *     }
     * }
     * and maps them to monitored/controllable properties, following the naming mapping logic:
     * Audio.Ultrasound.MaxVolume -> Audio#UltrasoundMaxVolume
     * Video.Output.Connector[2].Resolution -> VideoOutput#Connector[2]Resolution
     * FacilityService.Service[1].Name -> FacilityService#Service[1]Name
     *
     * So, whenever there's a square bracket in a sequence, group separator is set to precede the array.
     * If there's no array elements, first entry is defined as a group name
     *
     * Property value is based on [source] value, and the type of the controllable property is defined by
     * the [valueSpace] object: Integer type would result in slider, with min/max values taken into account,
     * String type results in either text or dropdown, based on whether or not there's values enum present.
     *
     * @param aggregatedDevice device to generate config properties for
     * @throws Exception if any error occurs
     * */
    private void generateDeviceConfigurationProperties(AggregatedDevice aggregatedDevice) throws Exception {
        Map<String, String> deviceProperties = aggregatedDevice.getProperties();
        if (!includeConfigurationUpdates) {
            if (deviceProperties != null && !deviceProperties.isEmpty()) {
                deviceProperties.keySet().removeIf(s -> s.contains(Constants.PropertyNames.CONFIGURATION_GROUP));
            }
            return;
        }
        long currentTimestamp = System.currentTimeMillis();
        String deviceId = aggregatedDevice.getDeviceId();
        Long validDeviceConfigurationRetrievalPeriodTimestamp = validDeviceConfigurationRetrievalPeriodTimestamps.get(deviceId);

        if (validDeviceConfigurationRetrievalPeriodTimestamp != null && validDeviceConfigurationRetrievalPeriodTimestamp > currentTimestamp) {
            logDebugMessage(String.format("Device status retrieval is in cooldown. %s seconds left",
                    (validDeviceConfigurationRetrievalPeriodTimestamp - currentTimestamp) / 1000));
            return;
        }
        validDeviceConfigurationRetrievalPeriodTimestamps.put(deviceId, currentTimestamp + deviceConfigurationRetrievalTimeout);

        Map<String, String> properties = aggregatedDevice.getProperties();
        List<AdvancedControllableProperty> existingControls = aggregatedDevice.getControllableProperties();
        List<AdvancedControllableProperty> tagControls = existingControls.stream().filter(advancedControllableProperty ->
                advancedControllableProperty.getName().startsWith(Constants.PropertyNames.DEVICE_TAGS)).collect(toList());

        List<AdvancedControllableProperty> advancedControllableProperties = new ArrayList<>(tagControls);
        aggregatedDevice.setControllableProperties(advancedControllableProperties);

        try {
            JsonNode response = doGetWithRetry(Constants.URL.DEVICE_CONFIGURATIONS + deviceId);
            if (response == null) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Unable to retrieve device configuration details.");
                }
                return;
            }
            if (deviceProperties != null && !deviceProperties.isEmpty()) {
                deviceProperties.keySet().removeIf(s -> s.contains(Constants.PropertyNames.CONFIGURATION_GROUP));
            }
            JsonNode array = response.at(Constants.Paths.ITEMS);
            array.fieldNames().forEachRemaining(configPropertyName -> {
                JsonNode configValues = array.get(configPropertyName);
                String source = configValues.at(Constants.Paths.SOURCE).asText();
                String value = configValues.at(String.format(Constants.Paths.SOURCES, source)).asText();
                String symphonyConfigPropertyName = "";
                if (configPropertyName.contains("[")) {
                    String[] nameElements = configPropertyName.split("\\.");
                    String name = "";
                    for (int i = 0; i < nameElements.length; i++) {
                        name += nameElements[i];
                        int nextElement = i + 1;
                        if (!name.contains("#") && nameElements.length > nextElement && nameElements[nextElement].contains("[")) {
                            name += Constants.PropertyNames.CONFIGURATION_GROUP;
                        } else if (!name.contains("#") && i == 0 && name.contains("[")) {
                            name = name.split("\\[")[0] + Constants.PropertyNames.CONFIGURATION_GROUP + name;
                        }
                    }
                    symphonyConfigPropertyName = name;
                } else {
                    symphonyConfigPropertyName = configPropertyName.replaceFirst("\\.", Constants.PropertyNames.CONFIGURATION_GROUP).replaceAll("\\.", "");
                }

                controllablePropertiesToConfigurationNames.put(symphonyConfigPropertyName, configPropertyName);

                if (includePropertyGroups.contains(symphonyConfigPropertyName.split("#")[0])) {
                    availablePropertyGroups.add(symphonyConfigPropertyName.split("#")[0]);
                } else {
                    availablePropertyGroups.add(symphonyConfigPropertyName.split("#")[0]);
                    return;
                }
                JsonNode valueSpace = configValues.at(Constants.Paths.VALUESPACE);
                String configType = valueSpace.at(Constants.Paths.TYPE).asText();
                if (Constants.Paths.DataType.STRING.equals(configType) && !valueSpace.at(Constants.Paths.ENUM).isMissingNode()) {
                    List<String> dropdownValues = new ArrayList<>();
                    valueSpace.at(Constants.Paths.ENUM).elements().forEachRemaining(item -> dropdownValues.add(item.asText()));
                    advancedControllableProperties.add(createDropdown(symphonyConfigPropertyName, dropdownValues, value));
                } else if (Constants.Paths.DataType.INTEGER.equals(configType)) {
                    String min = valueSpace.at(Constants.Paths.MIN).asText();
                    String max = valueSpace.at(Constants.Paths.MAX).asText();
                    advancedControllableProperties.add(createSlider(symphonyConfigPropertyName, Float.parseFloat(min), Float.parseFloat(max), Float.parseFloat(value)));
                } else if (Constants.Paths.DataType.STRING.equals(configType)) {
                    advancedControllableProperties.add(createText(symphonyConfigPropertyName, value));
                }
                properties.put(symphonyConfigPropertyName, value);
            });
            properties.put(Constants.PropertyNames.LAST_UPDATED, generateCurrentDateISO8601());
        } catch (Exception ex) {
            logger.warn("Unable to retrieve WebEx Configuration details for device " + deviceId, ex);
        }
    }

    /**
     * Retrieve device xAPI status and map it to list of device properties. Works only for devices that support xAPI.
     *
     * The method receives payload of the format
     * {
     *   "deviceId": "Y2lzY29zcGFyazovL3VybjpURUFNOnVzLXdlc3QtMl9yL0RFVklDRS82Mjg1ZDczNS1jMjk5LTRmY2MtOGM1Zi01NmM1MDlmMjgyNDU=",
     *   "result": {
     *     "Bookings": {
     *       "Availability": {
     *         "Status": "Free",
     *         "TimeStamp": ""
     *       },
     *       "Current": {
     *         "Id": ""
     *       }
     *     },
     *     "Cameras": {
     *       "Camera": [
     *         {
     *           "id": 1,
     *           "DetectedConnector": 0,
     *           "SoftwareID": "",
     *           "Connected": "False",
     *           "MacAddress": "",
     *           "Manufacturer": "",
     *           "Model": "",
     *           "SerialNumber": "",
     *           "HardwareID": "",
     *           "Flip": "Off",
     *           "Capabilities": {
     *             "Options": ""
     *           },
     *           "LightingConditions": "Unknown"
     *         }
     *       ],
     *       "PresenterTrack": {
     *         "Availability": "Off",
     *         "PresenterDetected": "False",
     *         "Status": "Off"
     *       },
     *       "SpeakerTrack": {
     *         "ActiveConnector": 0,
     *         "Availability": "Unavailable",
     *         "BackgroundMode": "Inactive",
     *         "Frames": {
     *           "Availability": "Unavailable",
     *           "Status": "Inactive"
     *         },
     *         "State": "Off",
     *         "Status": "Active",
     *         "ViewLimits": {
     *           "Pan": 0,
     *           "Status": "Inactive",
     *           "Tilt": 0,
     *           "Zoom": 0
     *         }
     *       }
     *     }
     *     }
     *  ]
     * And maps it to the list of properties in Symphony, using parent node as a Group name.
     *
     * @param aggregatedDevice device to generate status properties for
     * */
    private void retrieveDeviceStatus(AggregatedDevice aggregatedDevice) {
        Map<String, String> deviceProperties = aggregatedDevice.getProperties();
        if (!includeStatusUpdates) {
            if (deviceProperties != null && !deviceProperties.isEmpty()) {
                deviceProperties.keySet().removeIf(s -> s.contains(Constants.PropertyNames.STATUS_GROUP));
            }
            assignDeviceInCallStatus(aggregatedDevice, false);
            return;
        }
        long currentTimestamp = System.currentTimeMillis();
        String deviceId = aggregatedDevice.getDeviceId();
        Long validDeviceStatusRetrievalPeriodTimestamp = validDeviceStatusRetrievalPeriodTimestamps.get(deviceId);

        if (validDeviceStatusRetrievalPeriodTimestamp != null && validDeviceStatusRetrievalPeriodTimestamp > currentTimestamp) {
            logDebugMessage(String.format("Device status retrieval is in cooldown. %s seconds left",
                    (validDeviceStatusRetrievalPeriodTimestamp - currentTimestamp) / 1000));
            return;
        }
        validDeviceStatusRetrievalPeriodTimestamps.put(deviceId, currentTimestamp + deviceStatusRetrievalTimeout);

        if (!deviceProperties.containsKey(Constants.PropertyNames.API_CAPABILITIES) || !deviceProperties.containsKey(Constants.PropertyNames.API_PERMISSIONS) ||
                !deviceProperties.get(Constants.PropertyNames.API_CAPABILITIES).contains("xapi") || !deviceProperties.get(Constants.PropertyNames.API_PERMISSIONS).contains("xapi")
                || BooleanUtils.isFalse(aggregatedDevice.getDeviceOnline())) {
            assignDeviceInCallStatus(aggregatedDevice, false);
            deviceProperties.keySet().removeIf(name -> name.contains(Constants.PropertyNames.STATUS_GROUP));
            logDebugMessage(String.format("Device %s does not support or has permissions for xapi use. Skipping statistics retrieval.", deviceId));
            return;
        }
        try {
            JsonNode jsonNode = doGetWithRetry(String.format(Constants.URL.XAPI_STATUS, deviceId));
            if (jsonNode == null) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Unable to retrieve device status details.");
                }
                assignDeviceInCallStatus(aggregatedDevice, false);
                return;
            }
            if (deviceProperties != null && !deviceProperties.isEmpty()) {
                deviceProperties.keySet().removeIf(name -> name.contains(Constants.PropertyNames.STATUS_GROUP));
            }
            Map<String, String> properties = new HashMap<>();
            JsonNode elements = jsonNode.at(Constants.Paths.RESULT);
            collectStatusProperties(elements, properties, "");
            deviceProperties.putAll(properties);

            String systemState = properties.get(Constants.CallIndicators.SYSTEM_STATE);
            String teamsState = properties.get(Constants.CallIndicators.MS_EXTENSION_IN_CALL);
            String teamsNewState = properties.get(Constants.CallIndicators.MS_TEAMS_IN_CALL);

            assignDeviceInCallStatus(aggregatedDevice, Objects.equals(Constants.States.IN_CALL, systemState)
                    || Objects.equals(Constants.States.TRUE, teamsState) || Objects.equals(Constants.States.TRUE, teamsNewState));
        } catch (Exception ex) {
            logger.warn("Unable to retrieve xAPI status of device " + deviceId, ex);
        }
    }

    /**
     * Request device status details
     *
     * @param aggregatedDevice to request details for
     * @throws Exception if any error occurs
     * */
    private void requestDeviceStatus(AggregatedDevice aggregatedDevice) throws Exception {
        try {
            retrieveDeviceStatus(aggregatedDevice);
        } catch (Exception e) {
            Throwable rootCause = ExceptionUtils.getRootCause(e);
            if (ExceptionUtils.hasCause(rootCause, RetryError.class)) {
                Long retryAfter = ((RetryError)rootCause).getRetryAfter();
                if (retryAfter != null) {
                    TimeUnit.SECONDS.sleep(retryAfter);
                    requestDeviceStatus(aggregatedDevice);
                    return;
                }
            }
            throw e;
        }
    }

    /**
     * Request device configuration details
     *
     * @param aggregatedDevice to request details for
     * @throws Exception if any error occurs
     * */
    private void requestDeviceConfiguration(AggregatedDevice aggregatedDevice) throws Exception {
        try {
            generateDeviceConfigurationProperties(aggregatedDevice);
        } catch (Exception e) {
            Throwable rootCause = ExceptionUtils.getRootCause(e);
            if (ExceptionUtils.hasCause(rootCause, RetryError.class)) {
                Long retryAfter = ((RetryError)rootCause).getRetryAfter();
                if (retryAfter != null) {
                    TimeUnit.SECONDS.sleep(retryAfter);
                    requestDeviceConfiguration(aggregatedDevice);
                    return;
                }
            }
            throw e;
        }
    }

    /**
     * Assign AggregatedDevice's inCall status based on the value provided
     *
     * @param aggregatedDevice for which inCall status must be set
     * @param inCall status of the call
     * */
    private void assignDeviceInCallStatus(AggregatedDevice aggregatedDevice, boolean inCall) {
        EndpointStatistics endpointStatistics = new EndpointStatistics();
        endpointStatistics.setInCall(inCall);
        aggregatedDevice.setMonitoredStatistics(Collections.singletonList(endpointStatistics));
    }

    /**
     * Collect status properties from elements parameter and save it to properties map. The method is recursive.
     *
     * @param elements to get json data from
     * @param properties to save data to
     * @param previous previous node name
     * */
    private void collectStatusProperties(JsonNode elements, Map<String, String> properties, String previous) {
        elements.fieldNames().forEachRemaining(s -> {
            boolean isValue = false;
            String nodeName = s;

            if (StringUtils.isNullOrEmpty(previous)) {
                if (includePropertyGroups.contains(s + Constants.PropertyNames.STATUS)) {
                    availablePropertyGroups.add(s + Constants.PropertyNames.STATUS);
                } else {
                    availablePropertyGroups.add(s + Constants.PropertyNames.STATUS);
                    return;
                }
                if (elements.get(s).isArray()) {
                    nodeName = s + Constants.PropertyNames.STATUS_GROUP + nodeName;
                    processArrayStatusProperties(elements, properties, s, nodeName);
                    return;
                } else {
                    nodeName = s + Constants.PropertyNames.STATUS_GROUP;
                    properties.put(nodeName, "");
                }
            } else {
                nodeName = previous + s;

                if (includePropertyGroups.contains(nodeName.split("#")[0])) {
                    availablePropertyGroups.add(nodeName.split("#")[0]);
                } else {
                    availablePropertyGroups.add(nodeName.split("#")[0]);
                    return;
                }

                properties.remove(previous);
                if (elements.isValueNode() || elements.get(s).isValueNode()) {
                    properties.put(nodeName, elements.get(s).asText());
                    isValue = true;
                } else if (elements.get(s).isArray()) {
                    processArrayStatusProperties(elements, properties, s, nodeName);
                    return;
                } else {
                    properties.put(nodeName, "");
                }
            }
            if (!isValue) {
                collectStatusProperties(elements.get(s), properties, nodeName);
            }
        });
        properties.put(Constants.PropertyNames.LAST_UPDATED, generateCurrentDateISO8601());
    }

    /**
     * Process array properties of xAPI status, which results in Monitored properties of format
     * Audio#Input[1]Something
     * Audio#Input[2]Something
     * Audio#Input[3]Something
     * etc.
     *
     * @param elements jsonNode to pull data from
     * @param properties to save properties to
     * @param currentNode current node name
     * @param parentNodeName name of the parentnode, to add numbered square brackets to
     * */
    private void processArrayStatusProperties(JsonNode elements, Map<String, String> properties, String currentNode, String parentNodeName) {
        for (JsonNode jsonNode: elements.get(currentNode)) {
            Iterator<String> fieldNamesIterator = jsonNode.fieldNames();
            while (fieldNamesIterator.hasNext()) {
                String fieldName = fieldNamesIterator.next();
                if (fieldName.equals("id")) {
                    continue;
                }
                if (jsonNode.get(fieldName).isValueNode()) {
                    properties.put(parentNodeName + "[" + jsonNode.at(Constants.Paths.ID).asText() + "]" + fieldName, jsonNode.get(fieldName).asText());
                } else {
                    collectStatusProperties(jsonNode.get(fieldName), properties, parentNodeName + "[" + jsonNode.at(Constants.Paths.ID).asText() + "]" + fieldName);
                }
            }
        }
    }

    /**
     * Uptime is received in seconds, need to normalize it and make it human readable, like
     * 1 day(s) 5 hour(s) 12 minute(s) 55 minute(s)
     * Incoming parameter is may have a decimal point, so in order to safely process this - it's rounded first.
     * We don't need to add a segment of time if it's 0.
     *
     * @param uptimeSeconds value in seconds
     * @return string value of format 'x day(s) x hour(s) x minute(s) x minute(s)'
     */
    private String normalizeUptime(long uptimeSeconds) {
        StringBuilder normalizedUptime = new StringBuilder();

        long seconds = uptimeSeconds % 60;
        long minutes = uptimeSeconds % 3600 / 60;
        long hours = uptimeSeconds % 86400 / 3600;
        long days = uptimeSeconds / 86400;

        if (days > 0) {
            normalizedUptime.append(days).append(" day(s) ");
        }
        if (hours > 0) {
            normalizedUptime.append(hours).append(" hour(s) ");
        }
        if (minutes > 0) {
            normalizedUptime.append(minutes).append(" minute(s) ");
        }
        if (seconds > 0) {
            normalizedUptime.append(seconds).append(" second(s)");
        }
        return normalizedUptime.toString().trim();
    }

    /**
     * Logging debug message with checking if it's enabled first
     *
     * @param message to log
     * */
    private void logDebugMessage(String message) {
        if (logger.isDebugEnabled()) {
            logger.debug(message);
        }
    }

    /**
     * Update the status of the device.
     * The device is considered as paused if did not receive any retrieveMultipleStatistics()
     * calls during {@link WebExControlHubAggregatorCommunicator#validRetrieveStatisticsTimestamp}
     */
    private synchronized void updateAggregatorStatus() {
        // If the adapter is destroyed out of order, we need to make sure the device isn't paused here
        if (validRetrieveStatisticsTimestamp > 0L) {
            devicePaused = validRetrieveStatisticsTimestamp < System.currentTimeMillis();
        } else {
            devicePaused = false;
        }
    }

    /**
     * Update valid retrieve statistics timestamp. If validRetrieveStatisticsTimestamp is not updated,
     * the adapter is considered to be paused.
     * */
    private synchronized void updateValidRetrieveStatisticsTimestamp() {
        validRetrieveStatisticsTimestamp = System.currentTimeMillis() + retrieveStatisticsTimeOut;
        updateAggregatorStatus();
    }

    /**
     * Add tag to the device
     *
     * @param deviceId to add tag to
     * @param tag tad to add to the device
     * @throws Exception if any error occurs
     * */
    private synchronized void addDeviceTag(String deviceId, String tag) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("op", "add");
        request.put("path", "tags");
        request.put("value", Collections.singletonList(tag));

        JsonNode response = doPatch(Constants.URL.DEVICE_TAGS + deviceId, Collections.singletonList(request), JsonNode.class);
        boolean success = String.valueOf(response.at(Constants.Paths.TAGS)).contains(tag);
        if (success) {
            Map<String, String> properties = aggregatedDevices.get(deviceId).getProperties();
            List<String> existingTags = Arrays.stream(properties.get(Constants.PropertyNames.TAGS).split(","))
                    .map(String::trim).filter(StringUtils::isNotNullOrEmpty).collect(toList());
            existingTags.add(tag);
            properties.put(Constants.PropertyNames.TAGS, String.join(",", existingTags));
        } else {
            throw new RuntimeException("Error occurred during tag add operation");
        }
    }

    /**
     * Remove all tags from the device
     *
     * @param deviceId device to remove tags from
     * @throws Exception if any error occurs
     * */
    private synchronized void removeDeviceTags(String deviceId) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("op", "remove");
        request.put("path", "tags");
        request.put("value", "[]");
        JsonNode response = doPatch(Constants.URL.DEVICE_TAGS + deviceId, Collections.singletonList(request), JsonNode.class);
        boolean success = response.at(Constants.Paths.TAGS).isEmpty();
        if (success) {
            Map<String, String> properties = aggregatedDevices.get(deviceId).getProperties();
            properties.put(Constants.PropertyNames.TAGS, "");
        } else {
            throw new RuntimeException("Error occurred during tags remove operation");
        }
    }

    /**
     * If addressed too frequently, WebEx API may respond with 429 code, meaning that the call rate per second was reached.
     * Normally it would rarely happen due to the request rate limit, but when it does happen - adapter must retry the
     * attempts of retrieving needed information.
     * Wait timeout is set based on the Retry-After header value.
     *
     * @param url to retrieve data from
     * @return JsonNode response body
     * @throws Exception if a communication error occurs
     */
    private synchronized JsonNode doGetWithRetry(String url) throws Exception {
        int retryAttempts = 0;
        Exception lastError = null;
        boolean criticalError = false;
        while (retryAttempts++ < 10 && !devicePaused) {
            try {
                return doGet(url, JsonNode.class);
            } catch (CommandFailureException e) {
                lastError = e;
                if (e.getStatusCode() != 429) {
                    // Might be 401, 403 or any other error code here so the code will just get stuck
                    // cycling this failed request until it's fixed. So we need to skip this scenario.
                    criticalError = true;
                    logger.error(String.format("WebEx API error %s while retrieving %s data", e.getStatusCode(), url), e);
                    break;
                }
            } catch (Exception e) {
                Throwable rootCause = ExceptionUtils.getRootCause(e);
                if (ExceptionUtils.hasCause(rootCause, RetryError.class)) {
                    throw e;
                }
                lastError = e;
                // if service is running, log error
                if (!devicePaused) {
                    criticalError = true;
                    logger.error(String.format("WebEx API error while retrieving %s data", url), e);
                }
                break;
            }
        }

        if (retryAttempts == 10 && !devicePaused || criticalError) {
            // if we got here, all 10 attempts failed, or this is a login error that doesn't imply retry attempts
            if (lastError instanceof CommandFailureException) {
                int code = ((CommandFailureException)lastError).getStatusCode();
                if (code == HttpStatus.UNAUTHORIZED.value() || code == HttpStatus.FORBIDDEN.value()) {
                    saveActiveErrors(code, lastError.getMessage());
                    return null;
                }
                saveActiveErrors(code, lastError.getMessage());
            } else if (lastError instanceof FailedLoginException) {
                saveActiveErrors(401, lastError.getMessage());
                return null;
            } else {
                saveActiveErrors(0, "Unknown Error");
            }
        }
        return null;
    }

    /**
     * Cleanup any active error, that was previously saved
     * */
    private void cleanupActiveErrors() {
        lastErrorCode = 0;
        lastErrorMessage = null;
    }

    /**
     * Save an error that must be propagated to the UI
     * */
    private void saveActiveErrors(int errorCode, String message) {
        lastErrorCode = errorCode;
        lastErrorMessage = message;
    }

    /**
     * Generate current date in ISO8601 String format
     *
     * @return String value of ISO8601 date
     * */
    private String generateCurrentDateISO8601() {
        Date currentDate = new Date();
        ZonedDateTime zonedDateTime = currentDate.toInstant().atZone(java.time.ZoneId.of("UTC"));
        DateTimeFormatter formatter = DateTimeFormatter.ISO_INSTANT;
        return formatter.format(zonedDateTime);
    }
}
