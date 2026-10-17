package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class GetAccountDeviceInfoJceRequest extends JceStruct {
    public String guid;
    public int loginType;
    public String openId;
    public int platform;

    public GetAccountDeviceInfoJceRequest() {
        this.loginType = 0;
        this.openId = "";
        this.guid = "";
        this.platform = 0;
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.loginType = jceInputStream.read(this.loginType, 0, true);
        this.openId = jceInputStream.readString(1, true);
        this.guid = jceInputStream.readString(2, true);
        this.platform = jceInputStream.read(this.platform, 3, true);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write(this.loginType, 0);
        jceOutputStream.write(this.openId, 1);
        jceOutputStream.write(this.guid, 2);
        jceOutputStream.write(this.platform, 3);
    }

    public GetAccountDeviceInfoJceRequest(int i, String str, String str2, int i2) {
        this.loginType = i;
        this.openId = str;
        this.guid = str2;
        this.platform = i2;
    }
}
