package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;
import java.util.ArrayList;
import java.util.Collection;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class GetAssociatedDeviceListJceResponse extends JceStruct {
    static ArrayList<DeviceInfo> cache_associatedDeviceList = new ArrayList<>();
    public ArrayList<DeviceInfo> associatedDeviceList;
    public String msg;
    public int ret;

    static {
        cache_associatedDeviceList.add(new DeviceInfo());
    }

    public GetAssociatedDeviceListJceResponse() {
        this.ret = 0;
        this.msg = "";
        this.associatedDeviceList = null;
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.ret = jceInputStream.read(this.ret, 0, true);
        this.msg = jceInputStream.readString(1, false);
        this.associatedDeviceList = (ArrayList) jceInputStream.read(cache_associatedDeviceList, 2, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write(this.ret, 0);
        String str = this.msg;
        if (str != null) {
            jceOutputStream.write(str, 1);
        }
        ArrayList<DeviceInfo> arrayList = this.associatedDeviceList;
        if (arrayList != null) {
            jceOutputStream.write((Collection) arrayList, 2);
        }
    }

    public GetAssociatedDeviceListJceResponse(int i, String str, ArrayList<DeviceInfo> arrayList) {
        this.ret = i;
        this.msg = str;
        this.associatedDeviceList = arrayList;
    }
}
