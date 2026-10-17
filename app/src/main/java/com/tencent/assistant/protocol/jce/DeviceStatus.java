package com.tencent.assistant.protocol.jce;

import java.io.Serializable;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class DeviceStatus implements Serializable {

    /* renamed from: c, reason: collision with root package name */
    public static final DeviceStatus[] f4722c = new DeviceStatus[4];

    /* renamed from: b, reason: collision with root package name */
    public String f4723b;

    static {
        new DeviceStatus(0, "OFFLINE");
        new DeviceStatus(1, "ONLINE");
        new DeviceStatus(2, "CONNECTING");
        new DeviceStatus(3, "DISCONNECT");
    }

    public DeviceStatus(int i, String str) {
        this.f4723b = str;
        f4722c[i] = this;
    }

    public final String toString() {
        return this.f4723b;
    }
}
