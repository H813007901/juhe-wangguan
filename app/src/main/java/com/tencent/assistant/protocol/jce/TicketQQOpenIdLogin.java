package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class TicketQQOpenIdLogin extends JceStruct {
    public String accessToken;
    public String openId;
    public String payToken;
    public String pf;

    public TicketQQOpenIdLogin() {
        this.openId = "";
        this.accessToken = null;
        this.pf = null;
        this.payToken = null;
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.openId = jceInputStream.readString(0, false);
        this.accessToken = jceInputStream.readString(1, false);
        this.pf = jceInputStream.readString(2, false);
        this.payToken = jceInputStream.readString(3, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        String str = this.openId;
        if (str != null) {
            jceOutputStream.write(str, 0);
        }
        String str2 = this.accessToken;
        if (str2 != null) {
            jceOutputStream.write(str2, 1);
        }
        String str3 = this.pf;
        if (str3 != null) {
            jceOutputStream.write(str3, 2);
        }
        String str4 = this.payToken;
        if (str4 != null) {
            jceOutputStream.write(str4, 3);
        }
    }

    public TicketQQOpenIdLogin(String str, String str2, String str3, String str4) {
        this.openId = str;
        this.accessToken = str2;
        this.pf = str3;
        this.payToken = str4;
    }
}
