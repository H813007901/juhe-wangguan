package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class GetUserInfoRequest extends JceStruct {
    public String param;

    public GetUserInfoRequest() {
        this.param = "";
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.param = jceInputStream.readString(0, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        String str = this.param;
        if (str != null) {
            jceOutputStream.write(str, 0);
        }
    }

    public GetUserInfoRequest(String str) {
        this.param = str;
    }
}
