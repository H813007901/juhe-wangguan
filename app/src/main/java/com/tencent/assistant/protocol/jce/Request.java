package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class Request extends JceStruct {
    static byte[] cache_body;
    static ReqHead cache_head = new ReqHead();
    public byte[] body;
    public ReqHead head;

    static {
        cache_body = new byte[]{0};
    }

    public Request() {
        this.head = null;
        this.body = null;
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.head = (ReqHead) jceInputStream.read((JceStruct) cache_head, 0, true);
        this.body = jceInputStream.read(cache_body, 1, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write((JceStruct) this.head, 0);
        byte[] bArr = this.body;
        if (bArr != null) {
            jceOutputStream.write(bArr, 1);
        }
    }

    public Request(ReqHead reqHead, byte[] bArr) {
        this.head = reqHead;
        this.body = bArr;
    }
}
