package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class ReportLoginJceRequest extends JceStruct {
    public String deviceName;
    public int loginTime;
    public int platform;

    public ReportLoginJceRequest() {
        this.platform = 0;
        this.loginTime = 0;
        this.deviceName = "";
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.platform = jceInputStream.read(this.platform, 0, true);
        this.loginTime = jceInputStream.read(this.loginTime, 1, true);
        this.deviceName = jceInputStream.readString(2, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write(this.platform, 0);
        jceOutputStream.write(this.loginTime, 1);
        String str = this.deviceName;
        if (str != null) {
            jceOutputStream.write(str, 2);
        }
    }

    public ReportLoginJceRequest(int i, int i2, String str) {
        this.platform = i;
        this.loginTime = i2;
        this.deviceName = str;
    }
}
