package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class SettingCfg extends JceStruct {
    static byte[] cache_cfg;
    public byte[] cfg;
    public long revision;
    public byte type;

    public SettingCfg() {
        this.type = (byte) 0;
        this.cfg = null;
        this.revision = 0L;
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.type = jceInputStream.read(this.type, 0, true);
        if (cache_cfg == null) {
            cache_cfg = new byte[]{0};
        }
        this.cfg = jceInputStream.read(cache_cfg, 1, true);
        this.revision = jceInputStream.read(this.revision, 2, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write(this.type, 0);
        jceOutputStream.write(this.cfg, 1);
        jceOutputStream.write(this.revision, 2);
    }

    public SettingCfg(byte b2, byte[] bArr, long j2) {
        this.type = b2;
        this.cfg = bArr;
        this.revision = j2;
    }
}
