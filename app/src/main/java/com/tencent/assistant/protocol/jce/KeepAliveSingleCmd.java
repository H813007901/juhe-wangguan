package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class KeepAliveSingleCmd extends JceStruct {
    static byte[] cache_body;
    public byte[] body;
    public int cmdId;
    public int ret;

    static {
        cache_body = new byte[]{0};
    }

    public KeepAliveSingleCmd() {
        this.cmdId = 0;
        this.body = null;
        this.ret = 0;
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.cmdId = jceInputStream.read(this.cmdId, 0, true);
        this.body = jceInputStream.read(cache_body, 1, true);
        this.ret = jceInputStream.read(this.ret, 2, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write(this.cmdId, 0);
        jceOutputStream.write(this.body, 1);
        jceOutputStream.write(this.ret, 2);
    }

    public KeepAliveSingleCmd(int i, byte[] bArr, int i2) {
        this.cmdId = i;
        this.body = bArr;
        this.ret = i2;
    }
}
