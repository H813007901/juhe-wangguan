package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class InitiateRemoteControlJceResponse extends JceStruct {
    static SecurityPolicy cache_controlledDeviceToControllingDeviceSecurityPolicy = new SecurityPolicy();
    public SecurityPolicy controlledDeviceToControllingDeviceSecurityPolicy;
    public String msg;
    public int ret;

    public InitiateRemoteControlJceResponse() {
        this.ret = 0;
        this.msg = "";
        this.controlledDeviceToControllingDeviceSecurityPolicy = null;
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.ret = jceInputStream.read(this.ret, 0, true);
        this.msg = jceInputStream.readString(1, false);
        this.controlledDeviceToControllingDeviceSecurityPolicy = (SecurityPolicy) jceInputStream.read((JceStruct) cache_controlledDeviceToControllingDeviceSecurityPolicy, 2, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write(this.ret, 0);
        String str = this.msg;
        if (str != null) {
            jceOutputStream.write(str, 1);
        }
        SecurityPolicy securityPolicy = this.controlledDeviceToControllingDeviceSecurityPolicy;
        if (securityPolicy != null) {
            jceOutputStream.write((JceStruct) securityPolicy, 2);
        }
    }

    public InitiateRemoteControlJceResponse(int i, String str, SecurityPolicy securityPolicy) {
        this.ret = i;
        this.msg = str;
        this.controlledDeviceToControllingDeviceSecurityPolicy = securityPolicy;
    }
}
