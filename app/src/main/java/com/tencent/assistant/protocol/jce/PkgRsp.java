package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class PkgRsp extends JceStruct {
    static PkgRspHead cache_pkgRspHead = new PkgRspHead();
    static byte[] cache_rspEncData;
    public PkgRspHead pkgRspHead;
    public byte[] rspEncData;

    static {
        cache_rspEncData = new byte[]{0};
    }

    public PkgRsp() {
        this.pkgRspHead = null;
        this.rspEncData = null;
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.pkgRspHead = (PkgRspHead) jceInputStream.read((JceStruct) cache_pkgRspHead, 0, true);
        this.rspEncData = jceInputStream.read(cache_rspEncData, 1, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write((JceStruct) this.pkgRspHead, 0);
        byte[] bArr = this.rspEncData;
        if (bArr != null) {
            jceOutputStream.write(bArr, 1);
        }
    }

    public PkgRsp(PkgRspHead pkgRspHead, byte[] bArr) {
        this.pkgRspHead = pkgRspHead;
        this.rspEncData = bArr;
    }
}
