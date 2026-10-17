package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class GetFederationTokenJceResponse extends JceStruct {
    public String TmpSecretId;
    public String TmpSecretKey;
    public String msg;
    public int ret;
    public String token;

    public GetFederationTokenJceResponse() {
        this.ret = 0;
        this.msg = "";
        this.token = "";
        this.TmpSecretId = "";
        this.TmpSecretKey = "";
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.ret = jceInputStream.read(this.ret, 0, true);
        this.msg = jceInputStream.readString(1, false);
        this.token = jceInputStream.readString(2, false);
        this.TmpSecretId = jceInputStream.readString(3, false);
        this.TmpSecretKey = jceInputStream.readString(4, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write(this.ret, 0);
        String str = this.msg;
        if (str != null) {
            jceOutputStream.write(str, 1);
        }
        String str2 = this.token;
        if (str2 != null) {
            jceOutputStream.write(str2, 2);
        }
        String str3 = this.TmpSecretId;
        if (str3 != null) {
            jceOutputStream.write(str3, 3);
        }
        String str4 = this.TmpSecretKey;
        if (str4 != null) {
            jceOutputStream.write(str4, 4);
        }
    }

    public GetFederationTokenJceResponse(int i, String str, String str2, String str3, String str4) {
        this.ret = i;
        this.msg = str;
        this.token = str2;
        this.TmpSecretId = str3;
        this.TmpSecretKey = str4;
    }
}
