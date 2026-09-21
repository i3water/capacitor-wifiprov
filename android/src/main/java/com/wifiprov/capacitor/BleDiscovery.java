package com.wifiprov.capacitor;

import android.bluetooth.le.ScanResult;
import android.content.Context;

// Optional host-owned scan resource. Does not implement Espressif provisioning.
public interface BleDiscovery {
    interface Listener {
        void result(ScanResult result);
        void ended(String error);
    }
    interface Completion { void complete(String error); }
    interface Factory { BleDiscovery create(Context context); }
    void start(String prefix, Listener listener);
    void stop(Completion completion);
}
