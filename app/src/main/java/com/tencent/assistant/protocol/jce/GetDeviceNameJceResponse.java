package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class GetDeviceNameJceResponse extends JceStruct {
    public String deviceName;
    public String msg;
    public int platform;
    public int ret;

    public GetDeviceNameJceResponse() {
        this.ret = 0;
        this.msg = "";
        this.deviceName = "";
        this.platform = 0;
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.ret = jceInputStream.read(this.ret, 0, true);
        this.msg = jceInputStream.readString(1, false);
        this.deviceName = jceInputStream.readString(2, false);
        this.platform = jceInputStream.read(this.platform, 3, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write(this.ret, 0);
        String str = this.msg;
        if (str != null) {
            jceOutputStream.write(str, 1);
        }
        String str2 = this.deviceName;
        if (str2 != null) {
            jceOutputStream.write(str2, 2);
        }
        jceOutputStream.write(this.platform, 3);
    }

    public GetDeviceNameJceResponse(int i, String str, String str2, int i2) {
        this.ret = i;
        this.msg = str;
        this.deviceName = str2;
        this.platform = i2;
    }
}
