package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class KeepAliveWhiteCmd extends JceStruct {
    public int cmdId;

    public KeepAliveWhiteCmd() {
        this.cmdId = 0;
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.cmdId = jceInputStream.read(this.cmdId, 0, true);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write(this.cmdId, 0);
    }

    public KeepAliveWhiteCmd(int i) {
        this.cmdId = i;
    }
}
