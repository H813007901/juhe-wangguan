package com.tencent.assistant.protocol.jce;

import java.io.Serializable;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class ResponseHeadErrorCode implements Serializable {

    /* renamed from: c, reason: collision with root package name */
    public static final ResponseHeadErrorCode[] f4730c = new ResponseHeadErrorCode[12];

    /* renamed from: b, reason: collision with root package name */
    public final String f4731b;

    static {
        new ResponseHeadErrorCode(0, "EC_AUTH_ACCOUNT_ABNORMAL");
        new ResponseHeadErrorCode(1, "EC_AUTH_PWD_CHANGE");
        new ResponseHeadErrorCode(2, "EC_AUTH_EXPIRED");
        new ResponseHeadErrorCode(3, "EC_ACCESS_CONTROL_FAILED");
        new ResponseHeadErrorCode(4, "EC_SERVER_INTERNAL_ERROR");
        new ResponseHeadErrorCode(5, "EC_BUSINESS_ERROR");
        new ResponseHeadErrorCode(6, "EC_CS_TICKET_EXPIRED");
        new ResponseHeadErrorCode(7, "EC_CS_TICKET_INVALID");
        new ResponseHeadErrorCode(8, "EC_COMMON_ERROR");
        new ResponseHeadErrorCode(9, "EC_CS_TICKET_ERROR");
        new ResponseHeadErrorCode(10, "EC_AUTH_RESET");
        new ResponseHeadErrorCode(11, "EC_BLOCKED");
    }

    public ResponseHeadErrorCode(int i, String str) {
        this.f4731b = str;
        f4730c[i] = this;
    }

    public final String toString() {
        return this.f4731b;
    }
}
