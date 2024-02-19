/*
 * Copyright (c) 2023 AVI-SPL, Inc. All Rights Reserved.
 */
package com.avispl.symphony.dal.communicator;

import com.avispl.symphony.api.dal.dto.control.ControllableProperty;
import com.avispl.symphony.api.dal.dto.monitor.ExtendedStatistics;
import com.avispl.symphony.api.dal.dto.monitor.Statistics;
import com.avispl.symphony.api.dal.dto.monitor.aggregator.AggregatedDevice;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

public class WebExControlHubAggregatorCommunicatorTest {

    WebExControlHubAggregatorCommunicator communicator;

    @BeforeEach
    public void setUp() throws Exception {
        communicator = new WebExControlHubAggregatorCommunicator();
        communicator.setHost("webexapis.com");
        communicator.setLogin("");
        communicator.setPassword("");
        communicator.setAuthorizationMode("Integration");
        communicator.setRefreshToken("");
        communicator.setProtocol("https");
    }

    @Test
    public void testGetMultipleStatisticsWithAppAccess() throws Exception {
        communicator.setDeviceMetaDataRetrievalTimeout(90000);
        communicator.setIncludePropertyGroups("PeripheralsStatus, PeripheralsConfiguration");
        //communicator.setTagDeviceFilter("NewTag");
        communicator.setTypeDeviceFilter("roomdesk");
        communicator.init();
        communicator.retrieveMultipleStatistics();
        communicator.getMultipleStatistics();
        Thread.sleep(30000);
        communicator.retrieveMultipleStatistics();
        Thread.sleep(30000);
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
        controllableProperty.setDeviceId("Y2lzY29zcGFyazovL3VybjpURUFNOnVzLXdlc3QtMl9yL0RFVklDRS82Mjg1ZDczNS1jMjk5LTRmY2MtOGM1Zi01NmM1MDlmMjgyNDU=");
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
        controllableProperty.setDeviceId("Y2lzY29zcGFyazovL3VybjpURUFNOnVzLXdlc3QtMl9yL0RFVklDRS82Mjg1ZDczNS1jMjk5LTRmY2MtOGM1Zi01NmM1MDlmMjgyNDU=");
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
        controllableProperty.setDeviceId("Y2lzY29zcGFyazovL3VybjpURUFNOnVzLXdlc3QtMl9yL0RFVklDRS82Mjg1ZDczNS1jMjk5LTRmY2MtOGM1Zi01NmM1MDlmMjgyNDU=");
        communicator.controlProperty(controllableProperty);
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
        List<AggregatedDevice> devices = communicator.retrieveMultipleStatistics();
        Assertions.assertNotNull(devices);
        Assertions.assertFalse(devices.isEmpty());
        for (AggregatedDevice aggregatedDevice: devices) {
            Map<String, String> properties = aggregatedDevice.getProperties();
            Assertions.assertFalse(properties.isEmpty());
        }
    }
}
