package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;
import java.util.ArrayList;
import java.util.Collection;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class KeepAliveWhiteCmds extends JceStruct {
    static ArrayList<KeepAliveWhiteCmd> cache_cmds = new ArrayList<>();
    public ArrayList<KeepAliveWhiteCmd> cmds;

    static {
        cache_cmds.add(new KeepAliveWhiteCmd());
    }

    public KeepAliveWhiteCmds() {
        this.cmds = null;
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.cmds = (ArrayList) jceInputStream.read(cache_cmds, 0, true);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write((Collection) this.cmds, 0);
    }

    public KeepAliveWhiteCmds(ArrayList<KeepAliveWhiteCmd> arrayList) {
        this.cmds = arrayList;
    }
}
