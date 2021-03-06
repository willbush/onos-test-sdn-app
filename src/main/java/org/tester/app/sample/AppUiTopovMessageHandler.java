/*
 * Copyright 2016-present Open Networking Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.tester.app.sample;

import org.onosproject.incubator.net.PortStatisticsService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import org.onlab.osgi.ServiceDirectory;
import org.onosproject.net.*;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.host.HostService;
import org.onosproject.net.link.LinkService;
import org.onosproject.net.statistic.Load;
import org.onosproject.ui.RequestHandler;
import org.onosproject.ui.UiConnection;
import org.onosproject.ui.UiMessageHandler;
import org.onosproject.ui.topo.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static org.onosproject.net.DefaultEdgeLink.createEdgeLink;

/**
 * Skeletal ONOS UI Topology-Overlay message handler.
 */
public class AppUiTopovMessageHandler extends UiMessageHandler {
    private static final String SAMPLE_TOPOV_DISPLAY_START = "sampleTopovDisplayStart";
    private static final String SAMPLE_TOPOV_DISPLAY_UPDATE = "sampleTopovDisplayUpdate";
    private static final String SAMPLE_TOPOV_DISPLAY_STOP = "sampleTopovDisplayStop";
    private static final String TRAFFIC_THRESHOLD_UPDATE = "trafficThresholdUpdate";

    private static final long UPDATE_PERIOD_MS = 200;

    private static final Link[] EMPTY_LINK_SET = new Link[0];

    private static double KBps_threshold = 0.0;

    private enum Mode {IDLE, MONITOR}

    private final Logger log = LoggerFactory.getLogger(getClass());

    private DeviceService deviceService;
    private HostService hostService;
    private LinkService linkService;
    private PortStatisticsService portStatisticsService;

    private final Timer timer = new Timer("sample-overlay");
    private TimerTask demoTask = null;
    private Mode currentMode = Mode.IDLE;
    private Link[] linkSet = EMPTY_LINK_SET;

    @Override
    public void init(UiConnection connection, ServiceDirectory directory) {
        super.init(connection, directory);
        deviceService = directory.get(DeviceService.class);
        hostService = directory.get(HostService.class);
        linkService = directory.get(LinkService.class);
        portStatisticsService = directory.get(PortStatisticsService.class);
    }

    @Override
    protected Collection<RequestHandler> createRequestHandlers() {
        return ImmutableSet.of(
                new DisplayStartHandler(),
                new DisplayUpdateHandler(),
                new DisplayStopHandler(),
                new ThresholdHandler()
        );
    }

    // Handler classes

    private final class ThresholdHandler extends RequestHandler {

        public ThresholdHandler() {
            super(TRAFFIC_THRESHOLD_UPDATE);
        }

        @Override
        public void process(long sid, ObjectNode payload) {
            String threshold = string(payload, "threshold");

            if (!Strings.isNullOrEmpty(threshold)) {
                int numOfKiloBytes = Integer.valueOf(threshold);
                KBps_threshold = numOfKiloBytes * TopoUtils.KILO;
                log.debug("threshold set to: ", KBps_threshold);
            }
        }
    }

    private final class DisplayStartHandler extends RequestHandler {
        public DisplayStartHandler() {
            super(SAMPLE_TOPOV_DISPLAY_START);
        }

        @Override
        public void process(long sid, ObjectNode payload) {
            String mode = string(payload, "mode");

            log.debug("Start Display: mode [{}]", mode);
            clearState();
            clearAllHighlightedLinksInTopology();

            switch (mode) {
                case "monitor":
                    currentMode = Mode.MONITOR;
                    scheduleTask();
                    initLinkSet();
                    sendAllPortTraffic();
                    break;

                default:
                    currentMode = Mode.IDLE;
                    cancelTask();
                    break;
            }
        }
    }

    private final class DisplayUpdateHandler extends RequestHandler {
        public DisplayUpdateHandler() {
            super(SAMPLE_TOPOV_DISPLAY_UPDATE);
        }

        @Override
        public void process(long sid, ObjectNode payload) {
            if (currentMode == Mode.MONITOR)
                sendAllPortTraffic();
            else
                clearAllHighlightedLinksInTopology();
        }
    }

    private final class DisplayStopHandler extends RequestHandler {
        public DisplayStopHandler() {
            super(SAMPLE_TOPOV_DISPLAY_STOP);
        }

        @Override
        public void process(long sid, ObjectNode payload) {
            log.debug("Stop Display");
            cancelTask();
            clearState();
            clearAllHighlightedLinksInTopology();
        }
    }

    private void clearAllHighlightedLinksInTopology() {
        sendHighlights(new Highlights());
    }

    private void clearState() {
        currentMode = Mode.IDLE;
        linkSet = EMPTY_LINK_SET;
    }

    private void sendHighlights(Highlights highlights) {
        sendMessage(TopoJson.highlightsMessage(highlights));
    }

    private void initLinkSet() {
        Set<Link> links = new HashSet<>();
        for (Link link : linkService.getActiveLinks()) {
            links.add(link);
        }
        linkSet = links.toArray(new Link[links.size()]);
        log.debug("initialized link set to {}", linkSet.length);
    }

    private void sendAllPortTraffic() {
        log.debug("sendAllPortTraffic");
        sendHighlights(buildHighlights());
    }

    private Highlights buildHighlights() {
        Highlights highlights = new Highlights();
        TrafficLinkMap linkMap = new TrafficLinkMap();

        linkService.getLinks().forEach(linkMap::add);

        hostService.getHosts().forEach(host -> {
            linkMap.add(createEdgeLink(host, true));
            linkMap.add(createEdgeLink(host, false));
        });

        for (TrafficLink link : linkMap.biLinks()) {
            attachPortLoad(link);
            // we only want to report on links deemed to have traffic
            if (link.hasTraffic()) {
                highlights.add(link.highlight(TrafficLink.StatsType.PORT_STATS));
            }
        }
        return highlights;
    }

    private void attachPortLoad(TrafficLink link) {
        // For bi-directional traffic links, use
        // the max link rate of either direction
        // (we choose 'one' since we know that is never null)
        Link one = link.one();
        Load egressSrc = portStatisticsService.load(one.src());
        Load egressDst = portStatisticsService.load(one.dst());
        link.addLoad(maxLoad(egressSrc, egressDst), KBps_threshold);
    }

    private Load maxLoad(Load a, Load b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.rate() > b.rate() ? a : b;
    }


    private synchronized void scheduleTask() {
        if (demoTask == null) {
            log.debug("Starting up demo task...");
            demoTask = new DisplayUpdateTask();
            timer.schedule(demoTask, UPDATE_PERIOD_MS, UPDATE_PERIOD_MS);
        } else {
            log.debug("(demo task already running");
        }
    }

    private synchronized void cancelTask() {
        if (demoTask != null) {
            demoTask.cancel();
            demoTask = null;
        }
    }

    private class DisplayUpdateTask extends TimerTask {
        @Override
        public void run() {
            try {
                if (currentMode == Mode.MONITOR)
                    sendAllPortTraffic();
            } catch (Exception e) {
                log.warn("Unable to process demo task: {}", e.getMessage());
                log.debug("Oops", e);
            }
        }
    }
}