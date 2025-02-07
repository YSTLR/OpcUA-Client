package com.bosch.njp1;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class NodeAccessMonitor {
    private ConcurrentHashMap<String, AtomicLong> accessCount = new ConcurrentHashMap<>();
}
