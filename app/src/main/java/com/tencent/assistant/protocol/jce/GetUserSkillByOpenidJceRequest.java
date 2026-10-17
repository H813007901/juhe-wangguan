package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class GetUserSkillByOpenidJceRequest extends JceStruct {
    public String pcGuid;
    public int platform;
    public String tab;

    public GetUserSkillByOpenidJceRequest() {
        this.platform = 0;
        this.pcGuid = "";
        this.tab = "";
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.platform = jceInputStream.read(this.platform, 0, true);
        this.pcGuid = jceInputStream.readString(1, true);
        this.tab = jceInputStream.readString(2, true);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write(this.platform, 0);
        jceOutputStream.write(this.pcGuid, 1);
        jceOutputStream.write(this.tab, 2);
    }

    public GetUserSkillByOpenidJceRequest(int i, String str, String str2) {
        this.platform = i;
        this.pcGuid = str;
        this.tab = str2;
    }
}
