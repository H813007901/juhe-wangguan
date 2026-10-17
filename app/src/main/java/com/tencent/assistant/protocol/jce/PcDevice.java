package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class PcDevice extends JceStruct {
    public String guid;
    public String openID;
    public int platform;

    public PcDevice() {
        this.guid = "";
        this.openID = "";
        this.platform = 0;
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.guid = jceInputStream.readString(0, true);
        this.openID = jceInputStream.readString(1, true);
        this.platform = jceInputStream.read(this.platform, 2, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write(this.guid, 0);
        jceOutputStream.write(this.openID, 1);
        jceOutputStream.write(this.platform, 2);
    }

    public PcDevice(String str, String str2, int i) {
        this.guid = str;
        this.openID = str2;
        this.platform = i;
    }
}
