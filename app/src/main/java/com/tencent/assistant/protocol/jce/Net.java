package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class Net extends JceStruct {
    public String clientIp;
    public String clientIpv6;
    public String extNetworkOperator;
    public int extNetworkType;
    public byte ipType;
    public byte isWap;
    public int nacMode;
    public String nacModeStr;
    public byte netType;
    public String wifiBssid;
    public String wifiSsid;

    public Net() {
        this.netType = (byte) 0;
        this.nacMode = 0;
        this.ipType = (byte) 2;
        this.extNetworkOperator = "";
        this.extNetworkType = 0;
        this.isWap = (byte) 0;
        this.wifiSsid = "";
        this.wifiBssid = "";
        this.nacModeStr = "";
        this.clientIp = "";
        this.clientIpv6 = "";
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.netType = jceInputStream.read(this.netType, 0, true);
        this.nacMode = jceInputStream.read(this.nacMode, 1, true);
        this.ipType = jceInputStream.read(this.ipType, 2, false);
        this.extNetworkOperator = jceInputStream.readString(3, false);
        this.extNetworkType = jceInputStream.read(this.extNetworkType, 4, false);
        this.isWap = jceInputStream.read(this.isWap, 5, false);
        this.wifiSsid = jceInputStream.readString(6, false);
        this.wifiBssid = jceInputStream.readString(7, false);
        this.nacModeStr = jceInputStream.readString(8, false);
        this.clientIp = jceInputStream.readString(9, false);
        this.clientIpv6 = jceInputStream.readString(10, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write(this.netType, 0);
        jceOutputStream.write(this.nacMode, 1);
        jceOutputStream.write(this.ipType, 2);
        String str = this.extNetworkOperator;
        if (str != null) {
            jceOutputStream.write(str, 3);
        }
        jceOutputStream.write(this.extNetworkType, 4);
        jceOutputStream.write(this.isWap, 5);
        String str2 = this.wifiSsid;
        if (str2 != null) {
            jceOutputStream.write(str2, 6);
        }
        String str3 = this.wifiBssid;
        if (str3 != null) {
            jceOutputStream.write(str3, 7);
        }
        String str4 = this.nacModeStr;
        if (str4 != null) {
            jceOutputStream.write(str4, 8);
        }
        String str5 = this.clientIp;
        if (str5 != null) {
            jceOutputStream.write(str5, 9);
        }
        String str6 = this.clientIpv6;
        if (str6 != null) {
            jceOutputStream.write(str6, 10);
        }
    }

    public Net(byte b2, int i, byte b3, String str, int i2, byte b4, String str2, String str3, String str4, String str5, String str6) {
        this.netType = b2;
        this.nacMode = i;
        this.ipType = b3;
        this.extNetworkOperator = str;
        this.extNetworkType = i2;
        this.isWap = b4;
        this.wifiSsid = str2;
        this.wifiBssid = str3;
        this.nacModeStr = str4;
        this.clientIp = str5;
        this.clientIpv6 = str6;
    }
}
