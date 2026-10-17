package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class PkgRspHead extends JceStruct {
    public String checkSum;
    public byte encryptWithPack;
    public int requestId;

    public PkgRspHead() {
        this.requestId = 0;
        this.encryptWithPack = (byte) 0;
        this.checkSum = "";
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.requestId = jceInputStream.read(this.requestId, 0, true);
        this.encryptWithPack = jceInputStream.read(this.encryptWithPack, 1, false);
        this.checkSum = jceInputStream.readString(2, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write(this.requestId, 0);
        jceOutputStream.write(this.encryptWithPack, 1);
        String str = this.checkSum;
        if (str != null) {
            jceOutputStream.write(str, 2);
        }
    }

    public PkgRspHead(int i, byte b2, String str) {
        this.requestId = i;
        this.encryptWithPack = b2;
        this.checkSum = str;
    }
}
