package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class GetAssociationListJceResponse extends JceStruct {
    public String associationInfo;
    public String msg;
    public int ret;
    public String signature;

    public GetAssociationListJceResponse() {
        this.ret = 0;
        this.msg = "";
        this.associationInfo = "";
        this.signature = "";
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.ret = jceInputStream.read(this.ret, 0, true);
        this.msg = jceInputStream.readString(1, false);
        this.associationInfo = jceInputStream.readString(2, true);
        this.signature = jceInputStream.readString(3, true);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write(this.ret, 0);
        String str = this.msg;
        if (str != null) {
            jceOutputStream.write(str, 1);
        }
        jceOutputStream.write(this.associationInfo, 2);
        jceOutputStream.write(this.signature, 3);
    }

    public GetAssociationListJceResponse(int i, String str, String str2, String str3) {
        this.ret = i;
        this.msg = str;
        this.associationInfo = str2;
        this.signature = str3;
    }
}
