package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;
import java.util.ArrayList;
import java.util.Collection;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class DeviceManageJceRequest extends JceStruct {
    static ArrayList<String> cache_associatedDeviceId;
    public ArrayList<String> associatedDeviceId;
    public String deviceId;
    public String deviceName;
    public String guid;
    public int loginType;
    public String openId;
    public int operateType;
    public int platform;

    static {
        ArrayList<String> arrayList = new ArrayList<>();
        cache_associatedDeviceId = arrayList;
        arrayList.add("");
    }

    public DeviceManageJceRequest() {
        this.loginType = 0;
        this.openId = "";
        this.guid = "";
        this.platform = 0;
        this.deviceId = "";
        this.associatedDeviceId = null;
        this.operateType = 0;
        this.deviceName = "";
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.loginType = jceInputStream.read(this.loginType, 0, true);
        this.openId = jceInputStream.readString(1, true);
        this.guid = jceInputStream.readString(2, true);
        this.platform = jceInputStream.read(this.platform, 3, true);
        this.deviceId = jceInputStream.readString(4, true);
        this.associatedDeviceId = (ArrayList) jceInputStream.read(cache_associatedDeviceId, 5, false);
        this.operateType = jceInputStream.read(this.operateType, 6, true);
        this.deviceName = jceInputStream.readString(7, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write(this.loginType, 0);
        jceOutputStream.write(this.openId, 1);
        jceOutputStream.write(this.guid, 2);
        jceOutputStream.write(this.platform, 3);
        jceOutputStream.write(this.deviceId, 4);
        ArrayList<String> arrayList = this.associatedDeviceId;
        if (arrayList != null) {
            jceOutputStream.write((Collection) arrayList, 5);
        }
        jceOutputStream.write(this.operateType, 6);
        String str = this.deviceName;
        if (str != null) {
            jceOutputStream.write(str, 7);
        }
    }

    public DeviceManageJceRequest(int i, String str, String str2, int i2, String str3, ArrayList<String> arrayList, int i3, String str4) {
        this.loginType = i;
        this.openId = str;
        this.guid = str2;
        this.platform = i2;
        this.deviceId = str3;
        this.associatedDeviceId = arrayList;
        this.operateType = i3;
        this.deviceName = str4;
    }
}
