package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class ShortCut extends JceStruct {
    public String lnkAddress;
    public String lnkIconUrl;
    public String lnkName;

    public ShortCut() {
        this.lnkName = "";
        this.lnkAddress = "";
        this.lnkIconUrl = "";
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.lnkName = jceInputStream.readString(0, false);
        this.lnkAddress = jceInputStream.readString(1, false);
        this.lnkIconUrl = jceInputStream.readString(2, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        String str = this.lnkName;
        if (str != null) {
            jceOutputStream.write(str, 0);
        }
        String str2 = this.lnkAddress;
        if (str2 != null) {
            jceOutputStream.write(str2, 1);
        }
        String str3 = this.lnkIconUrl;
        if (str3 != null) {
            jceOutputStream.write(str3, 2);
        }
    }

    public ShortCut(String str, String str2, String str3) {
        this.lnkName = str;
        this.lnkAddress = str2;
        this.lnkIconUrl = str3;
    }
}
