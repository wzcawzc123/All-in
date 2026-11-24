package com.gaeul.allin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Atomic ownership registry for NativeBridge LocalBridge.Call instances.
 * Registers a cancellable call BEFORE worker submission so fast cancelRequest(reqId)
 * cannot race ahead of worker startup, and guarantees cleanup on rejection or completion.
 */
final class BridgeRequestRegistry {
    private final Set<LocalBridge.Call> activeCalls = new HashSet<LocalBridge.Call>();
    private final Map<String, LocalBridge.Call> byId = new HashMap<String, LocalBridge.Call>();
    private boolean destroyed;
    private long pageGeneration;

    synchronized long generation() {
        return pageGeneration;
    }

    boolean register(String reqId, LocalBridge.Call call, long expectedGeneration) {
        LocalBridge.Call replaced = null;
        synchronized (this) {
            if (destroyed || pageGeneration != expectedGeneration) {
                call.cancel();
                return false;
            }
            activeCalls.add(call);
            if (reqId != null && !reqId.isEmpty()) {
                replaced = byId.put(reqId, call);
                if (replaced != null && replaced != call) {
                    activeCalls.remove(replaced);
                }
            }
        }
        if (replaced != null && replaced != call) {
            replaced.cancel();
        }
        return true;
    }

    boolean cancelById(String reqId) {
        if (reqId == null || reqId.isEmpty()) return false;
        LocalBridge.Call target;
        synchronized (this) {
            target = byId.remove(reqId);
            if (target != null) activeCalls.remove(target);
        }
        if (target != null) {
            target.cancel();
            return true;
        }
        return false;
    }

    void release(String reqId, LocalBridge.Call call) {
        synchronized (this) {
            activeCalls.remove(call);
            if (reqId != null && !reqId.isEmpty() && byId.get(reqId) == call) {
                byId.remove(reqId);
            }
        }
        call.close();
    }

    void cancelAll(boolean markDestroyed) {
        List<LocalBridge.Call> snapshot;
        synchronized (this) {
            if (markDestroyed) destroyed = true;
            pageGeneration++;
            snapshot = new ArrayList<LocalBridge.Call>(activeCalls);
            activeCalls.clear();
            byId.clear();
        }
        for (LocalBridge.Call call : snapshot) {
            call.cancel();
        }
    }

    synchronized int activeCount() {
        return activeCalls.size();
    }

    synchronized int idCount() {
        return byId.size();
    }
}
