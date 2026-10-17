package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class SignalingForwardForMobileJceRequest extends JceStruct {
    public String code;
    public String controlledDeviceID;
    public String dataJson;
    public String deviceId;
    public String guid;
    public int loginType;
    public String openId;
    public int platform;
    public int signalingType;
    public String token;

    public SignalingForwardForMobileJceRequest() {
        this.loginType = 0;
        this.openId = "";
        this.guid = "";
        this.token = "";
        this.platform = 0;
        this.deviceId = "";
        this.controlledDeviceID = "";
        this.signalingType = 0;
        this.dataJson = "";
        this.code = "";
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.loginType = jceInputStream.read(this.loginType, 0, true);
        this.openId = jceInputStream.readString(1, true);
        this.guid = jceInputStream.readString(2, true);
        this.token = jceInputStream.readString(3, true);
        this.platform = jceInputStream.read(this.platform, 4, true);
        this.deviceId = jceInputStream.readString(5, true);
        this.controlledDeviceID = jceInputStream.readString(6, true);
        this.signalingType = jceInputStream.read(this.signalingType, 7, true);
        this.dataJson = jceInputStream.readString(8, false);
        this.code = jceInputStream.readString(9, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write(this.loginType, 0);
        jceOutputStream.write(this.openId, 1);
        jceOutputStream.write(this.guid, 2);
        jceOutputStream.write(this.token, 3);
        jceOutputStream.write(this.platform, 4);
        jceOutputStream.write(this.deviceId, 5);
        jceOutputStream.write(this.controlledDeviceID, 6);
        jceOutputStream.write(this.signalingType, 7);
        String str = this.dataJson;
        if (str != null) {
            jceOutputStream.write(str, 8);
        }
        String str2 = this.code;
        if (str2 != null) {
            jceOutputStream.write(str2, 9);
        }
    }

    public SignalingForwardForMobileJceRequest(int i, String str, String str2, String str3, int i2, String str4, String str5, int i3, String str6, String str7) {
        this.loginType = i;
        this.openId = str;
        this.guid = str2;
        this.token = str3;
        this.platform = i2;
        this.deviceId = str4;
        this.controlledDeviceID = str5;
        this.signalingType = i3;
        this.dataJson = str6;
        this.code = str7;
    }
}
