package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class AllocateCloudMarvisJceResponse extends JceStruct {
    public int cloudMarvisPlan;
    public String msg;
    public String pcGuid;
    public long remainingDuration;
    public int ret;
    public long startTime;

    public AllocateCloudMarvisJceResponse() {
        this.ret = 0;
        this.msg = "";
        this.pcGuid = "";
        this.cloudMarvisPlan = 0;
        this.startTime = 0L;
        this.remainingDuration = 0L;
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.ret = jceInputStream.read(this.ret, 0, true);
        this.msg = jceInputStream.readString(1, false);
        this.pcGuid = jceInputStream.readString(2, false);
        this.cloudMarvisPlan = jceInputStream.read(this.cloudMarvisPlan, 3, false);
        this.startTime = jceInputStream.read(this.startTime, 4, false);
        this.remainingDuration = jceInputStream.read(this.remainingDuration, 5, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write(this.ret, 0);
        String str = this.msg;
        if (str != null) {
            jceOutputStream.write(str, 1);
        }
        String str2 = this.pcGuid;
        if (str2 != null) {
            jceOutputStream.write(str2, 2);
        }
        jceOutputStream.write(this.cloudMarvisPlan, 3);
        jceOutputStream.write(this.startTime, 4);
        jceOutputStream.write(this.remainingDuration, 5);
    }

    public AllocateCloudMarvisJceResponse(int i, String str, String str2) {
        this.cloudMarvisPlan = 0;
        this.startTime = 0L;
        this.remainingDuration = 0L;
        this.ret = i;
        this.msg = str;
        this.pcGuid = str2;
    }
}
