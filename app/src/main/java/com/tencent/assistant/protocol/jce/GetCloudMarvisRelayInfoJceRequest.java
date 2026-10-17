package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class GetCloudMarvisRelayInfoJceRequest extends JceStruct {
    public boolean is_support_wss;
    public String pcGuid;
    public int platform;
    public String sessionId;

    public GetCloudMarvisRelayInfoJceRequest() {
        this.platform = 0;
        this.pcGuid = "";
        this.sessionId = "";
        this.is_support_wss = false;
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.platform = jceInputStream.read(this.platform, 0, true);
        this.pcGuid = jceInputStream.readString(1, true);
        this.sessionId = jceInputStream.readString(2, true);
        this.is_support_wss = jceInputStream.read(this.is_support_wss, 3, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write(this.platform, 0);
        String str = this.pcGuid;
        if (str != null) {
            jceOutputStream.write(str, 1);
        }
        String str2 = this.sessionId;
        if (str2 != null) {
            jceOutputStream.write(str2, 2);
        }
        jceOutputStream.write(this.is_support_wss, 3);
    }

    public GetCloudMarvisRelayInfoJceRequest(int i, String str, String str2) {
        this.is_support_wss = false;
        this.platform = i;
        this.pcGuid = str;
        this.sessionId = str2;
    }

    public GetCloudMarvisRelayInfoJceRequest(int i, String str, String str2, boolean z2) {
        this.platform = i;
        this.pcGuid = str;
        this.sessionId = str2;
        this.is_support_wss = z2;
    }
}
