package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class LCMessageBodyBase extends JceStruct {
    static byte[] cache_data;
    public long cmd;
    public long contentId;
    public int contentType;
    public byte[] data;

    static {
        cache_data = new byte[]{0};
    }

    public LCMessageBodyBase() {
        this.cmd = 0L;
        this.contentType = 0;
        this.contentId = 0L;
        this.data = null;
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.cmd = jceInputStream.read(this.cmd, 0, false);
        this.contentType = jceInputStream.read(this.contentType, 1, false);
        this.contentId = jceInputStream.read(this.contentId, 2, false);
        this.data = jceInputStream.read(cache_data, 3, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write(this.cmd, 0);
        jceOutputStream.write(this.contentType, 1);
        jceOutputStream.write(this.contentId, 2);
        byte[] bArr = this.data;
        if (bArr != null) {
            jceOutputStream.write(bArr, 3);
        }
    }

    public LCMessageBodyBase(long j2, int i, long j3, byte[] bArr) {
        this.cmd = j2;
        this.contentType = i;
        this.contentId = j3;
        this.data = bArr;
    }
}
