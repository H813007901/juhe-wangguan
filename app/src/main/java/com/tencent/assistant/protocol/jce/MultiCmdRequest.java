package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;
import java.util.ArrayList;
import java.util.Collection;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class MultiCmdRequest extends JceStruct {
    static ArrayList<SingleCmdRequest> cache_multiCmds = new ArrayList<>();
    public ArrayList<SingleCmdRequest> multiCmds;

    static {
        cache_multiCmds.add(new SingleCmdRequest());
    }

    public MultiCmdRequest() {
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

    public MultiCmdRequest(ArrayList<SingleCmdRequest> arrayList) {
        this.multiCmds = arrayList;
    }
}
