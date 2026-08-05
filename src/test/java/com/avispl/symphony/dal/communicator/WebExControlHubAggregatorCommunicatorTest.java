/*
 * Copyright (c) 2023 AVI-SPL, Inc. All Rights Reserved.
 */
package com.avispl.symphony.dal.communicator;

import com.avispl.symphony.api.dal.dto.control.AdvancedControllableProperty;
import com.avispl.symphony.api.dal.dto.control.ControllableProperty;
import com.avispl.symphony.api.dal.dto.monitor.EndpointStatistics;
import com.avispl.symphony.api.dal.dto.monitor.ExtendedStatistics;
import com.avispl.symphony.api.dal.dto.monitor.Statistics;
import com.avispl.symphony.api.dal.dto.monitor.aggregator.AggregatedDevice;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class WebExControlHubAggregatorCommunicatorTest {

    WebExControlHubAggregatorCommunicator communicator;

    @BeforeEach
    public void setUp() throws Exception {
        communicator = new WebExControlHubAggregatorCommunicator();
        communicator.setHost("");
        communicator.setLogin("");
        communicator.setPassword("");
        communicator.setAuthorizationMode("");
        communicator.setRefreshToken("");
        communicator.setProtocol("");
    }

    @Test
    public void testGetMultipleStatisticsWithAppAccess() throws Exception {
        communicator.setDeviceMetaDataRetrievalTimeout(90000);
        communicator.setIncludePropertyGroups("AudioStatus,BluetoothStatus,BookingsStatus,CamerasStatus,CapabilitiesStatus,ConferenceStatus,DiagnosticsStatus,PeripheralsStatus,ProvisioningStatus,\n" +
                "ProximityStatus,RoomAnalyticsStatus,RoomPresetStatus,SIPStatus,StandbyStatus,SystemUnitStatus,ThousandEyesStatus,TimeStatus,UserInterfaceStatus,\n" +
                "VideoStatus,WebEngineStatus,WebexStatus,WebRTCStatus,NetworkStatus,NetworkServicesStatus,MicrosoftTeamsStatus,\n" +
                "AppsConfiguration,AudioConfiguration,AudioInputConfiguration,AudioOutputConfiguration,BluetoothConfiguration,BookingsConfiguration,BYODConfiguration,\n" +
                "CallHistoryConfiguration,CamerasConfiguration,ConferenceConfiguration,FacilityServiceConfiguration,FilesConfiguration,HttpClientConfiguration,HttpFeedbackConfiguration,LoggingConfiguration,MacrosConfiguration,\n" +
                "MariConfiguration,MicrosoftTeamsConfiguration,NetworkConfiguration,NetworkServicesConfiguration,PeripheralsConfiguration,PhonebookConfiguration,ProvisioningConfiguration,ProximityConfiguration,\n" +
                "RoomAnalyticsConfiguration,RoomCleanupConfiguration,RoomSchedulerConfiguration,RTPConfiguration,SecurityConfiguration,SerialPortConfiguration,SIPConfiguration,StandbyConfiguration,SystemUnitConfiguration,ThousandEyesConfiguration,\n" +
                "TimeConfiguration,UserInterfaceConfiguration,UserManagementConfiguration,VideoConfiguration,VideoInputConfiguration,VideoOutputConfiguration,VoiceControlConfiguration,WebEngineConfiguration,WebexConfiguration,WebRTCConfiguration,ZoomConfiguration");
        //communicator.setTagDeviceFilter("NewTag");
     //   communicator.setTypeDeviceFilter("roomdesk");
        //communicator.setProductDeviceFilter("DX-80, RoomKit, SX-80");
        communicator.setDeviceRetrievalPageSize(1);
        communicator.init();
        communicator.retrieveMultipleStatistics();
        communicator.getMultipleStatistics();
        Thread.sleep(30000);
        communicator.retrieveMultipleStatistics();
        Thread.sleep(30000);
        communicator.retrieveMultipleStatistics();
        Thread.sleep(30000);
        communicator.retrieveMultipleStatistics();

        List<AggregatedDevice> aggregatedDevices = communicator.retrieveMultipleStatistics();
        List<Statistics> statistics = communicator.getMultipleStatistics();
        Assertions.assertNotNull(statistics);
        Assertions.assertNotNull(aggregatedDevices);
        Assertions.assertFalse(aggregatedDevices.isEmpty());
    }

    @Test
    public void testControllableConfigurationProperty() throws Exception {
        communicator.init();
        communicator.setIncludePropertyGroups("VideoInput, AudioStatus");
        List<AggregatedDevice> statistics = communicator.retrieveMultipleStatistics();
        Assertions.assertNotNull(statistics);
        ControllableProperty controllableProperty = new ControllableProperty();
        controllableProperty.setProperty("Zoom#DefaultDomain");
        controllableProperty.setValue(10.0);
        controllableProperty.setDeviceId("=");
        communicator.controlProperty(controllableProperty);
    }

    @Test
    public void testAddDeviceTag() throws Exception {
        communicator.init();
        communicator.setIncludePropertyGroups("VideoInput, AudioStatus");
        List<AggregatedDevice> statistics = communicator.retrieveMultipleStatistics();
        Assertions.assertNotNull(statistics);

        ControllableProperty controllableProperty = new ControllableProperty();
        controllableProperty.setProperty("DeviceTags#AddTag");
        controllableProperty.setValue("SomeNewTag1");
        controllableProperty.setDeviceId("");
        communicator.controlProperty(controllableProperty);
    }

    @Test
    public void testRemoveDeviceTags() throws Exception {
        communicator.init();
        communicator.setIncludePropertyGroups("VideoInput, AudioStatus");
        List<AggregatedDevice> statistics = communicator.retrieveMultipleStatistics();
        Assertions.assertNotNull(statistics);

        ControllableProperty controllableProperty = new ControllableProperty();
        controllableProperty.setProperty("DeviceTags#RemoveAll");
        controllableProperty.setValue("SomeNewTag1");
        controllableProperty.setDeviceId("");
        communicator.controlProperty(controllableProperty);
    }

    @Test
    public void testRebootDevice() throws Exception {
        communicator.init();
        communicator.setIncludePropertyGroups("SystemUnitStatus");
        List<AggregatedDevice> devices = communicator.retrieveMultipleStatistics();
        Assertions.assertNotNull(devices);
        Assertions.assertFalse(devices.isEmpty());

        AggregatedDevice device = devices.stream()
                .filter(aggregatedDevice -> aggregatedDevice.getControllableProperties().stream()
                        .anyMatch(control -> Constants.PropertyNames.REBOOT.equals(control.getName())))
                .findFirst()
                .orElse(devices.get(0));

        ControllableProperty controllableProperty = new ControllableProperty();
        controllableProperty.setProperty(Constants.PropertyNames.REBOOT);
        controllableProperty.setDeviceId(device.getDeviceId());
        communicator.controlProperty(controllableProperty);
    }

    /**
     * Regression test for updateRebootControl(): once the Reboot button control is removed because a
     * device stops supporting xAPI commands, it must be re-added once xAPI support is detected again.
     * Invoked via reflection since updateRebootControl() is private and this test targets it directly
     * without going through any network call.
     *
     * Initial controllableProperties is seeded with a Reboot Button already present, matching what
     * aggregatedDeviceProcessor.extractDevices() produces from the mapping before updateRebootControl()
     * ever runs in production, rather than starting from an empty list.
     * */
    @Test
    public void testUpdateRebootControlReAddsButtonAfterXapiSupportRestored() throws Exception {
        AggregatedDevice device = new AggregatedDevice();
        device.setDeviceId("test-device-id");
        device.setDeviceOnline(true);
        Map<String, String> properties = new HashMap<>();
        properties.put(Constants.PropertyNames.API_CAPABILITIES, "[xapi]");
        properties.put(Constants.PropertyNames.API_PERMISSIONS, "[xapi]");
        properties.put(Constants.PropertyNames.REBOOT, "Reboot");
        device.setProperties(properties);

        AdvancedControllableProperty.Button button = new AdvancedControllableProperty.Button();
        button.setLabel("Reboot");
        button.setLabelPressed("Rebooting");
        button.setGracePeriod(Constants.PropertyNames.REBOOT_GRACE_PERIOD_SECONDS);
        List<AdvancedControllableProperty> controllableProperties = new ArrayList<>();
        controllableProperties.add(new AdvancedControllableProperty(Constants.PropertyNames.REBOOT, new Date(), button, "Reboot"));
        device.setControllableProperties(controllableProperties);

        Method updateRebootControl = WebExControlHubAggregatorCommunicator.class.getDeclaredMethod("updateRebootControl", AggregatedDevice.class);
        updateRebootControl.setAccessible(true);

        // Initial state, as produced by extractDevices() from the mapping: Reboot button present
        Assertions.assertTrue(device.getControllableProperties().stream()
                .anyMatch(control -> Constants.PropertyNames.REBOOT.equals(control.getName())));

        // Device goes offline, no longer supports xAPI commands: Reboot button must be removed
        device.setDeviceOnline(false);
        updateRebootControl.invoke(communicator, device);
        Assertions.assertTrue(device.getControllableProperties().stream()
                .noneMatch(control -> Constants.PropertyNames.REBOOT.equals(control.getName())));

        // Device comes back online, supports xAPI commands again: Reboot button must be re-added
        device.setDeviceOnline(true);
        updateRebootControl.invoke(communicator, device);
        Assertions.assertTrue(device.getControllableProperties().stream()
                        .anyMatch(control -> Constants.PropertyNames.REBOOT.equals(control.getName())),
                "Reboot button should be re-added once the device supports xAPI commands again");
    }

    /**
     * Regression test for retrieveDeviceStatus(): the raw SystemUnit/State/System value read off the xAPI
     * status response must be exposed under Constants.PropertyNames.DEVICE_STATE unconditionally, even when
     * includePropertyGroups is empty and collectStatusProperties() would otherwise drop the SystemUnit group.
     * Invoked via reflection since retrieveDeviceStatus() is private. The xAPI status response is stubbed by
     * overriding doGet() on an anonymous subclass, and the background polling thread is stopped immediately
     * after construction, so the test performs no network calls.
     * */
    @Test
    public void testRetrieveDeviceStatusExposesDeviceStateWhenSleeping() throws Exception {
        WebExControlHubAggregatorCommunicator testCommunicator = newNetworkFreeCommunicator(buildXapiStatusResponse("Sleeping"));
        AggregatedDevice device = buildXapiCapableDevice("device-sleeping");

        invokeRetrieveDeviceStatus(testCommunicator, device);

        Assertions.assertEquals("Sleeping", device.getProperties().get(Constants.PropertyNames.DEVICE_STATE));
        EndpointStatistics endpointStatistics = (EndpointStatistics) device.getMonitoredStatistics().get(0);
        Assertions.assertFalse(endpointStatistics.isInCall(), "Device must not be marked in-call while Sleeping");
    }

    /**
     * Companion to {@link #testRetrieveDeviceStatusExposesDeviceStateWhenSleeping()}: confirms the new
     * DEVICE_STATE property does not affect the existing in-call detection when SystemUnit/State/System
     * reports InCall.
     * */
    @Test
    public void testRetrieveDeviceStatusKeepsInCallBehaviourWhenInCall() throws Exception {
        WebExControlHubAggregatorCommunicator testCommunicator = newNetworkFreeCommunicator(buildXapiStatusResponse(Constants.States.IN_CALL));
        AggregatedDevice device = buildXapiCapableDevice("device-in-call");

        invokeRetrieveDeviceStatus(testCommunicator, device);

        Assertions.assertEquals(Constants.States.IN_CALL, device.getProperties().get(Constants.PropertyNames.DEVICE_STATE));
        EndpointStatistics endpointStatistics = (EndpointStatistics) device.getMonitoredStatistics().get(0);
        Assertions.assertTrue(endpointStatistics.isInCall(), "Device must be marked in-call when SystemUnit/State/System is InCall");
    }

    private static JsonNode buildXapiStatusResponse(String systemState) throws Exception {
        String json = "{\"result\":{\"SystemUnit\":{\"State\":{\"System\":\"" + systemState + "\"}}}}";
        return new ObjectMapper().readTree(json);
    }

    private static AggregatedDevice buildXapiCapableDevice(String deviceId) {
        AggregatedDevice device = new AggregatedDevice();
        device.setDeviceId(deviceId);
        device.setDeviceOnline(true);
        Map<String, String> properties = new HashMap<>();
        properties.put(Constants.PropertyNames.API_CAPABILITIES, "[xapi]");
        properties.put(Constants.PropertyNames.API_PERMISSIONS, "[xapi]");
        device.setProperties(properties);
        return device;
    }

    /**
     * Builds a communicator whose xAPI status response is stubbed via an overridden doGet() (so the request
     * never leaves the JVM), and whose background polling thread is destroyed immediately after construction,
     * before it can reach its first fetchDevicesList() call. devicePaused is then flipped to false directly,
     * bypassing retrieveMultipleStatistics()/fetchDevicesList(), so doGetWithRetry() will actually invoke the
     * stubbed doGet() instead of short-circuiting to null.
     * */
    private static WebExControlHubAggregatorCommunicator newNetworkFreeCommunicator(JsonNode statusResponse) throws Exception {
        WebExControlHubAggregatorCommunicator testCommunicator = new WebExControlHubAggregatorCommunicator() {
            @Override
            @SuppressWarnings("unchecked")
            protected <Response> Response doGet(String uri, Class<Response> responseClass) throws Exception {
                if (responseClass == JsonNode.class && uri.contains("xapi/status")) {
                    return (Response) statusResponse;
                }
                throw new UnsupportedOperationException("Unexpected network call in test: " + uri);
            }
        };
        testCommunicator.internalDestroy();

        Field devicePausedField = WebExControlHubAggregatorCommunicator.class.getDeclaredField("devicePaused");
        devicePausedField.setAccessible(true);
        devicePausedField.set(testCommunicator, false);

        return testCommunicator;
    }

    private static void invokeRetrieveDeviceStatus(WebExControlHubAggregatorCommunicator communicator, AggregatedDevice device) throws Exception {
        Method retrieveDeviceStatus = WebExControlHubAggregatorCommunicator.class.getDeclaredMethod("retrieveDeviceStatus", AggregatedDevice.class);
        retrieveDeviceStatus.setAccessible(true);
        retrieveDeviceStatus.invoke(communicator, device);
    }

    @Test
    public void testGetMultipleStatisticsWithBotAccess() throws Exception {
        communicator.setAuthorizationMode("Bot");
        communicator.setPassword("");
        communicator.init();
        List<Statistics> statistics = communicator.getMultipleStatistics();
        Assertions.assertNotNull(statistics);
        Assertions.assertFalse(statistics.isEmpty());
        Assertions.assertFalse(((ExtendedStatistics)statistics.get(0)).getStatistics().isEmpty());
    }

    @Test
    public void testRetrieveMultipleStatisticsWithBotAccess() throws Exception {
        communicator.setAuthorizationMode("Bot");
        communicator.setPassword("");
        communicator.init();
        communicator.retrieveMultipleStatistics();
        Thread.sleep(30000);
        communicator.retrieveMultipleStatistics();
        Thread.sleep(30000);
        communicator.retrieveMultipleStatistics();
        Thread.sleep(30000);
        List<AggregatedDevice> devices = communicator.retrieveMultipleStatistics();
        Assertions.assertNotNull(devices);
        Assertions.assertFalse(devices.isEmpty());
        for (AggregatedDevice aggregatedDevice: devices) {
            Map<String, String> properties = aggregatedDevice.getProperties();
            Assertions.assertFalse(properties.isEmpty());
        }
    }
}
