package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class AllocateCloudMarvisJceRequest extends JceStruct {
    public int cloudMarvisPlan;
    public boolean hasIoa;
    public String orderNo;
    public int platform;
    public String sessionId;
    public String wifi;

    public AllocateCloudMarvisJceRequest() {
        this.platform = 0;
        this.sessionId = "";
        this.wifi = "";
        this.hasIoa = false;
        this.cloudMarvisPlan = 0;
        this.orderNo = "";
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.platform = jceInputStream.read(this.platform, 0, true);
        this.sessionId = jceInputStream.readString(1, true);
        this.wifi = jceInputStream.readString(2, false);
        this.hasIoa = jceInputStream.read(this.hasIoa, 3, false);
        this.cloudMarvisPlan = jceInputStream.read(this.cloudMarvisPlan, 4, false);
        this.orderNo = jceInputStream.readString(5, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write(this.platform, 0);
        String str = this.sessionId;
        if (str != null) {
            jceOutputStream.write(str, 1);
        }
        String str2 = this.wifi;
        if (str2 != null) {
            jceOutputStream.write(str2, 2);
        }
        jceOutputStream.write(this.hasIoa, 3);
        jceOutputStream.write(this.cloudMarvisPlan, 4);
        String str3 = this.orderNo;
        if (str3 != null) {
            jceOutputStream.write(str3, 5);
        }
    }

    public AllocateCloudMarvisJceRequest(int i, String str, String str2, boolean z2) {
        this.cloudMarvisPlan = 0;
        this.orderNo = "";
        this.platform = i;
        this.sessionId = str;
        this.wifi = str2;
        this.hasIoa = z2;
    }
}
