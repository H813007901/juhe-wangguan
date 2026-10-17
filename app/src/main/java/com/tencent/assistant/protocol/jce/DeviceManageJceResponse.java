package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class DeviceManageJceResponse extends JceStruct {
    public String msg;
    public int ret;
    public long updateTime;

    public DeviceManageJceResponse() {
        this.ret = 0;
        this.msg = "";
        this.updateTime = 0L;
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.ret = jceInputStream.read(this.ret, 0, true);
        this.msg = jceInputStream.readString(1, false);
        this.updateTime = jceInputStream.read(this.updateTime, 2, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write(this.ret, 0);
        String str = this.msg;
        if (str != null) {
            jceOutputStream.write(str, 1);
        }
        jceOutputStream.write(this.updateTime, 2);
    }

    public DeviceManageJceResponse(int i, String str, long j2) {
        this.ret = i;
        this.msg = str;
        this.updateTime = j2;
    }
}
