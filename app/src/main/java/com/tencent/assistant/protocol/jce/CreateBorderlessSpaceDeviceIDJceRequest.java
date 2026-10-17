package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class CreateBorderlessSpaceDeviceIDJceRequest extends JceStruct {
    public String deviceName;
    public String guid;
    public int loginType;
    public String openId;
    public int platform;
    public String token;
    public String version;

    public CreateBorderlessSpaceDeviceIDJceRequest() {
        this.loginType = 0;
        this.openId = "";
        this.guid = "";
        this.token = "";
        this.platform = 0;
        this.deviceName = "";
        this.version = "";
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.loginType = jceInputStream.read(this.loginType, 0, true);
        this.openId = jceInputStream.readString(1, true);
        this.guid = jceInputStream.readString(2, true);
        this.token = jceInputStream.readString(3, false);
        this.platform = jceInputStream.read(this.platform, 4, true);
        this.deviceName = jceInputStream.readString(5, true);
        this.version = jceInputStream.readString(6, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write(this.loginType, 0);
        jceOutputStream.write(this.openId, 1);
        jceOutputStream.write(this.guid, 2);
        String str = this.token;
        if (str != null) {
            jceOutputStream.write(str, 3);
        }
        jceOutputStream.write(this.platform, 4);
        jceOutputStream.write(this.deviceName, 5);
        String str2 = this.version;
        if (str2 != null) {
            jceOutputStream.write(str2, 6);
        }
    }

    public CreateBorderlessSpaceDeviceIDJceRequest(int i, String str, String str2, String str3, int i2, String str4, String str5) {
        this.loginType = i;
        this.openId = str;
        this.guid = str2;
        this.token = str3;
        this.platform = i2;
        this.deviceName = str4;
        this.version = str5;
    }
}
