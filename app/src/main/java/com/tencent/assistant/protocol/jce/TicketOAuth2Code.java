package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class TicketOAuth2Code extends JceStruct {
    static byte[] cache_code;
    public byte[] code;

    static {
        cache_code = new byte[]{0};
    }

    public TicketOAuth2Code() {
        this.code = null;
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.code = jceInputStream.read(cache_code, 0, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        byte[] bArr = this.code;
        if (bArr != null) {
            jceOutputStream.write(bArr, 0);
        }
    }

    public TicketOAuth2Code(byte[] bArr) {
        this.code = bArr;
    }
}
