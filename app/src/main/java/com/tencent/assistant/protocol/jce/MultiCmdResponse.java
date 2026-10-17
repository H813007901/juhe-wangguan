package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;
import java.util.ArrayList;
import java.util.Collection;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class MultiCmdResponse extends JceStruct {
    static ArrayList<SingleCmdResponse> cache_multiCmds = new ArrayList<>();
    public ArrayList<SingleCmdResponse> multiCmds;

    static {
        cache_multiCmds.add(new SingleCmdResponse());
    }

    public MultiCmdResponse() {
        this.multiCmds = null;
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.multiCmds = (ArrayList) jceInputStream.read(cache_multiCmds, 0, true);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write((Collection) this.multiCmds, 0);
    }

    public MultiCmdResponse(ArrayList<SingleCmdResponse> arrayList) {
        this.multiCmds = arrayList;
    }
}
