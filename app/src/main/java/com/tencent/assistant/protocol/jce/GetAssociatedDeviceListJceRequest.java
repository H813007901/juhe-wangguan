package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class GetAssociatedDeviceListJceRequest extends JceStruct {
    public String associatedDeviceId;
    public String deviceId;
    public String guid;
    public int loginType;
    public String openId;
    public int platform;

    public GetAssociatedDeviceListJceRequest() {
        this.loginType = 0;
        this.openId = "";
        this.guid = "";
        this.deviceId = "";
        this.platform = 0;
        this.associatedDeviceId = "";
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.loginType = jceInputStream.read(this.loginType, 0, true);
        this.openId = jceInputStream.readString(1, true);
        this.guid = jceInputStream.readString(2, true);
        this.deviceId = jceInputStream.readString(3, true);
        this.platform = jceInputStream.read(this.platform, 4, true);
        this.associatedDeviceId = jceInputStream.readString(5, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write(this.loginType, 0);
        jceOutputStream.write(this.openId, 1);
        jceOutputStream.write(this.guid, 2);
        jceOutputStream.write(this.deviceId, 3);
        jceOutputStream.write(this.platform, 4);
        String str = this.associatedDeviceId;
        if (str != null) {
            jceOutputStream.write(str, 5);
        }
    }

    public GetAssociatedDeviceListJceRequest(int i, String str, String str2, String str3, int i2, String str4) {
        this.loginType = i;
        this.openId = str;
        this.guid = str2;
        this.deviceId = str3;
        this.platform = i2;
        this.associatedDeviceId = str4;
    }
}
