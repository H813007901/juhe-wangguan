package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class AccountDeviceInfo extends JceStruct {
    public int associatedDeviceNum;
    public String desktopUrl;
    public String deviceId;
    public String deviceName;
    public String publicKey;
    public String qrCodeUrl;

    public AccountDeviceInfo() {
        this.deviceId = "";
        this.deviceName = "";
        this.qrCodeUrl = "";
        this.associatedDeviceNum = 0;
        this.publicKey = "";
        this.desktopUrl = "";
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.deviceId = jceInputStream.readString(0, false);
        this.deviceName = jceInputStream.readString(1, false);
        this.qrCodeUrl = jceInputStream.readString(2, false);
        this.associatedDeviceNum = jceInputStream.read(this.associatedDeviceNum, 3, false);
        this.publicKey = jceInputStream.readString(4, false);
        this.desktopUrl = jceInputStream.readString(5, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        String str = this.deviceId;
        if (str != null) {
            jceOutputStream.write(str, 0);
        }
        String str2 = this.deviceName;
        if (str2 != null) {
            jceOutputStream.write(str2, 1);
        }
        String str3 = this.qrCodeUrl;
        if (str3 != null) {
            jceOutputStream.write(str3, 2);
        }
        jceOutputStream.write(this.associatedDeviceNum, 3);
        String str4 = this.publicKey;
        if (str4 != null) {
            jceOutputStream.write(str4, 4);
        }
        String str5 = this.desktopUrl;
        if (str5 != null) {
            jceOutputStream.write(str5, 5);
        }
    }

    public AccountDeviceInfo(String str, String str2, String str3, int i, String str4, String str5) {
        this.deviceId = str;
        this.deviceName = str2;
        this.qrCodeUrl = str3;
        this.associatedDeviceNum = i;
        this.publicKey = str4;
        this.desktopUrl = str5;
    }
}
