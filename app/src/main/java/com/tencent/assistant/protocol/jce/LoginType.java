package com.tencent.assistant.protocol.jce;

import java.io.Serializable;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class LoginType implements Serializable {

    /* renamed from: c, reason: collision with root package name */
    public static final LoginType[] f4726c = new LoginType[3];

    /* renamed from: b, reason: collision with root package name */
    public final String f4727b;

    static {
        new LoginType(0, "YYB");
        new LoginType(1, "qq");
        new LoginType(2, "WECHAT");
    }

    public LoginType(int i, String str) {
        this.f4727b = str;
        f4726c[i] = this;
    }

    public final String toString() {
        return this.f4727b;
    }
}
