package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class GetExplicitHotWordsRequest extends JceStruct {
    public String reserved;
    public long version;

    public GetExplicitHotWordsRequest() {
        this.reserved = "";
        this.version = 0L;
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.reserved = jceInputStream.readString(0, false);
        this.version = jceInputStream.read(this.version, 1, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        String str = this.reserved;
        if (str != null) {
            jceOutputStream.write(str, 0);
        }
        jceOutputStream.write(this.version, 1);
    }

    public GetExplicitHotWordsRequest(String str, long j2) {
        this.reserved = str;
        this.version = j2;
    }
}
