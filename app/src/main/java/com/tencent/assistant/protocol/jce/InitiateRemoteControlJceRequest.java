package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class InitiateRemoteControlJceRequest extends JceStruct {
    public String controlledDeviceId;
    public String controllingDeviceId;
    public String dataJson;
    public String guid;
    public int loginType;
    public String openId;
    public String password;
    public int platform;

    public InitiateRemoteControlJceRequest() {
        this.loginType = 0;
        this.openId = "";
        this.guid = "";
        this.controllingDeviceId = "";
        this.platform = 0;
        this.controlledDeviceId = "";
        this.password = "";
        this.dataJson = "";
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.loginType = jceInputStream.read(this.loginType, 0, true);
        this.openId = jceInputStream.readString(1, true);
        this.guid = jceInputStream.readString(2, true);
        this.controllingDeviceId = jceInputStream.readString(3, true);
        this.platform = jceInputStream.read(this.platform, 4, true);
        this.controlledDeviceId = jceInputStream.readString(5, true);
        this.password = jceInputStream.readString(6, false);
        this.dataJson = jceInputStream.readString(7, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write(this.loginType, 0);
        jceOutputStream.write(this.openId, 1);
        jceOutputStream.write(this.guid, 2);
        jceOutputStream.write(this.controllingDeviceId, 3);
        jceOutputStream.write(this.platform, 4);
        jceOutputStream.write(this.controlledDeviceId, 5);
        String str = this.password;
        if (str != null) {
            jceOutputStream.write(str, 6);
        }
        String str2 = this.dataJson;
        if (str2 != null) {
            jceOutputStream.write(str2, 7);
        }
    }

    public InitiateRemoteControlJceRequest(int i, String str, String str2, String str3, int i2, String str4, String str5, String str6) {
        this.loginType = i;
        this.openId = str;
        this.guid = str2;
        this.controllingDeviceId = str3;
        this.platform = i2;
        this.controlledDeviceId = str4;
        this.password = str5;
        this.dataJson = str6;
    }
}
