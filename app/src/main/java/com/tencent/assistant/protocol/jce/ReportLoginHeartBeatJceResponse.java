package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class ReportLoginHeartBeatJceResponse extends JceStruct {
    public String currentDeviceName;
    public String currentLoginTime;
    public boolean isCurrentLogin;
    public String msg;
    public int ret;

    public ReportLoginHeartBeatJceResponse() {
        this.ret = 0;
        this.msg = "";
        this.isCurrentLogin = true;
        this.currentDeviceName = "";
        this.currentLoginTime = "";
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.ret = jceInputStream.read(this.ret, 0, true);
        this.msg = jceInputStream.readString(1, false);
        this.isCurrentLogin = jceInputStream.read(this.isCurrentLogin, 2, false);
        this.currentDeviceName = jceInputStream.readString(3, false);
        this.currentLoginTime = jceInputStream.readString(4, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write(this.ret, 0);
        String str = this.msg;
        if (str != null) {
            jceOutputStream.write(str, 1);
        }
        jceOutputStream.write(this.isCurrentLogin, 2);
        String str2 = this.currentDeviceName;
        if (str2 != null) {
            jceOutputStream.write(str2, 3);
        }
        String str3 = this.currentLoginTime;
        if (str3 != null) {
            jceOutputStream.write(str3, 4);
        }
    }

    public ReportLoginHeartBeatJceResponse(int i, String str, boolean z2, String str2, String str3) {
        this.ret = i;
        this.msg = str;
        this.isCurrentLogin = z2;
        this.currentDeviceName = str2;
        this.currentLoginTime = str3;
    }
}
