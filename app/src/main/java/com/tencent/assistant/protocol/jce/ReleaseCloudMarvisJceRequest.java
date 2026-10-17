package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class ReleaseCloudMarvisJceRequest extends JceStruct {
    public String pcGuid;
    public int platform;

    public ReleaseCloudMarvisJceRequest() {
        this.platform = 0;
        this.pcGuid = "";
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.platform = jceInputStream.read(this.platform, 0, true);
        this.pcGuid = jceInputStream.readString(1, true);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write(this.platform, 0);
        String str = this.pcGuid;
        if (str != null) {
            jceOutputStream.write(str, 1);
        }
    }

    public ReleaseCloudMarvisJceRequest(int i, String str) {
        this.platform = i;
        this.pcGuid = str;
    }
}
