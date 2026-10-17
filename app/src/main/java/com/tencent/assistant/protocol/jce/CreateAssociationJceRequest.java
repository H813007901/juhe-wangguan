package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class CreateAssociationJceRequest extends JceStruct {
    public int platform;
    public String token;
    public String version;

    public CreateAssociationJceRequest() {
        this.token = "";
        this.platform = 0;
        this.version = "";
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.token = jceInputStream.readString(0, false);
        this.platform = jceInputStream.read(this.platform, 1, false);
        this.version = jceInputStream.readString(2, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        String str = this.token;
        if (str != null) {
            jceOutputStream.write(str, 0);
        }
        jceOutputStream.write(this.platform, 1);
        String str2 = this.version;
        if (str2 != null) {
            jceOutputStream.write(str2, 2);
        }
    }

    public CreateAssociationJceRequest(String str, int i, String str2) {
        this.token = str;
        this.platform = i;
        this.version = str2;
    }
}
