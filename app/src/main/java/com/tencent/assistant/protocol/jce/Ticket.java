package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class Ticket extends JceStruct {
    static byte[] cache_value;
    public byte type;
    public byte[] value;

    static {
        cache_value = new byte[]{0};
    }

    public Ticket() {
        this.type = (byte) 0;
        this.value = null;
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.type = jceInputStream.read(this.type, 0, true);
        this.value = jceInputStream.read(cache_value, 1, true);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write(this.type, 0);
        jceOutputStream.write(this.value, 1);
    }

    public Ticket(byte b2, byte[] bArr) {
        this.type = b2;
        this.value = bArr;
    }
}
