package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class GetRelayInfoJceRequest extends JceStruct {
    public boolean isSupportWss;
    public String pcGuid;
    public String pcOpenID;

    public GetRelayInfoJceRequest() {
        this.pcOpenID = "";
        this.pcGuid = "";
        this.isSupportWss = true;
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.pcOpenID = jceInputStream.readString(0, false);
        this.pcGuid = jceInputStream.readString(1, false);
        this.isSupportWss = jceInputStream.read(this.isSupportWss, 2, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        String str = this.pcOpenID;
        if (str != null) {
            jceOutputStream.write(str, 0);
        }
        String str2 = this.pcGuid;
        if (str2 != null) {
            jceOutputStream.write(str2, 1);
        }
        jceOutputStream.write(this.isSupportWss, 2);
    }

    public GetRelayInfoJceRequest(String str, String str2, boolean z2) {
        this.pcOpenID = str;
        this.pcGuid = str2;
        this.isSupportWss = z2;
    }
}
