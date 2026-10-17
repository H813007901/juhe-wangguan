package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;
import java.util.ArrayList;
import java.util.Collection;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class SimulatorCheck extends JceStruct {
    static ArrayList<String> cache_infoList;
    public ArrayList<String> infoList;

    static {
        ArrayList<String> arrayList = new ArrayList<>();
        cache_infoList = arrayList;
        arrayList.add("");
    }

    public SimulatorCheck() {
        this.infoList = null;
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.infoList = (ArrayList) jceInputStream.read(cache_infoList, 0, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        ArrayList<String> arrayList = this.infoList;
        if (arrayList != null) {
            jceOutputStream.write((Collection) arrayList, 0);
        }
    }

    public SimulatorCheck(ArrayList<String> arrayList) {
        this.infoList = arrayList;
    }
}
