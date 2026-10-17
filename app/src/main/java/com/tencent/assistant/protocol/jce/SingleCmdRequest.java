package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class SingleCmdRequest extends JceStruct {
    static byte[] cache_body;
    public byte[] body;
    public int cmdId;

    static {
        cache_body = new byte[]{0};
    }

    public SingleCmdRequest() {
        this.cmdId = 0;
        this.body = null;
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.cmdId = jceInputStream.read(this.cmdId, 0, true);
        this.body = jceInputStream.read(cache_body, 1, true);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write(this.cmdId, 0);
        jceOutputStream.write(this.body, 1);
    }

    public SingleCmdRequest(int i, byte[] bArr) {
        this.cmdId = i;
        this.body = bArr;
    }
}
