package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class PkgReq extends JceStruct {
    static PkgReqHead cache_pkgReqHead = new PkgReqHead();
    static byte[] cache_reqEncData;
    public PkgReqHead pkgReqHead;
    public byte[] reqEncData;

    static {
        cache_reqEncData = new byte[]{0};
    }

    public PkgReq() {
        this.pkgReqHead = null;
        this.reqEncData = null;
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.pkgReqHead = (PkgReqHead) jceInputStream.read((JceStruct) cache_pkgReqHead, 0, true);
        this.reqEncData = jceInputStream.read(cache_reqEncData, 1, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write((JceStruct) this.pkgReqHead, 0);
        byte[] bArr = this.reqEncData;
        if (bArr != null) {
            jceOutputStream.write(bArr, 1);
        }
    }

    public PkgReq(PkgReqHead pkgReqHead, byte[] bArr) {
        this.pkgReqHead = pkgReqHead;
        this.reqEncData = bArr;
    }
}
