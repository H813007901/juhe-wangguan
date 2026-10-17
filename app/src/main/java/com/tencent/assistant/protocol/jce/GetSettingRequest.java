package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class GetSettingRequest extends JceStruct {
    static byte[] cache_context;
    static byte[] cache_pngContext;
    public byte[] context;
    public byte[] pngContext;
    public String reserve;
    public int viaversion;

    public GetSettingRequest() {
        this.reserve = "";
        this.context = null;
        this.pngContext = null;
        this.viaversion = -1;
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.reserve = jceInputStream.readString(0, false);
        if (cache_context == null) {
            cache_context = new byte[]{0};
        }
        this.context = jceInputStream.read(cache_context, 1, false);
        if (cache_pngContext == null) {
            cache_pngContext = new byte[]{0};
        }
        this.pngContext = jceInputStream.read(cache_pngContext, 2, false);
        this.viaversion = jceInputStream.read(this.viaversion, 3, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        String str = this.reserve;
        if (str != null) {
            jceOutputStream.write(str, 0);
        }
        byte[] bArr = this.context;
        if (bArr != null) {
            jceOutputStream.write(bArr, 1);
        }
        byte[] bArr2 = this.pngContext;
        if (bArr2 != null) {
            jceOutputStream.write(bArr2, 2);
        }
        jceOutputStream.write(this.viaversion, 3);
    }

    public GetSettingRequest(String str, byte[] bArr, byte[] bArr2, int i) {
        this.reserve = str;
        this.context = bArr;
        this.pngContext = bArr2;
        this.viaversion = i;
    }
}
