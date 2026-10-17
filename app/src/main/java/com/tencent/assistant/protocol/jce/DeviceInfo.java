package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;
import java.util.ArrayList;
import java.util.Collection;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class DeviceInfo extends JceStruct {
    static SecurityPolicy cache_associatedDeviceToDeviceSecurityPolicy;
    static SecurityPolicy cache_deviceToAssociatedDeviceSecurityPolicy;
    static ArrayList<ShortCut> cache_shortCutList = new ArrayList<>();
    public SecurityPolicy associatedDeviceToDeviceSecurityPolicy;
    public String desktopUrl;
    public String deviceId;
    public String deviceName;
    public SecurityPolicy deviceToAssociatedDeviceSecurityPolicy;
    public String guid;
    public int openclawOnlineStatus;
    public int platform;
    public ArrayList<ShortCut> shortCutList;
    public int status;
    public long updateTime;

    static {
        cache_shortCutList.add(new ShortCut());
        cache_deviceToAssociatedDeviceSecurityPolicy = new SecurityPolicy();
        cache_associatedDeviceToDeviceSecurityPolicy = new SecurityPolicy();
    }

    public DeviceInfo() {
        this.deviceId = "";
        this.deviceName = "";
        this.platform = 0;
        this.status = 0;
        this.desktopUrl = "";
        this.shortCutList = null;
        this.deviceToAssociatedDeviceSecurityPolicy = null;
        this.associatedDeviceToDeviceSecurityPolicy = null;
        this.updateTime = 0L;
        this.guid = "";
        this.openclawOnlineStatus = 0;
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.deviceId = jceInputStream.readString(0, false);
        this.deviceName = jceInputStream.readString(1, false);
        this.platform = jceInputStream.read(this.platform, 2, false);
        this.status = jceInputStream.read(this.status, 3, false);
        this.desktopUrl = jceInputStream.readString(4, false);
        this.shortCutList = (ArrayList) jceInputStream.read(cache_shortCutList, 5, false);
        this.deviceToAssociatedDeviceSecurityPolicy = (SecurityPolicy) jceInputStream.read((JceStruct) cache_deviceToAssociatedDeviceSecurityPolicy, 6, false);
        this.associatedDeviceToDeviceSecurityPolicy = (SecurityPolicy) jceInputStream.read((JceStruct) cache_associatedDeviceToDeviceSecurityPolicy, 7, false);
        this.updateTime = jceInputStream.read(this.updateTime, 8, false);
        this.guid = jceInputStream.readString(9, false);
        this.openclawOnlineStatus = jceInputStream.read(this.openclawOnlineStatus, 10, false);
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
        jceOutputStream.write(this.platform, 2);
        jceOutputStream.write(this.status, 3);
        String str3 = this.desktopUrl;
        if (str3 != null) {
            jceOutputStream.write(str3, 4);
        }
        ArrayList<ShortCut> arrayList = this.shortCutList;
        if (arrayList != null) {
            jceOutputStream.write((Collection) arrayList, 5);
        }
        SecurityPolicy securityPolicy = this.deviceToAssociatedDeviceSecurityPolicy;
        if (securityPolicy != null) {
            jceOutputStream.write((JceStruct) securityPolicy, 6);
        }
        SecurityPolicy securityPolicy2 = this.associatedDeviceToDeviceSecurityPolicy;
        if (securityPolicy2 != null) {
            jceOutputStream.write((JceStruct) securityPolicy2, 7);
        }
        jceOutputStream.write(this.updateTime, 8);
        String str4 = this.guid;
        if (str4 != null) {
            jceOutputStream.write(str4, 9);
        }
        jceOutputStream.write(this.openclawOnlineStatus, 10);
    }

    public DeviceInfo(String str, String str2, int i, int i2, String str3, ArrayList<ShortCut> arrayList, SecurityPolicy securityPolicy, SecurityPolicy securityPolicy2, long j2, String str4, int i3) {
        this.deviceId = str;
        this.deviceName = str2;
        this.platform = i;
        this.status = i2;
        this.desktopUrl = str3;
        this.shortCutList = arrayList;
        this.deviceToAssociatedDeviceSecurityPolicy = securityPolicy;
        this.associatedDeviceToDeviceSecurityPolicy = securityPolicy2;
        this.updateTime = j2;
        this.guid = str4;
        this.openclawOnlineStatus = i3;
    }
}
