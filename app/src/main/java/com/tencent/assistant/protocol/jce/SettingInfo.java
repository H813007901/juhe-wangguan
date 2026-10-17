package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class SettingInfo extends JceStruct {
    static byte[] cache_setting;
    public byte[] setting;
    public byte type;

    static {
        cache_setting = new byte[]{0};
    }

    public SettingInfo() {
        this.type = (byte) 0;
        this.setting = null;
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.type = jceInputStream.read(this.type, 0, true);
        this.setting = jceInputStream.read(cache_setting, 1, true);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write(this.type, 0);
        jceOutputStream.write(this.setting, 1);
    }

    public SettingInfo(byte b2, byte[] bArr) {
        this.type = b2;
        this.setting = bArr;
    }
}
