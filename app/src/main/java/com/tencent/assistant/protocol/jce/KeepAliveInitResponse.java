package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class KeepAliveInitResponse extends JceStruct {
    static KeepAliveWhiteCmds cache_cmds = new KeepAliveWhiteCmds();
    public String botUid;
    public KeepAliveWhiteCmds cmds;
    public String phoneGuid;
    public int ret;

    public KeepAliveInitResponse() {
        this.ret = 0;
        this.cmds = null;
        this.phoneGuid = "";
        this.botUid = "";
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.ret = jceInputStream.read(this.ret, 0, true);
        this.cmds = (KeepAliveWhiteCmds) jceInputStream.read((JceStruct) cache_cmds, 1, false);
        this.phoneGuid = jceInputStream.readString(2, false);
        this.botUid = jceInputStream.readString(3, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write(this.ret, 0);
        KeepAliveWhiteCmds keepAliveWhiteCmds = this.cmds;
        if (keepAliveWhiteCmds != null) {
            jceOutputStream.write((JceStruct) keepAliveWhiteCmds, 1);
        }
        String str = this.phoneGuid;
        if (str != null) {
            jceOutputStream.write(str, 2);
        }
        String str2 = this.botUid;
        if (str2 != null) {
            jceOutputStream.write(str2, 3);
        }
    }

    public KeepAliveInitResponse(int i, KeepAliveWhiteCmds keepAliveWhiteCmds, String str, String str2) {
        this.ret = i;
        this.cmds = keepAliveWhiteCmds;
        this.phoneGuid = str;
        this.botUid = str2;
    }
}
