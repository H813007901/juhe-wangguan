package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class GetUserInfoResponse extends JceStruct {
    public long bitmap;
    public String nickName;
    public String portraitUrl;
    public int ret;

    public GetUserInfoResponse() {
        this.ret = 0;
        this.portraitUrl = "";
        this.nickName = "";
        this.bitmap = 0L;
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.ret = jceInputStream.read(this.ret, 0, true);
        this.portraitUrl = jceInputStream.readString(1, true);
        this.nickName = jceInputStream.readString(2, true);
        this.bitmap = jceInputStream.read(this.bitmap, 3, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write(this.ret, 0);
        jceOutputStream.write(this.portraitUrl, 1);
        jceOutputStream.write(this.nickName, 2);
        jceOutputStream.write(this.bitmap, 3);
    }

    public GetUserInfoResponse(int i, String str, String str2, long j2) {
        this.ret = i;
        this.portraitUrl = str;
        this.nickName = str2;
        this.bitmap = j2;
    }
}
