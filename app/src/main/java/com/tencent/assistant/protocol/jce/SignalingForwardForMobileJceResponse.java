package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class SignalingForwardForMobileJceResponse extends JceStruct {
    public String dataJson;
    public String msg;
    public int ret;

    public SignalingForwardForMobileJceResponse() {
        this.ret = 0;
        this.msg = "";
        this.dataJson = "";
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.ret = jceInputStream.read(this.ret, 0, true);
        this.msg = jceInputStream.readString(1, false);
        this.dataJson = jceInputStream.readString(2, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write(this.ret, 0);
        String str = this.msg;
        if (str != null) {
            jceOutputStream.write(str, 1);
        }
        String str2 = this.dataJson;
        if (str2 != null) {
            jceOutputStream.write(str2, 2);
        }
    }

    public SignalingForwardForMobileJceResponse(int i, String str, String str2) {
        this.ret = i;
        this.msg = str;
        this.dataJson = str2;
    }
}
