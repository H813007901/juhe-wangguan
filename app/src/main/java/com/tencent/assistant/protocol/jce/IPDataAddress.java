package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class IPDataAddress extends JceStruct {
    public String ip;
    public short port;

    public IPDataAddress() {
        this.ip = "";
        this.port = (short) 0;
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.ip = jceInputStream.readString(0, true);
        this.port = jceInputStream.read(this.port, 1, true);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write(this.ip, 0);
        jceOutputStream.write(this.port, 1);
    }

    public IPDataAddress(String str, short s) {
        this.ip = str;
        this.port = s;
    }
}
