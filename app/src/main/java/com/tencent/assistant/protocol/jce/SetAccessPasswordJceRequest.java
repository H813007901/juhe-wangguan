package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class SetAccessPasswordJceRequest extends JceStruct {
    public String accessPassword;
    public String deviceId;
    public String guid;
    public int loginType;
    public String openId;
    public int platform;

    public SetAccessPasswordJceRequest() {
        this.loginType = 0;
        this.openId = "";
        this.guid = "";
        this.platform = 0;
        this.deviceId = "";
        this.accessPassword = "";
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.loginType = jceInputStream.read(this.loginType, 0, true);
        this.openId = jceInputStream.readString(1, true);
        this.guid = jceInputStream.readString(2, true);
        this.platform = jceInputStream.read(this.platform, 3, true);
        this.deviceId = jceInputStream.readString(4, true);
        this.accessPassword = jceInputStream.readString(5, true);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write(this.loginType, 0);
        jceOutputStream.write(this.openId, 1);
        jceOutputStream.write(this.guid, 2);
        jceOutputStream.write(this.platform, 3);
        jceOutputStream.write(this.deviceId, 4);
        jceOutputStream.write(this.accessPassword, 5);
    }

    public SetAccessPasswordJceRequest(int i, String str, String str2, int i2, String str3, String str4) {
        this.loginType = i;
        this.openId = str;
        this.guid = str2;
        this.platform = i2;
        this.deviceId = str3;
        this.accessPassword = str4;
    }
}
