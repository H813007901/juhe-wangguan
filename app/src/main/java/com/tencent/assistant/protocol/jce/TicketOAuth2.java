package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class TicketOAuth2 extends JceStruct {
    static byte[] cache_accessToken;
    static byte[] cache_refreshToken;
    public byte[] accessToken;
    public String openId;
    public byte[] refreshToken;

    static {
        cache_accessToken = new byte[]{0};
        cache_refreshToken = new byte[]{0};
    }

    public TicketOAuth2() {
        this.openId = "";
        this.accessToken = null;
        this.refreshToken = null;
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.openId = jceInputStream.readString(0, false);
        this.accessToken = jceInputStream.read(cache_accessToken, 1, false);
        this.refreshToken = jceInputStream.read(cache_refreshToken, 2, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        String str = this.openId;
        if (str != null) {
            jceOutputStream.write(str, 0);
        }
        byte[] bArr = this.accessToken;
        if (bArr != null) {
            jceOutputStream.write(bArr, 1);
        }
        byte[] bArr2 = this.refreshToken;
        if (bArr2 != null) {
            jceOutputStream.write(bArr2, 2);
        }
    }

    public TicketOAuth2(String str, byte[] bArr, byte[] bArr2) {
        this.openId = str;
        this.accessToken = bArr;
        this.refreshToken = bArr2;
    }
}
