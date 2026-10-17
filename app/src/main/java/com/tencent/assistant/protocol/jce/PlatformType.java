package com.tencent.assistant.protocol.jce;

import java.io.Serializable;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class PlatformType implements Serializable {

    /* renamed from: c, reason: collision with root package name */
    public static final PlatformType[] f4728c = new PlatformType[8];

    /* renamed from: b, reason: collision with root package name */
    public final String f4729b;

    static {
        new PlatformType(0, "UNKNOWN_APP_ID");
        new PlatformType(1, "ANDROID_YYB");
        new PlatformType(2, "WINDOWS_YYB");
        new PlatformType(3, "ANDROID_MARVIS");
        new PlatformType(4, "IOS_MARVIS");
        new PlatformType(5, "PERSONAL_OPENCLAW");
        new PlatformType(6, "CLOUD_MARVIS");
        new PlatformType(7, "MAC_MARVIS");
    }

    public PlatformType(int i, String str) {
        this.f4729b = str;
        f4728c[i] = this;
    }

    public final String toString() {
        return this.f4729b;
    }
}
