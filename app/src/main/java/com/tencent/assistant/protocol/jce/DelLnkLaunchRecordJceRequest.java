package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class DelLnkLaunchRecordJceRequest extends JceStruct {
    public String controlledDeviceId;
    public String controllingDeviceId;
    public String guid;
    public String lnkName;
    public int loginType;
    public String openId;
    public int platform;

    public DelLnkLaunchRecordJceRequest() {
        this.loginType = 0;
        this.openId = "";
        this.guid = "";
        this.platform = 0;
        this.controllingDeviceId = "";
        this.controlledDeviceId = "";
        this.lnkName = "";
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.loginType = jceInputStream.read(this.loginType, 0, true);
        this.openId = jceInputStream.readString(1, true);
        this.guid = jceInputStream.readString(2, true);
        this.platform = jceInputStream.read(this.platform, 3, true);
        this.controllingDeviceId = jceInputStream.readString(4, true);
        this.controlledDeviceId = jceInputStream.readString(5, true);
        this.lnkName = jceInputStream.readString(6, true);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write(this.loginType, 0);
        jceOutputStream.write(this.openId, 1);
        jceOutputStream.write(this.guid, 2);
        jceOutputStream.write(this.platform, 3);
        jceOutputStream.write(this.controllingDeviceId, 4);
        jceOutputStream.write(this.controlledDeviceId, 5);
        jceOutputStream.write(this.lnkName, 6);
    }

    public DelLnkLaunchRecordJceRequest(int i, String str, String str2, int i2, String str3, String str4, String str5) {
        this.loginType = i;
        this.openId = str;
        this.guid = str2;
        this.platform = i2;
        this.controllingDeviceId = str3;
        this.controlledDeviceId = str4;
        this.lnkName = str5;
    }
}
