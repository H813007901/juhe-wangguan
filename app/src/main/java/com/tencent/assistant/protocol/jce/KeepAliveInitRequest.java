package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class KeepAliveInitRequest extends JceStruct {
    static ReqHead cache_head = new ReqHead();
    public ReqHead head;

    public KeepAliveInitRequest() {
        this.head = null;
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.head = (ReqHead) jceInputStream.read((JceStruct) cache_head, 0, true);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write((JceStruct) this.head, 0);
    }

    public KeepAliveInitRequest(ReqHead reqHead) {
        this.head = reqHead;
    }
}
