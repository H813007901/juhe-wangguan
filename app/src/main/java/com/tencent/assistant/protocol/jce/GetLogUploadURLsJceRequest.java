package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;
import java.util.ArrayList;
import java.util.Collection;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class GetLogUploadURLsJceRequest extends JceStruct {
    static ArrayList<CloudContainer> cache_cloudContainers = new ArrayList<>();
    static ArrayList<PcDevice> cache_onlinePcDevices;
    public ArrayList<CloudContainer> cloudContainers;
    public ArrayList<PcDevice> onlinePcDevices;
    public int platform;

    static {
        cache_cloudContainers.add(new CloudContainer());
        cache_onlinePcDevices = new ArrayList<>();
        cache_onlinePcDevices.add(new PcDevice());
    }

    public GetLogUploadURLsJceRequest() {
        this.platform = 0;
        this.cloudContainers = null;
        this.onlinePcDevices = null;
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.platform = jceInputStream.read(this.platform, 0, true);
        this.cloudContainers = (ArrayList) jceInputStream.read(cache_cloudContainers, 1, false);
        this.onlinePcDevices = (ArrayList) jceInputStream.read(cache_onlinePcDevices, 2, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write(this.platform, 0);
        ArrayList<CloudContainer> arrayList = this.cloudContainers;
        if (arrayList != null) {
            jceOutputStream.write((Collection) arrayList, 1);
        }
        ArrayList<PcDevice> arrayList2 = this.onlinePcDevices;
        if (arrayList2 != null) {
            jceOutputStream.write((Collection) arrayList2, 2);
        }
    }

    public GetLogUploadURLsJceRequest(int i, ArrayList<CloudContainer> arrayList, ArrayList<PcDevice> arrayList2) {
        this.platform = i;
        this.cloudContainers = arrayList;
        this.onlinePcDevices = arrayList2;
    }
}
