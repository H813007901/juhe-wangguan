package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;
import java.util.ArrayList;
import java.util.Collection;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class LCCMessage extends JceStruct {
    static ArrayList<LCCMessageBodyItem> cache_datalist = new ArrayList<>();
    public ArrayList<LCCMessageBodyItem> datalist;

    static {
        cache_datalist.add(new LCCMessageBodyItem());
    }

    public LCCMessage() {
        this.datalist = null;
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.datalist = (ArrayList) jceInputStream.read(cache_datalist, 0, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        ArrayList<LCCMessageBodyItem> arrayList = this.datalist;
        if (arrayList != null) {
            jceOutputStream.write((Collection) arrayList, 0);
        }
    }

    public LCCMessage(ArrayList<LCCMessageBodyItem> arrayList) {
        this.datalist = arrayList;
    }
}
