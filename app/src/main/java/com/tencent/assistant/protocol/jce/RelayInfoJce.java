package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class RelayInfoJce extends JceStruct {
    public String Ip;
    public int Port;
    public String SessionID;
    public String Token;
    public String caSha256;

    public RelayInfoJce() {
        this.Ip = "";
        this.Port = 0;
        this.Token = "";
        this.SessionID = "";
        this.caSha256 = "";
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.Ip = jceInputStream.readString(0, false);
        this.Port = jceInputStream.read(this.Port, 1, false);
        this.Token = jceInputStream.readString(2, false);
        this.SessionID = jceInputStream.readString(3, false);
        this.caSha256 = jceInputStream.readString(4, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        String str = this.Ip;
        if (str != null) {
            jceOutputStream.write(str, 0);
        }
        jceOutputStream.write(this.Port, 1);
        String str2 = this.Token;
        if (str2 != null) {
            jceOutputStream.write(str2, 2);
        }
        String str3 = this.SessionID;
        if (str3 != null) {
            jceOutputStream.write(str3, 3);
        }
        String str4 = this.caSha256;
        if (str4 != null) {
            jceOutputStream.write(str4, 4);
        }
    }

    public RelayInfoJce(String str, int i, String str2, String str3, String str4) {
        this.Ip = str;
        this.Port = i;
        this.Token = str2;
        this.SessionID = str3;
        this.caSha256 = str4;
    }
}
